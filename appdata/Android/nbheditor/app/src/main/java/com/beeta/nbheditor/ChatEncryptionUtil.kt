package com.beeta.nbheditor

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec

/**
 * Simple AES‑256 encryption utility used for encrypting chat messages.
 * The key should be a 32‑byte (256‑bit) string. For demonstration purposes a static key
 * is used; in production replace it with a securely generated key stored in the
 * Android Keystore.
 */
object ChatEncryptionUtil {
    // NOTE: Replace with a secure key management solution.
    private const val KEY = "0123456789ABCDEF0123456789ABCDEF" // 32 chars = 256 bits
    private const val IV = "ABCDEF0123456789" // 16 bytes IV

    private val secretKey = SecretKeySpec(KEY.toByteArray(Charsets.UTF_8), "AES")
    private val ivSpec = IvParameterSpec(IV.toByteArray(Charsets.UTF_8))

    fun encrypt(plainText: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            plainText // fallback to plain text on error
        }
    }

    fun decrypt(cipherText: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            val decoded = Base64.decode(cipherText, Base64.NO_WRAP)
            String(cipher.doFinal(decoded), Charsets.UTF_8)
        } catch (e: Exception) {
            cipherText // fallback to original on error
        }
    }
}
