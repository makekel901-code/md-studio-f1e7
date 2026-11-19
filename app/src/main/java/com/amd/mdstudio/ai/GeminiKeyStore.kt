package com.amd.mdstudio.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Local encrypted storage for multiple Gemini API keys. */
class GeminiKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("mdstudio_secrets", Context.MODE_PRIVATE)
    private val alias = "mdstudio_aes_key"

    @Synchronized fun addKey(key: String) {
        val keys = readKeys().toMutableList()
        if (key !in keys) keys.add(key)
        prefs.edit().putInt("gemini_count", keys.size).apply()
        keys.forEachIndexed { i, value -> prefs.edit().putString("gemini_$i", encrypt(value)).apply() }
    }

    @Synchronized fun readKeys(): List<String> {
        val count = prefs.getInt("gemini_count", 0)
        return (0 until count).mapNotNull { i -> prefs.getString("gemini_$i", null)?.let(::decrypt) }
    }

    @Synchronized fun removeKey(index: Int) {
        val keys = readKeys().toMutableList()
        if (index !in keys.indices) return
        keys.removeAt(index)
        val edit = prefs.edit().putInt("gemini_count", keys.size)
        (0 until maxOf(prefs.getInt("gemini_count", 0), keys.size + 1)).forEach { edit.remove("gemini_$it") }
        edit.apply()
        keys.forEachIndexed { i, value -> prefs.edit().putString("gemini_$i", encrypt(value)).apply() }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String? = runCatching {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, 12)
        val payload = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(payload), Charsets.UTF_8)
    }.getOrNull()
}
