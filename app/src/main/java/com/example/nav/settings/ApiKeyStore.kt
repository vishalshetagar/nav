package com.example.nav.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound encryption; legacy plaintext preferences are migrated on first read. */
class ApiKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("nav_prefs", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun read(): String {
        val legacy = prefs.getString("groq_api_key", null)
        if (legacy != null) {
            // Keep the old value only if migration fails; never discard an unreadable key silently.
            return runCatching { write(legacy); legacy }.getOrDefault("")
        }
        val stored = prefs.getString("groq_api_key_encrypted", null) ?: return ""
        return runCatching {
            val (iv, data) = stored.split(':', limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun write(value: String) {
        val normalized = value.trim().replace(Regex("^Bearer\\s+", RegexOption.IGNORE_CASE), "")
        if (normalized.isEmpty()) {
            check(prefs.edit().remove("groq_api_key").remove("groq_api_key_encrypted").commit())
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
        check(prefs.edit().putString("groq_api_key_encrypted", encoded).remove("groq_api_key").commit())
    }

    companion object { private const val ALIAS = "nav_groq_key_v1" }
}
