package com.blazemuzix.app.auth

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Token vault. API 23+: AES key in the Android Keystore (non-exportable).
 * API 19–22: MODE_PRIVATE SharedPreferences plus a device-local AES key —
 * the strongest isolation those versions offered. Tokens are never logged.
 */
class SecureStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("blaze_secure", Context.MODE_PRIVATE)

    fun put(key: String, value: String?) {
        if (value.isNullOrEmpty()) {
            prefs.edit().remove(pref(key)).remove(iv(key)).apply()
            return
        }
        val pair = encrypt(value.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(pref(key), b64(pair.first))
            .putString(iv(key), b64(pair.second))
            .apply()
    }

    fun get(key: String): String? {
        val text = prefs.getString(pref(key), null) ?: return null
        val ivB = prefs.getString(iv(key), null) ?: return null
        return try {
            String(decrypt(unb64(text), unb64(ivB)), Charsets.UTF_8).takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    fun remove(key: String) = put(key, null)

    private fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
            val iv = cipher.iv
            return cipher.doFinal(plain) to iv
        }
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, legacyKey(), IvParameterSpec(iv))
        return cipher.doFinal(plain) to iv
    }

    private fun decrypt(cipherText: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val key: SecretKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) keystoreKey() else legacyKey()
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        return cipher.doFinal(cipherText)
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                .setKeySize(128)
                .build()
        )
        return gen.generateKey()
    }

    private fun legacyKey(): SecretKey {
        val existing = prefs.getString(LEGACY, null)
        val raw = if (existing != null) {
            unb64(existing)
        } else {
            ByteArray(16).also { SecureRandom().nextBytes(it) }.also {
                prefs.edit().putString(LEGACY, b64(it)).apply()
            }
        }
        return SecretKeySpec(raw, "AES")
    }

    private fun pref(key: String) = "v_$key"
    private fun iv(key: String) = "i_$key"
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(value: String) = Base64.decode(value, Base64.NO_WRAP)

    companion object {
        private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
        private const val ALIAS = "blazemuzix_aes"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val LEGACY = "_legacy_k"
    }
}
