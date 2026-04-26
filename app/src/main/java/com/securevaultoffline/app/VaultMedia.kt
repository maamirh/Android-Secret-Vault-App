package com.securevaultoffline.app

import java.io.File
import java.util.Locale

enum class VaultMediaKind { IMAGE, VIDEO, OTHER }

object VaultMedia {
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    private val VIDEO_EXT = setOf(
        "mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v", "mpeg", "mpg", "ts", "m2ts", "wmv", "asf",
    )

    /** Strip trailing `.vault` regardless of casing (e.g. `.MP4.Vault`). */
    fun stripVaultSuffix(fileName: String): String {
        if (!fileName.endsWith(".vault", ignoreCase = true)) return fileName
        return fileName.substring(0, fileName.length - ".vault".length)
    }

    fun kindForVaultFile(file: File): VaultMediaKind {
        val inner = stripVaultSuffix(file.name)
        val ext = inner.substringAfterLast('.', "").lowercase(Locale.US)
        return when {
            ext in IMAGE_EXT -> VaultMediaKind.IMAGE
            ext in VIDEO_EXT -> VaultMediaKind.VIDEO
            else -> VaultMediaKind.OTHER
        }
    }

    fun displayName(file: File): String = stripVaultSuffix(file.name)
}
