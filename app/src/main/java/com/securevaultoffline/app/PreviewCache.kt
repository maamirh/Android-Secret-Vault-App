package com.securevaultoffline.app

import android.content.Context
import java.io.File

object PreviewCache {
    private const val DIR = "alpha_preview"
    private const val THUMB_DIR = "alpha_thumbs"

    fun dir(context: Context): File = File(context.cacheDir, DIR)

    fun wipe(context: Context) {
        val d = dir(context)
        if (d.isDirectory) d.listFiles()?.forEach { it.delete() }
        val t = File(context.cacheDir, THUMB_DIR)
        if (t.isDirectory) t.listFiles()?.forEach { it.delete() }
    }
}
