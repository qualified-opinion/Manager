package com.janreins.vaultlock.crypto

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * High-security offline cryptographic manager.
 * - Key derivation: PBKDF2WithHmacSHA256, 256-bit key; 600,000 iterations for new master passwords
 *   (vaults set up before the count was stored use 150,000 until their next password change).
 * - Data encryption: AES-256-GCM with unique 12-byte IV for every encryption operation.
 * - Zero network activity, 100% offline on-device cryptography.
 */
object CryptoManager {
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val AES_GCM_CIPHER = "AES/GCM/NoPadding"
    /** Work factor for new vaults and every master password change. */
    const val ITERATION_COUNT = 600_000
    /** Work factor of vaults whose stored credentials predate a per-vault iteration count. */
    const val LEGACY_ITERATION_COUNT = 150_000
    /** Stored counts outside this range are rejected instead of derived. */
    val ACCEPTED_ITERATION_RANGE = LEGACY_ITERATION_COUNT..2_000_000
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val SALT_LENGTH_BYTES = 32

    private val secureRandom = SecureRandom()

    /**
     * Generates a cryptographically secure random salt of [SALT_LENGTH_BYTES] bytes.
     */
    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        secureRandom.nextBytes(salt)
        return salt
    }

    /**
     * Derives a 256-bit AES SecretKey from the user's Master Password and a salt
     * using PBKDF2 with [iterations] rounds. Clears PBEKeySpec's internal copy of the password
     * and temporary key byte arrays after building the SecretKeySpec; the caller's array is not filled.
     */
    fun deriveKey(masterPassword: CharArray, salt: ByteArray, iterations: Int = ITERATION_COUNT): SecretKey {
        val spec = PBEKeySpec(masterPassword, salt, iterations, KEY_LENGTH_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            val pbeKey = factory.generateSecret(spec)
            val keyBytes = pbeKey.encoded
            try {
                SecretKeySpec(keyBytes, "AES")
            } finally {
                keyBytes?.fill(0)
                try {
                    if (!pbeKey.isDestroyed) {
                        pbeKey.destroy()
                    }
                } catch (_: Exception) {
                    // Ignored best effort destroy
                }
            }
        } finally {
            // Security hardening: zeroize the password array inside the spec to protect memory
            spec.clearPassword()
        }
    }

    /**
     * Encrypts plaintext string using AES-256-GCM.
     * The output format is: [12-byte IV] + [Ciphertext with 16-byte Auth Tag], Base64-encoded.
     */
    fun encrypt(plainText: String, secretKey: SecretKey): String {
        if (plainText.isEmpty()) return ""
        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(AES_GCM_CIPHER)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Decrypts a Base64-encoded AES-256-GCM ciphertext payload with the provided SecretKey.
     */
    fun decrypt(encryptedBase64: String, secretKey: SecretKey): String {
        if (encryptedBase64.isEmpty()) return ""
        val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        if (combined.size < GCM_IV_LENGTH_BYTES) {
            throw IllegalArgumentException("Invalid encrypted payload size")
        }

        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH_BYTES)

        val cipherTextSize = combined.size - GCM_IV_LENGTH_BYTES
        val cipherText = ByteArray(cipherTextSize)
        System.arraycopy(combined, GCM_IV_LENGTH_BYTES, cipherText, 0, cipherTextSize)

        val cipher = Cipher.getInstance(AES_GCM_CIPHER)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        val decryptedBytes = cipher.doFinal(cipherText)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    /**
     * Encrypts raw bytes (used for full vault backup encryption).
     */
    fun encryptBytes(data: ByteArray, secretKey: SecretKey): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(AES_GCM_CIPHER)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        val cipherText = cipher.doFinal(data)
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
        return combined
    }

    /**
     * Decrypts raw bytes (used for full vault backup decryption).
     */
    fun decryptBytes(encryptedData: ByteArray, secretKey: SecretKey): ByteArray {
        if (encryptedData.size < GCM_IV_LENGTH_BYTES) {
            throw IllegalArgumentException("Invalid encrypted payload length")
        }
        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        System.arraycopy(encryptedData, 0, iv, 0, GCM_IV_LENGTH_BYTES)

        val cipherTextSize = encryptedData.size - GCM_IV_LENGTH_BYTES
        val cipherText = ByteArray(cipherTextSize)
        System.arraycopy(encryptedData, GCM_IV_LENGTH_BYTES, cipherText, 0, cipherTextSize)

        val cipher = Cipher.getInstance(AES_GCM_CIPHER)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        return cipher.doFinal(cipherText)
    }
}
