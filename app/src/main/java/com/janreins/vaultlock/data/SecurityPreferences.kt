package com.janreins.vaultlock.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.janreins.vaultlock.crypto.CryptoManager
import javax.crypto.SecretKey

/**
 * Security preferences stored in Android Keystore backed EncryptedSharedPreferences.
 * Stores encryption salt, authentication verifier token, wrapped master keys, and app flags.
 */
class SecurityPreferences(context: Context, customPrefs: SharedPreferences? = null) {

    private val prefs: SharedPreferences = customPrefs ?: run {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context.applicationContext,
            ENCRYPTED_PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    init {
        // Seamless one-time migration from legacy plain SharedPreferences if present
        migrateLegacyPlainPrefsIfPresent(context)
    }

    private fun migrateLegacyPlainPrefsIfPresent(context: Context) {
        try {
            val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_FILE, Context.MODE_PRIVATE)
            if (legacyPrefs.contains(KEY_IS_SETUP) && !prefs.contains(KEY_IS_SETUP)) {
                val editor = prefs.edit()
                legacyPrefs.all.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is String -> editor.putString(key, value)
                        is Long -> editor.putLong(key, value)
                        is Int -> editor.putInt(key, value)
                        is Float -> editor.putFloat(key, value)
                    }
                }
                // Keep the only recoverable copy unless the encrypted write is durable.
                check(editor.commit()) { "Could not migrate security preferences" }
                legacyPrefs.edit().clear().commit()
            }
        } catch (e: Exception) {
            // Fail closed: do not present setup over a vault whose credentials failed migration.
            throw IllegalStateException("Security migration failed; original credentials retained. Retry opening the app.", e)
        }
    }

    companion object {
        private const val ENCRYPTED_PREFS_FILE = "vaultlock_security_encrypted_prefs"
        private const val LEGACY_PREFS_FILE = "vaultlock_security_prefs"

        private const val KEY_IS_SETUP = "key_is_setup"
        private const val KEY_SALT = "key_master_salt"
        private const val KEY_VERIFIER = "key_auth_verifier"
        private const val KEY_PENDING_SALT = "key_pending_master_salt"
        private const val KEY_PENDING_VERIFIER = "key_pending_auth_verifier"
        // Absent on vaults created before the count was stored: those use LEGACY_ITERATION_COUNT.
        private const val KEY_KDF_ITERATIONS = "key_kdf_iterations"
        private const val KEY_PENDING_KDF_ITERATIONS = "key_pending_kdf_iterations"
        private const val KEY_BIOMETRIC_ENABLED = "key_biometric_enabled"
        private const val KEY_WRAPPED_KEY = "key_wrapped_master_key"
        private const val KEY_AUTO_LOCK_SECONDS = "key_auto_lock_seconds"
        private const val KEY_THEME_MODE = "key_theme_mode" // "system", "dark", "light"
        private const val KEY_FAILED_ATTEMPTS = "key_failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "key_lockout_until"
        private const val VERIFIER_MAGIC = "VAULTLOCK_VERIFY_PAYLOAD_V1"
    }

    val isMasterPasswordSet: Boolean
        get() = prefs.getBoolean(KEY_IS_SETUP, false)

    val isBiometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)

    val autoLockSeconds: Long
        get() = prefs.getLong(KEY_AUTO_LOCK_SECONDS, 120L) // Default 2 minutes

    val themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, "dark") ?: "dark"

    data class PreparedMasterPassword(
        val secretKey: SecretKey,
        val saltBase64: String,
        val verifierEncrypted: String,
        val iterations: Int = CryptoManager.ITERATION_COUNT
    )

    /**
     * Prepares new master password credentials (salt, derived key, verifier token) in memory
     * WITHOUT persisting them to SharedPreferences.
     */
    fun prepareMasterPasswordChange(password: CharArray): PreparedMasterPassword {
        val salt = CryptoManager.generateSalt()
        val iterations = CryptoManager.ITERATION_COUNT
        val derivedKey = CryptoManager.deriveKey(password, salt, iterations)
        val verifierEncrypted = CryptoManager.encrypt(VERIFIER_MAGIC, derivedKey)
        val saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        return PreparedMasterPassword(derivedKey, saltBase64, verifierEncrypted, iterations)
    }

    /**
     * Persists prepared master password credentials to SharedPreferences using blocking commit().
     */
    fun commitMasterPasswordChange(prepared: PreparedMasterPassword): Boolean {
        return prefs.edit()
            .putBoolean(KEY_IS_SETUP, true)
            .putString(KEY_SALT, prepared.saltBase64)
            .putString(KEY_VERIFIER, prepared.verifierEncrypted)
            .putInt(KEY_KDF_ITERATIONS, prepared.iterations)
            .remove(KEY_PENDING_SALT)
            .remove(KEY_PENDING_VERIFIER)
            .remove(KEY_PENDING_KDF_ITERATIONS)
            .putLong(KEY_AUTO_LOCK_SECONDS, prefs.getLong(KEY_AUTO_LOCK_SECONDS, 120L))
            .commit()
    }

    /**
     * Initializes the Master Password for the first time or updates it immediately.
     * Generates a unique 32-byte salt, derives the Master Key via PBKDF2 ([CryptoManager.ITERATION_COUNT]
     * iterations), and stores the iteration count and encrypted magic verification token in
     * EncryptedSharedPreferences.
     */
    fun setupMasterPassword(password: CharArray): SecretKey {
        val prepared = prepareMasterPasswordChange(password)
        if (!commitMasterPasswordChange(prepared)) {
            throw IllegalStateException("Failed to persist Master Password")
        }
        return prepared.secretKey
    }

    /**
     * Verifies the user entered Master Password against the stored verifier payload.
     * Returns the derived SecretKey if valid, or null if incorrect.
     */
    fun verifyAndDeriveKey(password: CharArray): SecretKey? {
        return verifyAndDeriveKey(password, KEY_SALT, KEY_VERIFIER, KEY_KDF_ITERATIONS)
    }

    fun verifyPendingAndDeriveKey(password: CharArray): SecretKey? {
        return verifyAndDeriveKey(password, KEY_PENDING_SALT, KEY_PENDING_VERIFIER, KEY_PENDING_KDF_ITERATIONS)
    }

    fun stagePendingMasterPasswordChange(prepared: PreparedMasterPassword): Boolean {
        return prefs.edit()
            .putString(KEY_PENDING_SALT, prepared.saltBase64)
            .putString(KEY_PENDING_VERIFIER, prepared.verifierEncrypted)
            .putInt(KEY_PENDING_KDF_ITERATIONS, prepared.iterations)
            .commit()
    }

    fun hasPendingMasterPasswordChange(): Boolean {
        return prefs.contains(KEY_PENDING_SALT) || prefs.contains(KEY_PENDING_VERIFIER)
    }

    fun clearPendingMasterPasswordChange(): Boolean {
        return prefs.edit().remove(KEY_PENDING_SALT).remove(KEY_PENDING_VERIFIER)
            .remove(KEY_PENDING_KDF_ITERATIONS).commit()
    }

    fun promotePendingMasterPasswordChange(): Boolean {
        val salt = prefs.getString(KEY_PENDING_SALT, null) ?: return false
        val verifier = prefs.getString(KEY_PENDING_VERIFIER, null) ?: return false
        // A journal staged by an older build has no count; it was derived with the legacy count.
        val iterations = prefs.getInt(KEY_PENDING_KDF_ITERATIONS, CryptoManager.LEGACY_ITERATION_COUNT)
        return prefs.edit()
            .putBoolean(KEY_IS_SETUP, true)
            .putString(KEY_SALT, salt)
            .putString(KEY_VERIFIER, verifier)
            .putInt(KEY_KDF_ITERATIONS, iterations)
            .remove(KEY_PENDING_SALT)
            .remove(KEY_PENDING_VERIFIER)
            .remove(KEY_PENDING_KDF_ITERATIONS)
            .commit()
    }

    /** Checks the current verifier without deriving another key. */
    fun verifyKey(key: SecretKey): Boolean {
        val verifier = prefs.getString(KEY_VERIFIER, null) ?: return false
        return try {
            CryptoManager.decrypt(verifier, key) == VERIFIER_MAGIC
        } catch (_: Exception) {
            false
        }
    }

    /** Iteration count stored with the current credentials; vaults from older builds use the legacy count. */
    val kdfIterations: Int
        get() = prefs.getInt(KEY_KDF_ITERATIONS, CryptoManager.LEGACY_ITERATION_COUNT)

    private fun verifyAndDeriveKey(
        password: CharArray,
        saltKey: String,
        verifierKey: String,
        iterationsKey: String
    ): SecretKey? {
        val saltBase64 = prefs.getString(saltKey, null) ?: return null
        val verifierEncrypted = prefs.getString(verifierKey, null) ?: return null
        val iterations = prefs.getInt(iterationsKey, CryptoManager.LEGACY_ITERATION_COUNT)
        if (iterations !in CryptoManager.ACCEPTED_ITERATION_RANGE) return null

        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val derivedKey = CryptoManager.deriveKey(password, salt, iterations)

        return try {
            val decrypted = CryptoManager.decrypt(verifierEncrypted, derivedKey)
            if (decrypted == VERIFIER_MAGIC) {
                derivedKey
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns the wrapped master key string if biometric unlock is enabled.
     */
    fun getWrappedMasterKey(): String? {
        if (!isBiometricEnabled) return null
        return prefs.getString(KEY_WRAPPED_KEY, null)
    }

    /**
     * Saves the Keystore-wrapped master key token.
     */
    fun saveBiometricWrappedKey(wrappedKeyBase64: String) {
        prefs.edit()
            .putBoolean(KEY_BIOMETRIC_ENABLED, true)
            .putString(KEY_WRAPPED_KEY, wrappedKeyBase64)
            .apply()
    }

    /**
     * Disables biometric unlock and purges the stored wrapped key.
     */
    fun disableBiometric() {
        prefs.edit()
            .putBoolean(KEY_BIOMETRIC_ENABLED, false)
            .remove(KEY_WRAPPED_KEY)
            .apply()
    }

    fun setAutoLockSeconds(seconds: Long) {
        prefs.edit().putLong(KEY_AUTO_LOCK_SECONDS, seconds).apply()
    }

    fun setThemeMode(mode: String) {
        prefs.edit().putString(KEY_THEME_MODE, mode).apply()
    }

    fun getFailedUnlockAttempts(): Int = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)

    fun getLockoutUntil(): Long = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)

    fun getLockoutRemainingMillis(): Long {
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        val remaining = lockoutUntil - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0L
    }

    fun calculateBackoffDelaySeconds(attempts: Int): Long {
        if (attempts <= 0) return 0L
        val exponent = (attempts - 1).coerceAtMost(30)
        val delay = 1L shl exponent
        return delay.coerceAtMost(60L)
    }

    fun recordFailedUnlockAttempt(): Long {
        val currentAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        val delaySeconds = calculateBackoffDelaySeconds(currentAttempts)
        val lockoutUntil = System.currentTimeMillis() + delaySeconds * 1000L

        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, currentAttempts)
            .putLong(KEY_LOCKOUT_UNTIL, lockoutUntil)
            .apply()

        return delaySeconds
    }

    fun resetFailedUnlockAttempts() {
        prefs.edit()
            .remove(KEY_FAILED_ATTEMPTS)
            .remove(KEY_LOCKOUT_UNTIL)
            .apply()
    }

    fun wipeAll() {
        prefs.edit().clear().apply()
    }
}
