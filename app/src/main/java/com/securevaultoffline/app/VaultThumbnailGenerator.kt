package com.securevaultoffline.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import javax.crypto.Cipher
import kotlin.math.max

/**
 * Builds small JPEG thumbnails for grid preview. Writes under app cache; wiped with [PreviewCache].
 * Returns null if decryption is not allowed without UI, file too large, or type unsupported.
 */
class VaultThumbnailGenerator(private val appContext: Context) {

    private val thumbDir: File
        get() = File(appContext.cacheDir, "alpha_thumbs").apply { mkdirs() }

    fun thumbFileFor(vaultFile: File): File {
        val key = "${vaultFile.absolutePath}_${vaultFile.length()}_${vaultFile.lastModified()}"
        val safe = key.hashCode().toString(16)
        return File(thumbDir, "$safe.jpg")
    }

    /**
     * @param maxDecryptBytes skip thumbnail if ciphertext larger than this (videos).
     */
    fun buildThumbnail(vaultFile: File, maxDecryptBytes: Long = 45L * 1024 * 1024): File? {
        if (!SessionGate.isUnlocked) return null
        if (vaultFile.length() > maxDecryptBytes) return null

        val out = thumbFileFor(vaultFile)
        if (out.isFile && out.lastModified() >= vaultFile.lastModified()) return out

        val kind = VaultMedia.kindForVaultFile(vaultFile)
        if (kind == VaultMediaKind.OTHER) return null

        val cipher = VaultCrypto.newCipher()
        try {
            VaultIo.tryInitDecryptCipher(vaultFile, cipher)
        } catch (e: Exception) {
            if (e.isUserAuthRequired()) return null
            return null
        }

        val temp = File.createTempFile("thumb_src", null, appContext.cacheDir)
        return try {
            VaultIo.decryptVaultFileToPlainFile(vaultFile, temp, cipher)
            when (kind) {
                VaultMediaKind.IMAGE -> {
                    val bmp = decodeScaledBitmap(temp, maxSide = 384) ?: return null
                    FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                    bmp.recycle()
                    out
                }
                VaultMediaKind.VIDEO -> {
                    val bmp = ThumbnailUtils.createVideoThumbnail(
                        temp.absolutePath,
                        MediaStore.Images.Thumbnails.MINI_KIND,
                    ) ?: return null
                    FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                    bmp.recycle()
                    out
                }
                VaultMediaKind.OTHER -> null
            }
        } catch (_: Exception) {
            null
        } finally {
            temp.delete()
            if (out.exists() && out.length() == 0L) out.delete()
        }
    }

    private fun decodeScaledBitmap(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val w = bounds.outWidth
        val h = bounds.outHeight
        val maxDim = max(w, h)
        while (maxDim / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }
}
