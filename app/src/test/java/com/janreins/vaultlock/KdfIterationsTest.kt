package com.janreins.vaultlock

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.janreins.vaultlock.crypto.CryptoManager
import com.janreins.vaultlock.data.SecurityPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Per-vault PBKDF2 work factor: 600k for new credentials, legacy 150k vaults keep unlocking. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KdfIterationsTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var preferences: SecurityPreferences
    private val password = "MasterPassword123!"

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        prefs = application.getSharedPreferences("kdf_iterations_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        preferences = SecurityPreferences(application, prefs)
    }

    /** Writes credentials the way builds before the stored count did (150k, no count key). */
    private fun writeLegacyCredentials(saltKey: String, verifierKey: String, text: String) {
        val salt = CryptoManager.generateSalt()
        val key = CryptoManager.deriveKey(text.toCharArray(), salt, CryptoManager.LEGACY_ITERATION_COUNT)
        prefs.edit()
            .putBoolean("key_is_setup", true)
            .putString(saltKey, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(verifierKey, CryptoManager.encrypt("VAULTLOCK_VERIFY_PAYLOAD_V1", key))
            .commit()
    }

    @Test
    fun `new vault stores and uses 600k iterations`() {
        val key = preferences.setupMasterPassword(password.toCharArray())
        assertEquals(600_000, preferences.kdfIterations)
        assertNotNull(preferences.verifyAndDeriveKey(password.toCharArray()))
        assertTrue(preferences.verifyKey(key))
    }

    @Test
    fun `legacy vault without a stored count unlocks with 150k`() {
        writeLegacyCredentials("key_master_salt", "key_auth_verifier", password)
        assertEquals(150_000, preferences.kdfIterations)
        assertNotNull(preferences.verifyAndDeriveKey(password.toCharArray()))
        assertNull(preferences.verifyAndDeriveKey("WrongPassword123!".toCharArray()))
    }

    @Test
    fun `password change upgrades a legacy vault to 600k`() {
        writeLegacyCredentials("key_master_salt", "key_auth_verifier", password)
        val newPassword = "NewMasterPassword456!"
        val prepared = preferences.prepareMasterPasswordChange(newPassword.toCharArray())
        assertTrue(preferences.stagePendingMasterPasswordChange(prepared))
        assertNotNull(preferences.verifyPendingAndDeriveKey(newPassword.toCharArray()))
        assertTrue(preferences.promotePendingMasterPasswordChange())
        assertEquals(600_000, preferences.kdfIterations)
        assertNotNull(preferences.verifyAndDeriveKey(newPassword.toCharArray()))
        assertNull(preferences.verifyAndDeriveKey(password.toCharArray()))
    }

    @Test
    fun `journal staged by an older build promotes with the legacy count`() {
        preferences.setupMasterPassword("PreviousPassword789!".toCharArray())
        writeLegacyCredentials("key_pending_master_salt", "key_pending_auth_verifier", password)
        assertNotNull(preferences.verifyPendingAndDeriveKey(password.toCharArray()))
        assertTrue(preferences.promotePendingMasterPasswordChange())
        assertEquals(150_000, preferences.kdfIterations)
        assertNotNull(preferences.verifyAndDeriveKey(password.toCharArray()))
    }

    @Test
    fun `out of range stored count is refused instead of derived`() {
        preferences.setupMasterPassword(password.toCharArray())
        prefs.edit().putInt("key_kdf_iterations", 1_000).commit()
        assertNull(preferences.verifyAndDeriveKey(password.toCharArray()))
        prefs.edit().putInt("key_kdf_iterations", Int.MAX_VALUE).commit()
        assertNull(preferences.verifyAndDeriveKey(password.toCharArray()))
    }

    @Test
    fun `clearing the journal removes its count`() {
        preferences.setupMasterPassword(password.toCharArray())
        val prepared = preferences.prepareMasterPasswordChange("NewMasterPassword456!".toCharArray())
        assertTrue(preferences.stagePendingMasterPasswordChange(prepared))
        assertTrue(preferences.clearPendingMasterPasswordChange())
        assertFalse(preferences.hasPendingMasterPasswordChange())
        assertFalse(prefs.contains("key_pending_kdf_iterations"))
    }
}
