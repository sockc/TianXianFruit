package com.tianxian.fruit.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-local Android Keystore protected refresh token.
 *
 * Never store a login password or a refresh token in plaintext preferences.
 * An Android backup may restore preferences without the Keystore key. In that
 * case refuse the unusable ciphertext and ask the user to reauthenticate.
 */
internal class SecureRefreshTokenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "tianxian_secure_cloud_credentials", Context.MODE_PRIVATE
    )
    private val alias = "tianxian_cloud_refresh_aes_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun put(token: String) {
        if (token.isBlank()) {
            clear()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(
            cipher.doFinal(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP
        )
        prefs.edit().putString("sealed_refresh_token", "$iv:$encrypted").apply()
    }

    fun get(): String? {
        val ciphertext = prefs.getString("sealed_refresh_token", null) ?: return null
        return runCatching {
            val split = ciphertext.split(':', limit = 2)
            require(split.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(split[0], Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(split[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun clear() {
        prefs.edit().remove("sealed_refresh_token").apply()
    }
}
