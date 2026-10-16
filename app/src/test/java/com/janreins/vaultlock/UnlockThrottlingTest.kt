package com.janreins.vaultlock

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.janreins.vaultlock.crypto.CryptoManager
import com.janreins.vaultlock.crypto.SessionManager
import com.janreins.vaultlock.data.SecurityPreferences
import com.janreins.vaultlock.ui.VaultViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnlockThrottlingTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var application: Application
    private lateinit var securityPreferences: SecurityPreferences

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        application = ApplicationProvider.getApplicationContext()
        val testPrefs = application.getSharedPreferences("test_throttling_prefs", Context.MODE_PRIVATE)
        testPrefs.edit().clear().commit()
        securityPreferences = SecurityPreferences(application, customPrefs = testPrefs)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        SessionManager.lock()
    }

    @Test
    fun `failed attempts calculate exponential backoff delay capped at 60 seconds`() {
        assertEquals(1L, securityPreferences.calculateBackoffDelaySeconds(1))
        assertEquals(2L, securityPreferences.calculateBackoffDelaySeconds(2))
        assertEquals(4L, securityPreferences.calculateBackoffDelaySeconds(3))
        assertEquals(8L, securityPreferences.calculateBackoffDelaySeconds(4))
        assertEquals(16L, securityPreferences.calculateBackoffDelaySeconds(5))
        assertEquals(32L, securityPreferences.calculateBackoffDelaySeconds(6))
        assertEquals(60L, securityPreferences.calculateBackoffDelaySeconds(7))
        assertEquals(60L, securityPreferences.calculateBackoffDelaySeconds(10))
    }

    @Test
    fun `recordFailedUnlockAttempt increases attempts counter and sets lockout timestamp`() {
        assertEquals(0, securityPreferences.getFailedUnlockAttempts())

        val delay1 = securityPreferences.recordFailedUnlockAttempt()
        assertEquals(1L, delay1)
        assertEquals(1, securityPreferences.getFailedUnlockAttempts())
        assertTrue(securityPreferences.getLockoutRemainingMillis() > 0)

        val delay2 = securityPreferences.recordFailedUnlockAttempt()
        assertEquals(2L, delay2)
        assertEquals(2, securityPreferences.getFailedUnlockAttempts())
    }

    @Test
    fun `lockout runs on the uptime clock and restarts in full after a reboot`() {
        var uptime = 50_000L
        var boot = 7
        val prefs = application.getSharedPreferences("test_throttling_clock", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val clocked = SecurityPreferences(application, prefs, elapsedRealtime = { uptime }, bootCount = { boot })

        clocked.recordFailedUnlockAttempt()
        assertEquals(2L, clocked.recordFailedUnlockAttempt())
        assertEquals(2_000L, clocked.getLockoutRemainingMillis())
        uptime += 500
        assertEquals(1_500L, clocked.getLockoutRemainingMillis())

        // A restart: new boot count, uptime starts again near zero. The whole delay applies again.
        boot = 8
        uptime = 1_000L
        assertEquals(2_000L, clocked.getLockoutRemainingMillis())
        uptime += 2_000
        assertEquals(0L, clocked.getLockoutRemainingMillis())

        // Unknown boot count: uptime going backwards still counts as a restart.
        clocked.recordFailedUnlockAttempt()
        boot = -1
        uptime -= 500
        assertEquals(4_000L, clocked.getLockoutRemainingMillis())
    }

    @Test
    fun `resetFailedUnlockAttempts clears failure counter and lockout timestamp`() {
        securityPreferences.recordFailedUnlockAttempt()
        securityPreferences.recordFailedUnlockAttempt()
        assertTrue(securityPreferences.getFailedUnlockAttempts() > 0)

        securityPreferences.resetFailedUnlockAttempts()
        assertEquals(0, securityPreferences.getFailedUnlockAttempts())
        assertEquals(0L, securityPreferences.getLockoutRemainingMillis())
    }

    @Test
    fun `viewModel unlockWithPassword throttles failed attempts and resets on success`() = runTest {
        val masterPass = "ValidMasterPass123!"
        securityPreferences.setupMasterPassword(masterPass.toCharArray())
        SessionManager.lock()

        val viewModel = VaultViewModel(application, securityPreferences, testDispatcher)

        // 1. Attempt unlock with incorrect password
        var unlockSuccess = false
        var resultMessage = ""
        viewModel.unlockWithPassword("WrongPass123!") { success, msg ->
            unlockSuccess = success
            resultMessage = msg
        }
        runCurrent()

        assertFalse(unlockSuccess)
        assertTrue(resultMessage.contains("Try again in 1 second(s)"))
        assertTrue(viewModel.uiState.value.lockoutRemainingSeconds > 0)

        // 2. Attempt unlock during active lockout -> should be blocked
        var blockedSuccess = true
        var blockedMessage = ""
        viewModel.unlockWithPassword(masterPass) { success, msg ->
            blockedSuccess = success
            blockedMessage = msg
        }
        runCurrent()

        assertFalse(blockedSuccess)
        assertTrue(blockedMessage.contains("Too many failed attempts"))

        // 3. Clear lockout to simulate delay elapsed
        securityPreferences.resetFailedUnlockAttempts()

        // 4. Attempt unlock with valid password -> should succeed and reset counter
        var validSuccess = false
        viewModel.unlockWithPassword(masterPass) { success, _ ->
            validSuccess = success
        }
        runCurrent()

        assertTrue(validSuccess)
        assertTrue(viewModel.uiState.value.isUnlocked)
        assertEquals(0, securityPreferences.getFailedUnlockAttempts())
        assertEquals(0L, viewModel.uiState.value.lockoutRemainingSeconds)
    }

    @Test
    fun `SessionManager lock clears key and resets unlock state`() {
        val salt = CryptoManager.generateSalt()
        val derivedKey = CryptoManager.deriveKey("TestPass123!".toCharArray(), salt)

        SessionManager.setKey(derivedKey)
        assertTrue(SessionManager.isUnlocked.value)
        assertTrue(SessionManager.hasKey())
        assertNotNull(SessionManager.getKey())

        SessionManager.lock()
        assertFalse(SessionManager.isUnlocked.value)
        assertFalse(SessionManager.hasKey())
    }
}
