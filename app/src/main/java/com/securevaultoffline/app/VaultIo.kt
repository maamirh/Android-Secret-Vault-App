package com.securevaultoffline.app

import java.io.File
import javax.crypto.Cipher

object VaultIo {
    /** Decrypt entire vault file to [dest] using an already-initialized [cipher] for decrypt. */
    fun decryptVaultFileToPlainFile(vaultFile: File, dest: File, cipher: Cipher) {
        vaultFile.inputStream().use { input ->
            val iv = ByteArray(12)
            require(input.read(iv) == 12) { "Corrupt vault file" }
            VaultCrypto.initDecryptCipher(cipher, iv)
            dest.outputStream().use { output ->
                VaultCrypto.decryptStream(input, output, cipher)
            }
        }
    }

    /** Peek IV and return whether [cipher] can be initialized for decrypt (may throw if auth required). */
    fun tryInitDecryptCipher(vaultFile: File, cipher: Cipher) {
        vaultFile.inputStream().use { input ->
            val iv = ByteArray(12)
            require(input.read(iv) == 12) { "Corrupt vault file" }
            VaultCrypto.initDecryptCipher(cipher, iv)
        }
    }
}
