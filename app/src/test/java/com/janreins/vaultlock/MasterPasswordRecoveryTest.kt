package com.janreins.vaultlock

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.janreins.vaultlock.crypto.SessionManager
import com.janreins.vaultlock.data.SecurityPreferences
import com.janreins.vaultlock.data.VaultDatabase
import com.janreins.vaultlock.data.VaultEntry
import com.janreins.vaultlock.data.VaultRepository
import com.janreins.vaultlock.ui.VaultViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MasterPasswordRecoveryTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var application: Application
    private lateinit var preferences: SecurityPreferences
    private lateinit var faultPrefs: FailingCommitPreferences
    private lateinit var database: VaultDatabase
    private lateinit var repository: VaultRepository
    private val oldPassword = "OldMasterPassword123!"
    private val newPassword = "NewMasterPassword456!"

    @Before
    fun setUp() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        application = ApplicationProvider.getApplicationContext()
        val prefs = application.getSharedPreferences("rotation_recovery_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        faultPrefs = FailingCommitPreferences(prefs)
        preferences = SecurityPreferences(application, faultPrefs)
        database = VaultDatabase.getInstance(application)
        database.vaultDao().deleteAllEntries()
        repository = VaultRepository(database.vaultDao(), preferences)
        SessionManager.lock()
    }

    @After
    fun tearDown() {
        SessionManager.lock()
        Dispatchers.resetMain()
    }

    private suspend fun seedVault(): Long {
        SessionManager.setKey(preferences.setupMasterPassword(oldPassword.toCharArray()))
        repository.saveEntry(VaultEntry(title = "Private account", username = "alice", password = "secret"))
        return database.vaultDao().getAllEntriesSync().single().id
    }

    private suspend fun TestScope.unlock(model: VaultViewModel, password: String): Pair<Boolean, String> {
        val result = CompletableDeferred<Pair<Boolean, String>>()
        model.unlockWithPassword(password) { success, message -> result.complete(success to message) }
        runCurrent()
        // Room runs on IO; await its callback instead of advancing the repeating inactivity timer.
        return result.await()
    }

    @Test
    fun `stage and commit removes journal and changes current credentials`() {
        val oldKey = preferences.setupMasterPassword(oldPassword.toCharArray())
        val prepared = preferences.prepareMasterPasswordChange(newPassword.toCharArray())
        assertTrue(preferences.stagePendingMasterPasswordChange(prepared))
        assertTrue(preferences.hasPendingMasterPasswordChange())
        assertTrue(preferences.verifyKey(oldKey))
        assertNotNull(preferences.verifyPendingAndDeriveKey(newPassword.toCharArray()))
        assertTrue(preferences.commitMasterPasswordChange(prepared))
        assertFalse(preferences.hasPendingMasterPasswordChange())
        assertNull(preferences.verifyPendingAndDeriveKey(newPassword.toCharArray()))
        assertNull(preferences.verifyAndDeriveKey(oldPassword.toCharArray()))
        assertNotNull(preferences.verifyAndDeriveKey(newPassword.toCharArray()))
        assertTrue(preferences.verifyKey(prepared.secretKey))
        assertFalse(preferences.verifyKey(oldKey))
    }

    @Test
    fun `promotion moves pending credentials to current and wipe clears journal`() {
        preferences.setupMasterPassword(oldPassword.toCharArray())
        val prepared = preferences.prepareMasterPasswordChange(newPassword.toCharArray())
        assertTrue(preferences.stagePendingMasterPasswordChange(prepared))
        assertTrue(preferences.promotePendingMasterPasswordChange())
        assertFalse(preferences.hasPendingMasterPasswordChange())
        assertNull(preferences.verifyPendingAndDeriveKey(newPassword.toCharArray()))
        assertNull(preferences.verifyAndDeriveKey(oldPassword.toCharArray()))
        assertNotNull(preferences.verifyAndDeriveKey(newPassword.toCharArray()))
        assertFalse(preferences.promotePendingMasterPasswordChange())
        assertTrue(preferences.stagePendingMasterPasswordChange(prepared))
        preferences.wipeAll()
        assertFalse(preferences.hasPendingMasterPasswordChange())
        assertFalse(preferences.verifyKey(prepared.secretKey))
    }

    @Test
    fun `crash after database commit refuses old password and recovers with new password`() = runTest(dispatcher) {
        val id = seedVault()
        val oldKey = SessionManager.getKey()
        val prepared = preferences.prepareMasterPasswordChange(newPassword.toCharArray())
        assertTrue(preferences.stagePendingMasterPasswordChange(prepared))
        repository.reEncryptAll(oldKey, prepared.secretKey)
        SessionManager.lock()
        val model = VaultViewModel(application, preferences, dispatcher)

        val oldResult = unlock(model, oldPassword)
        assertFalse(oldResult.first)
        assertEquals("A master password change was interrupted. Unlock with your NEW master password to finish it.", oldResult.second)
        assertFalse(SessionManager.hasKey())
        assertEquals(0, preferences.getFailedUnlockAttempts())
        assertTrue(preferences.hasPendingMasterPasswordChange())

        assertTrue(unlock(model, newPassword).first)
        assertTrue(model.uiState.value.isUnlocked)
        assertFalse(preferences.hasPendingMasterPasswordChange())
        assertEquals(0, preferences.getFailedUnlockAttempts())
        assertEquals("Private account", repository.getEntryById(id)?.title)
        assertEquals("secret", repository.getEntryById(id)?.password)
        model.lockVault()
        runCurrent()
    }

    @Test
    fun `interrupted change to the same password text recovers with that password`() = runTest(dispatcher) {
        val id = seedVault()
        val oldKey = SessionManager.getKey()
        val prepared = preferences.prepareMasterPasswordChange(oldPassword.toCharArray())
        assertTrue(preferences.stagePendingMasterPasswordChange(prepared))
        repository.reEncryptAll(oldKey, prepared.secretKey)
        SessionManager.lock()
        val model = VaultViewModel(application, preferences, dispatcher)

        assertTrue(unlock(model, oldPassword).first)
        assertFalse(preferences.hasPendingMasterPasswordChange())
        assertEquals("secret", repository.getEntryById(id)?.password)
        model.lockVault()
        runCurrent()
    }

    @Test
    fun `crash before database commit refuses new password then old password decrypts`() = runTest(dispatcher) {
        val id = seedVault()
        val prepared = preferences.prepareMasterPasswordChange(newPassword.toCharArray())
        assertTrue(preferences.stagePendingMasterPasswordChange(prepared))
        SessionManager.lock()
        val model = VaultViewModel(application, preferences, dispatcher)

        val newResult = unlock(model, newPassword)
        assertFalse(newResult.first)
        assertEquals("The master password change did not complete. Unlock with your PREVIOUS master password.", newResult.second)
        assertFalse(SessionManager.hasKey())
        // The journal is dropped only once the previous password proves it still decrypts the vault.
        assertTrue(preferences.hasPendingMasterPasswordChange())
        assertEquals(0, preferences.getFailedUnlockAttempts())
        assertTrue(unlock(model, oldPassword).first)
        assertFalse(preferences.hasPendingMasterPasswordChange())
        assertEquals("secret", repository.getEntryById(id)?.password)
        model.lockVault()
        runCurrent()
    }

    @Test
    fun `damaged first row does not strand an interrupted change`() = runTest(dispatcher) {
        seedVault()
        repository.saveEntry(VaultEntry(title = "Bank", username = "bob", password = "second-secret"))
        val prepared = preferences.prepareMasterPasswordChange(newPassword.toCharArray())
        assertTrue(preferences.stagePendingMasterPasswordChange(prepared))
        repository.reEncryptAll(SessionManager.getKey(), prepared.secretKey)
        SessionManager.lock()
        // Favourites sort first, so the damaged row is the first one any probe reads.
        val dao = database.vaultDao()
        val damaged = dao.getAllEntriesSync().last()
        dao.updateEntry(damaged.copy(isFavorite = true, encryptedPassword = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
        assertEquals(damaged.id, dao.getAllEntriesSync().first().id)
        val model = VaultViewModel(application, preferences, dispatcher)

        assertFalse(unlock(model, oldPassword).first)
        assertTrue(preferences.hasPendingMasterPasswordChange())
        assertTrue(unlock(model, newPassword).first)
        assertFalse(preferences.hasPendingMasterPasswordChange())
        assertNotNull(preferences.verifyAndDeriveKey(newPassword.toCharArray()))
        val intact = dao.getAllEntriesSync().first { it.id != damaged.id }
        assertTrue(repository.getEntryById(intact.id)?.password in setOf("secret", "second-secret"))
        model.lockVault()
        runCurrent()
    }

    @Test
    fun `current password clears uncommitted journal and normal unlock still works`() = runTest(dispatcher) {
        val id = seedVault()
        val prepared = preferences.prepareMasterPasswordChange(newPassword.toCharArray())
        assertTrue(preferences.stagePendingMasterPasswordChange(prepared))
        SessionManager.lock()
        val model = VaultViewModel(application, preferences, dispatcher)
        assertTrue(unlock(model, oldPassword).first)
        assertFalse(preferences.hasPendingMasterPasswordChange())
        model.lockVault()
        assertTrue(unlock(model, oldPassword).first)
        assertEquals("secret", repository.getEntryById(id)?.password)
        model.lockVault()
        runCurrent()
    }

    @Test
    fun `empty vault has no key evidence and pending password can be promoted`() = runTest(dispatcher) {
        val oldKey = preferences.setupMasterPassword(oldPassword.toCharArray())
        assertNull(repository.keyDecryptsVault(oldKey))
        val prepared = preferences.prepareMasterPasswordChange(newPassword.toCharArray())
        assertTrue(preferences.stagePendingMasterPasswordChange(prepared))
        val model = VaultViewModel(application, preferences, dispatcher)
        assertTrue(unlock(model, newPassword).first)
        assertFalse(preferences.hasPendingMasterPasswordChange())
        model.lockVault()
        runCurrent()
    }
    @Test
    fun `failed staging never changes database or session key`() = runTest(dispatcher) {
        val id = seedVault()
        val oldKey = SessionManager.getKey()
        faultPrefs.failCommitNumber = faultPrefs.commits + 1
        val model = VaultViewModel(application, preferences, dispatcher)
        val result = CompletableDeferred<Pair<Boolean, String>>()
        model.changeMasterPassword(oldPassword, newPassword, newPassword) { ok, msg -> result.complete(ok to msg) }
        runCurrent()
        assertFalse(result.await().first)
        assertTrue(preferences.verifyKey(SessionManager.getKey()))
        assertEquals(true, repository.keyDecryptsVault(oldKey))
        assertEquals("secret", repository.getEntryById(id)?.password)
        model.lockVault()
        runCurrent()
    }

    @Test
    fun `failed final commit keeps new session key and recoverable journal`() = runTest(dispatcher) {
        val id = seedVault()
        // The first commit stages the journal; the second promotes credentials.
        faultPrefs.failCommitNumber = faultPrefs.commits + 2
        preferences.saveBiometricWrappedKey("old-wrapped-key")
        val model = VaultViewModel(application, preferences, dispatcher)
        val result = CompletableDeferred<Pair<Boolean, String>>()
        model.changeMasterPassword(oldPassword, newPassword, newPassword) { ok, msg -> result.complete(ok to msg) }
        runCurrent()
        val change = result.await()
        assertFalse(change.first)
        assertTrue(change.second.contains("NEW master password"))
        assertTrue(preferences.hasPendingMasterPasswordChange())
        assertFalse(preferences.isBiometricEnabled)
        assertFalse(model.uiState.value.isBiometricEnabled)
        assertEquals("secret", repository.getEntryById(id)?.password)
        assertFalse(preferences.verifyKey(SessionManager.getKey()))
        model.lockVault()
        assertTrue(unlock(model, newPassword).first)
        assertFalse(preferences.hasPendingMasterPasswordChange())
        model.lockVault()
        runCurrent()
    }

    @Test
    fun `failed promotion refuses unlock and preserves recovery journal`() = runTest(dispatcher) {
        seedVault()
        val prepared = preferences.prepareMasterPasswordChange(newPassword.toCharArray())
        assertTrue(preferences.stagePendingMasterPasswordChange(prepared))
        repository.reEncryptAll(SessionManager.getKey(), prepared.secretKey)
        SessionManager.lock()
        faultPrefs.failCommitNumber = faultPrefs.commits + 1
        val model = VaultViewModel(application, preferences, dispatcher)
        assertFalse(unlock(model, newPassword).first)
        assertFalse(SessionManager.hasKey())
        assertTrue(preferences.hasPendingMasterPasswordChange())
        assertEquals(0, preferences.getFailedUnlockAttempts())
        assertTrue(unlock(model, newPassword).first)
        model.lockVault()
        runCurrent()
    }

    @Test
    fun `pending rotation refuses biometric before prompting`() = runTest(dispatcher) {
        preferences.setupMasterPassword(oldPassword.toCharArray())
        preferences.saveBiometricWrappedKey("old-wrapped-key")
        assertTrue(preferences.stagePendingMasterPasswordChange(
            preferences.prepareMasterPasswordChange(newPassword.toCharArray())
        ))
        val model = VaultViewModel(application, preferences, dispatcher)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).get()
        var result: Pair<Boolean, String>? = null
        model.unlockWithBiometric(activity) { ok, message -> result = ok to message }
        assertEquals(false, result?.first)
        assertTrue(result?.second?.contains("master password") == true)
        assertFalse(SessionManager.hasKey())
        assertTrue(preferences.hasPendingMasterPasswordChange())
        runCurrent()
    }

    @Test
    fun `background lock during password derivation prevents delayed unlock`() = runTest(dispatcher) {
        seedVault()
        SessionManager.lock()
        val model = VaultViewModel(application, preferences, dispatcher)
        val result = CompletableDeferred<Boolean>()
        model.unlockWithPassword(oldPassword) { ok, _ -> result.complete(ok) }
        // Invalidate the request before its dispatcher resumes.
        model.onAppBackgrounded()
        runCurrent()
        assertFalse(result.await())
        assertFalse(SessionManager.hasKey())
        assertFalse(model.uiState.value.isUnlocked)
    }

    @Test
    fun `lock at rotation commit keeps vault locked and new password recoverable`() = runTest(dispatcher) {
        val id = seedVault()
        val finalCommit = faultPrefs.commits + 2
        faultPrefs.beforeCommit = { if (faultPrefs.commits == finalCommit) SessionManager.lock() }
        val model = VaultViewModel(application, preferences, dispatcher)
        val result = CompletableDeferred<Boolean>()
        model.changeMasterPassword(oldPassword, newPassword, newPassword) { ok, _ -> result.complete(ok) }
        runCurrent()
        assertTrue(result.await())
        assertFalse(SessionManager.hasKey())
        faultPrefs.beforeCommit = null
        assertTrue(unlock(model, newPassword).first)
        assertEquals("secret", repository.getEntryById(id)?.password)
        model.lockVault()
        runCurrent()
    }

    /** Inject a rejected write without changing stored credentials. */
    private class FailingCommitPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        var beforeCommit: (() -> Unit)? = null
        var commits = 0
        var failCommitNumber = -1

        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value)
                    return this
                }
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                    editor.putBoolean(key, value)
                    return this
                }
                override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                    editor.putLong(key, value)
                    return this
                }
                override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                    editor.putInt(key, value)
                    return this
                }
                override fun remove(key: String?): SharedPreferences.Editor {
                    editor.remove(key)
                    return this
                }
                override fun commit(): Boolean {
                    commits++
                    beforeCommit?.invoke()
                    return if (commits == failCommitNumber) false else editor.commit()
                }
            }
        }
    }

}
