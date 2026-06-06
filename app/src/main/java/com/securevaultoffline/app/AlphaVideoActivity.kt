package com.securevaultoffline.app

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.securevaultoffline.app.databinding.ActivityAlphaVideoBinding
import java.io.File
import javax.crypto.Cipher

@OptIn(UnstableApi::class)
class AlphaVideoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlphaVideoBinding
    private var player: ExoPlayer? = null
    private var vaultFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySecureWindow()
        binding = ActivityAlphaVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyPreviewEdgeToEdge(binding.previewCoordinator)
        ViewCompat.setAccessibilityPaneTitle(binding.previewCoordinator, getString(R.string.a11y_pane_video_preview))
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (!SessionGate.isUnlocked) {
            finish()
            return
        }

        val path = intent.getStringExtra(EXTRA_VAULT_PATH) ?: run {
            finish()
            return
        }
        val f = File(path)
        vaultFile = f
        if (!f.isFile) {
            finish()
            return
        }

        if (!canUseBiometricOrDeviceCredential()) {
            Toast.makeText(this, R.string.settings_biometrics_required, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)?.trim().orEmpty()
        binding.playerView.contentDescription = if (displayName.isNotEmpty()) {
            getString(R.string.a11y_video_preview_named, displayName)
        } else {
            getString(R.string.a11y_video_preview_unnamed)
        }
        supportActionBar?.title =
            if (displayName.isNotEmpty()) displayName else getString(R.string.a11y_video_preview_unnamed)

        beginPlaybackAuth(f)
    }

    private fun beginPlaybackAuth(f: File) {
        val cipher = VaultCrypto.newCipher()
        try {
            VaultIo.tryInitDecryptCipher(f, cipher)
            attachExoPlayer(f)
        } catch (e: Exception) {
            if (handleKeystoreInvalidated(e)) {
                finish()
                return
            }
            if (!e.isUserAuthRequired()) {
                Toast.makeText(
                    this,
                    e.message ?: getString(R.string.error_decrypt_generic),
                    Toast.LENGTH_LONG,
                ).show()
                finish()
                return
            }
            showDecryptBiometric(cipher, f)
        }
    }

    private fun attachExoPlayer(f: File) {
        releasePlayerInternal()
        val dataSourceFactory = VaultDecryptingDataSource.Factory(f)
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)
        val player = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory).build()
        this.player = player
        binding.playerView.player = player
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
        player.prepare()
        player.playWhenReady = true
    }

    private fun showDecryptBiometric(cipher: Cipher, f: File) {
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (result.cryptoObject?.cipher == null) {
                    Toast.makeText(
                        this@AlphaVideoActivity,
                        getString(R.string.error_internal_auth),
                        Toast.LENGTH_LONG,
                    ).show()
                    finish()
                    return
                }
                attachExoPlayer(f)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                ) {
                    Toast.makeText(this@AlphaVideoActivity, errString, Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }
        val prompt = BiometricPrompt(this, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.preview_video))
            .setSubtitle(f.name)
            .setAllowedAuthenticators(allowedAuthenticators())
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun canUseBiometricOrDeviceCredential(): Boolean {
        val authenticators = allowedAuthenticators()
        val r = BiometricManager.from(this).canAuthenticate(authenticators)
        return r == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun allowedAuthenticators(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }
    }

    private fun handleKeystoreInvalidated(e: Throwable): Boolean {
        val invalidated = e is KeyPermanentlyInvalidatedException ||
            generateSequence(e as Throwable?) { it.cause }
                .any { it is KeyPermanentlyInvalidatedException }
        if (invalidated) {
            AlertDialog.Builder(this)
                .setTitle("Vault key reset")
                .setMessage(
                    "Biometric enrollment changed or the hardware key was invalidated. " +
                        "Old vault files can no longer be decrypted.",
                )
                .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                .show()
            return true
        }
        return false
    }

    private fun releasePlayerInternal() {
        binding.playerView.player = null
        player?.release()
        player = null
    }

    override fun onStart() {
        super.onStart()
        if (!SessionGate.isUnlocked) finish()
    }

    override fun onResume() {
        super.onResume()
        if (!SessionGate.isUnlocked || vaultFile?.isFile != true) finish()
    }

    override fun onPause() {
        player?.pause()
        super.onPause()
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        releasePlayerInternal()
        vaultFile = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VAULT_PATH = "vault_path"
        const val EXTRA_DISPLAY_NAME = "display_name"
    }
}
