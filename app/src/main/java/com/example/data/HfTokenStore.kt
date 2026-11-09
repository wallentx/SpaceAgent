package com.example.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The encrypted preferences are device-bound and excluded from backup. */
class HfTokenStore internal constructor(context: Context, private val keyProvider: (() -> SecretKey)? = null) {
    private val prefs = context.getSharedPreferences("space_agent_credentials", Context.MODE_PRIVATE)
    private val legacy = context.getSharedPreferences("space_agent_prefs", Context.MODE_PRIVATE)

    fun read(): String = synchronized(lock) {
        val encrypted = prefs.getString("token", null)
        if (encrypted == null) {
            val previous = legacy.getString("hf_api_token", "").orEmpty()
            if (previous.isNotEmpty()) write(previous)
            return@synchronized previous
        }
        val parts = encrypted.split(':', limit = 2)
        require(parts.size == 2) { "Saved token is invalid; save your token again." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyProvider?.invoke() ?: key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        val token = String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        // Retry cleanup after an interrupted migration or a previous failed preference commit.
        removeLegacyToken()
        token
    }

    fun write(token: String) = synchronized(lock) {
        val editor = prefs.edit()
        if (token.isEmpty()) {
            editor.remove("token")
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider?.invoke() ?: key())
            val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            editor.putString("token", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }
        check(editor.commit()) { "Could not save the access token." }
        removeLegacyToken()
    }

    private fun removeLegacyToken() {
        if (legacy.contains("hf_api_token")) {
            check(legacy.edit().remove("hf_api_token").commit()) { "Could not remove the old access token." }
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }

    companion object {
        private const val KEY_ALIAS = "space_agent_hf_token"
        private val lock = Any()
    }
}
