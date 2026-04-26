package com.securevaultoffline.app

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.securevaultoffline.app.databinding.ActivityAlphaVideoBinding
import java.io.File

class AlphaVideoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlphaVideoBinding
    private var player: ExoPlayer? = null
    private var previewFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySecureWindow()
        binding = ActivityAlphaVideoBinding.inflate(layoutInflater)
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

        val player = ExoPlayer.Builder(this).build()
        this.player = player
        binding.playerView.player = player
        @Suppress("DEPRECATION")
        val uri = Uri.fromFile(f)
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
    }

    override fun onStart() {
        super.onStart()
        if (!SessionGate.isUnlocked) finish()
    }

    override fun onResume() {
        super.onResume()
        if (!SessionGate.isUnlocked || previewFile?.isFile != true) finish()
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        previewFile?.delete()
        previewFile = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "path"
    }
}
