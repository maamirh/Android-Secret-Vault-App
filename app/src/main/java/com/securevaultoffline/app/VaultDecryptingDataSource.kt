package com.securevaultoffline.app

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import javax.crypto.CipherInputStream
import kotlin.math.min

/**
 * Streams decrypted plaintext from a `.vault` file (12-byte IV + AES-GCM ciphertext + 16-byte tag)
 * for ExoPlayer without writing a full temporary file first.
 *
 * GCM is not seekable; [open] implements logical offsets by decrypting from the start and discarding
 * bytes until [DataSpec.position] (slow on scrub, fast for sequential playback from the beginning).
 */
@UnstableApi
class VaultDecryptingDataSource(
    private val vaultFile: File,
) : DataSource {

    private var cipherInput: CipherInputStream? = null
    private var fileInput: FileInputStream? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0

    class Factory(private val vaultFile: File) : DataSource.Factory {
        override fun createDataSource(): DataSource = VaultDecryptingDataSource(vaultFile)
    }

    override fun addTransferListener(transferListener: TransferListener) {}

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        close()
        uri = dataSpec.uri

        val plainLen = vaultPlaintextLength(vaultFile)
        if (plainLen < 0L) {
            throw IOException("Invalid vault file size")
        }

        val fis = FileInputStream(vaultFile)
        fileInput = fis
        val iv = ByteArray(12)
        if (fis.read(iv) != 12) {
            close()
            throw IOException("Missing IV")
        }
        val cipher = VaultCrypto.newCipher()
        VaultCrypto.initDecryptCipher(cipher, iv)
        val cis = CipherInputStream(fis, cipher)
        cipherInput = cis

        var toDiscard = dataSpec.position
        val buf = ByteArray(128 * 1024)
        while (toDiscard > 0) {
            val chunk = min(buf.size.toLong(), toDiscard).toInt()
            val n = cis.read(buf, 0, chunk)
            if (n < 0) {
                close()
                throw IOException("Unexpected EOF while seeking")
            }
            toDiscard -= n
        }

        val available = plainLen - dataSpec.position
        if (available < 0) {
            close()
            throw IOException("Invalid offset")
        }
        val requested = dataSpec.length
        bytesRemaining = if (requested == C.LENGTH_UNSET.toLong()) {
            available
        } else {
            min(available, requested)
        }
        return bytesRemaining
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT
        val cis = cipherInput ?: throw IOException("Not opened")
        val toRead = min(length.toLong(), bytesRemaining).toInt()
        var total = 0
        var zeroSpin = 0
        while (total < toRead) {
            val n = cis.read(buffer, offset + total, toRead - total)
            if (n < 0) {
                bytesRemaining = 0
                return if (total == 0) C.RESULT_END_OF_INPUT else total
            }
            if (n == 0) {
                zeroSpin++
                if (zeroSpin > 10_000) throw IOException("Stalled decrypt read")
                continue
            }
            zeroSpin = 0
            total += n
            bytesRemaining -= n.toLong()
        }
        return total
    }

    override fun getUri(): Uri? = uri

    @Throws(IOException::class)
    override fun close() {
        runCatching { cipherInput?.close() }
        cipherInput = null
        fileInput = null
        uri = null
        bytesRemaining = 0
    }

    companion object {
        /** Plaintext byte length for a vault blob written by [VaultCrypto.encryptStream]. */
        fun vaultPlaintextLength(file: File): Long {
            val len = file.length()
            val cipherLen = len - 12L
            if (cipherLen <= 16L) return -1L
            return cipherLen - 16L
        }
    }
}
