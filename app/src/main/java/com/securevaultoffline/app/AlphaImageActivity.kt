package com.securevaultoffline.app

import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.securevaultoffline.app.databinding.ActivityAlphaImageBinding
import java.io.File

class AlphaImageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlphaImageBinding
    private var previewFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySecureWindow()
        binding = ActivityAlphaImageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (!SessionGate.isUnlocked) {
            finish()
            return
        }

        val path = intent.getStringExtra(EXTRA_PATH) ?: run {
            finish()
            return
        }
        val f = File(path)
        previewFile = f
        if (!f.isFile) {
            finish()
            return
        }

        val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(f))
        } else {
            @Suppress("DEPRECATION")
            android.graphics.BitmapFactory.decodeFile(f.absolutePath)
        }
        binding.imageView.setImageBitmap(bmp)
    }

    override fun onStart() {
        super.onStart()
        if (!SessionGate.isUnlocked) finish()
    }

    override fun onResume() {
        super.onResume()
        if (!SessionGate.isUnlocked || previewFile?.isFile != true) finish()
    }

    override fun onDestroy() {
        previewFile?.delete()
        previewFile = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "path"
    }
}
