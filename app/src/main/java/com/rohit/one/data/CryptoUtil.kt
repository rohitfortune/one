package com.rohit.one.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CryptoUtil {
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "com.rohit.one.master_key"
    private const val AES_MODE = "AES/GCM/NoPadding"
    // GCM authentication tag length in bits and bytes (128 bits == 16 bytes)
    private const val TAG_LENGTH_BITS = 128
    private const val TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) return existingKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun encrypt(plainText: String): String {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        // store IV length + IV + cipherText
        val byteBuffer = ByteBuffer.allocate(4 + iv.size + cipherBytes.size)
        byteBuffer.putInt(iv.size)
        byteBuffer.put(iv)
        byteBuffer.put(cipherBytes)
        return Base64.encodeToString(byteBuffer.array(), Base64.NO_WRAP)
    }

    fun decrypt(encrypted: String?): String? {
        if (encrypted == null) return null
        try {
            val decoded = Base64.decode(encrypted, Base64.NO_WRAP)
            val byteBuffer = ByteBuffer.wrap(decoded)
            val ivLength = byteBuffer.int
            // IV length should typically be 12 for GCM, but accept common lengths in range
            if (ivLength !in 12..TAG_LENGTH_BYTES) return null
            val iv = ByteArray(ivLength)
            byteBuffer.get(iv)
            val cipherBytes = ByteArray(byteBuffer.remaining())
            byteBuffer.get(cipherBytes)
            val cipher = Cipher.getInstance(AES_MODE)
            // GCMParameterSpec expects tag length in bits
            val spec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
            val secretKey = getOrCreateSecretKey()
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val plain = cipher.doFinal(cipherBytes)
            return String(plain, Charsets.UTF_8)
        } catch (_: Exception) {
            // decryption failed
            return null
        }
    }
}
