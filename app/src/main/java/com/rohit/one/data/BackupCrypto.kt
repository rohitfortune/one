package com.rohit.one.data

import android.util.Base64
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupCrypto {
    private const val AES_MODE = "AES/GCM/NoPadding"
    private const val TAG_LENGTH = 128
    private const val SALT_SIZE = 16
    private const val ITERATIONS = 100_000
    private const val KEY_LENGTH = 256

    private val random = SecureRandom()

    fun encryptWithPassphrase(plainText: String, passphrase: CharArray): String {
        val salt = ByteArray(SALT_SIZE).also { random.nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val byteBuffer = ByteBuffer.allocate(4 + salt.size + 4 + iv.size + cipherBytes.size)
        byteBuffer.putInt(salt.size)
        byteBuffer.put(salt)
        byteBuffer.putInt(iv.size)
        byteBuffer.put(iv)
        byteBuffer.put(cipherBytes)
        return Base64.encodeToString(byteBuffer.array(), Base64.NO_WRAP)
    }

    fun decryptWithPassphrase(encrypted: String?, passphrase: CharArray): String? {
        if (encrypted == null) return null
        try {
            val decoded = Base64.decode(encrypted, Base64.NO_WRAP)
            val bb = ByteBuffer.wrap(decoded)
            val saltLen = bb.int
            val salt = ByteArray(saltLen)
            bb.get(salt)
            val ivLen = bb.int
            val iv = ByteArray(ivLen)
            bb.get(iv)
            val cipherBytes = ByteArray(bb.remaining())
            bb.get(cipherBytes)
            val key = deriveKey(passphrase, salt)
            val cipher = Cipher.getInstance(AES_MODE)
            val spec = GCMParameterSpec(TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val plain = cipher.doFinal(cipherBytes)
            return String(plain, Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_LENGTH)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = skf.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }
}

