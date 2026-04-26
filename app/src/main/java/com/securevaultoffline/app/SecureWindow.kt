package com.securevaultoffline.app

import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

fun AppCompatActivity.applySecureWindow() {
    window.setFlags(
        WindowManager.LayoutParams.FLAG_SECURE,
        WindowManager.LayoutParams.FLAG_SECURE,
    )
}
