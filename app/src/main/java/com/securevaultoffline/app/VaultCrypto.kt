package com.securevaultoffline.app

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed AES-256-GCM. Key use is gated by fingerprint / face / device PIN
 * (via [androidx.biometric.BiometricPrompt] + [javax.crypto.Cipher]).
 *
 * Encrypted file format: 12-byte IV + ciphertext + 128-bit auth tag (GCM).
 */
object VaultCrypto {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "secure_vault_master_aes_gcm_v1"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_LENGTH = 12

    /**
     * After a successful biometric / device-credential proof, Keystore allows this key to be used
     * for this many seconds without re-prompting (in-session import, preview, export).
     * The app still clears the *session* when leaving the foreground via [SessionGate].
     */
    private const val AUTH_VALIDITY_SECONDS = 600


    fun keyExists(): Boolean {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return ks.containsAlias(KEY_ALIAS)
    }

    fun createVaultKeyIfNeeded() {
        if (keyExists()) return
        val spec = buildKeySpec()
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kg.init(spec)
        kg.generateKey()
    }

    private fun buildKeySpec(): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                AUTH_VALIDITY_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        } else {
            // API 28–29: device PIN cannot be bound to AES in Keystore; biometrics only.
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
        }
        return builder.build()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return ks.getKey(KEY_ALIAS, null) as SecretKey
    }

    fun newCipher(): Cipher = Cipher.getInstance(AES_GCM)

    fun initEncryptCipher(cipher: Cipher) {
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
    }

    fun initDecryptCipher(cipher: Cipher, iv: ByteArray) {
        require(iv.size >= GCM_IV_LENGTH) { "IV too short" }
        val ivBytes = if (iv.size == GCM_IV_LENGTH) iv else iv.copyOfRange(0, GCM_IV_LENGTH)
        val spec = GCMParameterSpec(GCM_TAG_BITS, ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), spec)
    }

    /**
     * Streams data from [input] to [output] after encrypting it with [cipher].
     * The IV is written to [output] first.
     */
    fun encryptStream(input: InputStream, output: OutputStream, cipher: Cipher) {
        val iv = cipher.iv ?: error("missing GCM IV")
        output.write(iv)
        val cos = CipherOutputStream(output, cipher)
        input.use { it.copyTo(cos) }
        cos.close() // Important to flush and write the GCM tag
    }

    /**
     * Streams data from [input] (after the IV) to [output] after decrypting it with [cipher].
     */
    fun decryptStream(input: InputStream, output: OutputStream, cipher: Cipher) {
        val cis = CipherInputStream(input, cipher)
        cis.use { it.copyTo(output) }
    }
}
