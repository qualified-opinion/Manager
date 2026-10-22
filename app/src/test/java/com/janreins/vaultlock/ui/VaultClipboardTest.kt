package com.janreins.vaultlock.ui

import android.app.AlarmManager
import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.janreins.vaultlock.crypto.SessionManager
import com.janreins.vaultlock.data.SecurityPreferences
import java.time.Duration
import javax.crypto.spec.SecretKeySpec
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowClipboardManager
import org.robolectric.shadows.ShadowSystemClock

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], shadows = [VaultClipboardTest.OwnershipClipboardShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class VaultClipboardTest {
    /** Model Android's denied background ownership read without discarding the actual clip. */
    @Implements(ClipboardManager::class)
    class OwnershipClipboardShadow : ShadowClipboardManager() {
        var ownershipHidden = false
        var clearDenied = false

        @Implementation
        override fun getPrimaryClipDescription(): ClipDescription? =
            if (ownershipHidden) null else super.getPrimaryClipDescription()

        @Implementation(minSdk = Build.VERSION_CODES.P)
        override fun clearPrimaryClip() {
            check(!clearDenied) { "Clipboard access denied" }
            super.clearPrimaryClip()
        }

        @Implementation
        override fun setPrimaryClip(clip: ClipData?) {
            check(!clearDenied) { "Clipboard access denied" }
            super.setPrimaryClip(clip)
        }
    }

    private val dispatcher = StandardTestDispatcher()
    private lateinit var application: Application
    private lateinit var clipboard: ClipboardManager
    private lateinit var securityPreferences: SecurityPreferences
    private lateinit var viewModel: VaultViewModel
    private lateinit var store: ViewModelStore
    private val wipePrefs get() = application.getSharedPreferences("clipboard_wipe", Context.MODE_PRIVATE)
    private val clipboardShadow get() = Shadow.extract<OwnershipClipboardShadow>(clipboard)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        application = ApplicationProvider.getApplicationContext()
        clipboard = application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        wipePrefs.edit().clear().commit()
        ShadowAlarmManager.setAutoSchedule(false)
        val prefs = application.getSharedPreferences("clipboard_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        securityPreferences = SecurityPreferences(application, customPrefs = prefs)
        SessionManager.setKey(SecretKeySpec(ByteArray(32), "AES"))
        viewModel = VaultViewModel(application, securityPreferences, dispatcher)
        store = ViewModelStore().apply { put("vault", viewModel) }
    }

    /** Cancel the real-clock inactivity loop before runTest tries to drain virtual time. */
    private fun vmTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            store.clear()
            SessionManager.lock()
            runCurrent()
        }
    }

    @After
    fun tearDown() {
        store.clear()
        SessionManager.lock()
        Dispatchers.resetMain()
    }

    @Test
    fun `copy then lock clears sensitive clip`() = vmTest {
        runCurrent()
        viewModel.copyToClipboard(application, "secret")
        assertEquals("secret", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        // Set on every version, not just API 33+ where the constant was added.
        assertTrue(clipboard.primaryClipDescription?.extras?.getBoolean(VaultViewModel.EXTRA_IS_SENSITIVE) == true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertEquals(ClipDescription.EXTRA_IS_SENSITIVE, VaultViewModel.EXTRA_IS_SENSITIVE)
        }
        viewModel.lockVault()
        assertNotEquals("secret", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `lock preserves clip replaced by another app even with identical text`() = vmTest {
        runCurrent()
        viewModel.copyToClipboard(application, "secret")
        clipboard.setPrimaryClip(ClipData.newPlainText("Other app", "secret"))
        viewModel.lockVault()
        assertEquals("Other app", clipboard.primaryClipDescription?.label?.toString())
        assertEquals("secret", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `timeout preserves a replacement clip`() = vmTest {
        runCurrent()
        viewModel.copyToClipboard(application, "secret")
        clipboard.setPrimaryClip(ClipData.newPlainText("Other app", "replacement"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals("replacement", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `background lock keeps clip for pasting and timeout still clears it`() = vmTest {
        runCurrent()
        viewModel.copyToClipboard(application, "secret")
        viewModel.onAppBackgrounded()
        runCurrent()
        assertEquals("secret", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertNotEquals("secret", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `timeout clears latest copy after ten seconds`() = vmTest {
        runCurrent()
        viewModel.copyToClipboard(application, "first")
        val firstMarker = clipboard.primaryClipDescription?.label?.toString()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        viewModel.copyToClipboard(application, "second")
        assertNotEquals(firstMarker, clipboard.primaryClipDescription?.label?.toString())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals("second", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertNotEquals("second", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    private val alarms get() = shadowOf(application.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    /** The intent the system would deliver to [ClipboardClearReceiver] when the alarm fires. */
    private fun scheduledWipeIntent(): Intent {
        val alarm = alarms.peekNextScheduledAlarm()
        assertNotNull(alarm)
        return shadowOf(alarm!!.operation).savedIntent
    }

    @Test
    fun `alarm wipes the copy after the app process is gone`() = vmTest {
        runCurrent()
        viewModel.copyToClipboard(application, "secret")
        viewModel.onAppBackgrounded()
        runCurrent()
        val intent = scheduledWipeIntent()
        // Process death: the in-process timer never runs; only the system alarm is left.
        ClipboardClearReceiver().onReceive(application, intent)
        assertNotEquals("secret", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `alarm keeps a newer clip from another app`() = vmTest {
        runCurrent()
        viewModel.copyToClipboard(application, "secret")
        val intent = scheduledWipeIntent()
        clipboard.setPrimaryClip(ClipData.newPlainText("Other app", "replacement"))
        assertFalse(ClipboardWipe.onAlarm(application, intent))
        assertEquals("replacement", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `in-process clear cancels the alarm`() = vmTest {
        runCurrent()
        viewModel.copyToClipboard(application, "secret")
        assertNotNull(alarms.peekNextScheduledAlarm())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(null, alarms.peekNextScheduledAlarm())
    }

    @Test
    fun `background lock keeps the alarm for the wipe`() = vmTest {
        runCurrent()
        viewModel.copyToClipboard(application, "secret")
        viewModel.onAppBackgrounded()
        runCurrent()
        assertNotNull(alarms.peekNextScheduledAlarm())
    }

    @Test
    fun `clearing the view model keeps the clip and the alarm wipes it later`() = vmTest {
        runCurrent()
        viewModel.copyToClipboard(application, "secret")
        // Back on Android 8-11 finishes the activity and clears the ViewModel.
        store.clear()
        assertEquals("secret", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        val intent = scheduledWipeIntent()
        // The in-process timer is gone, so only the alarm can clear the copy.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals("secret", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertTrue(ClipboardWipe.onAlarm(application, intent))
        assertNotEquals("secret", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    /** Advance the device clock without running the Handler that backs up the alarm. */
    private fun deliverLateHiddenAlarm() {
        val intent = scheduledWipeIntent()
        clipboardShadow.ownershipHidden = true
        ShadowSystemClock.advanceBy(Duration.ofMillis(
            VaultViewModel.CLIPBOARD_CLEAR_MS + ClipboardWipe.BLIND_CLEAR_LIMIT_MS + 1
        ))
        assertFalse(ClipboardWipe.onAlarm(application, intent))
        clipboardShadow.ownershipHidden = false
    }

    @Test
    fun `late hidden alarm is recovered on next foreground`() = vmTest {
        runCurrent()
        viewModel.copyToClipboard(application, "secret")
        viewModel.onAppBackgrounded()
        deliverLateHiddenAlarm()
        assertEquals("secret", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        viewModel.onAppForegrounded()
        assertNotEquals("secret", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertTrue(wipePrefs.all.isEmpty())
        assertEquals(null, alarms.peekNextScheduledAlarm())
    }

    @Test
    fun `late hidden alarm recovery keeps another apps clip and retires record`() = vmTest {
        runCurrent()
        viewModel.copyToClipboard(application, "secret")
        viewModel.onAppBackgrounded()
        deliverLateHiddenAlarm()
        // Main already preserves replacement text; it fails this required pending-record lifecycle.
        assertFalse(wipePrefs.all.isEmpty())
        clipboard.setPrimaryClip(ClipData.newPlainText("Other app", "replacement"))
        viewModel.onAppForegrounded()
        assertEquals("replacement", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertTrue(wipePrefs.all.isEmpty())
        assertEquals(null, alarms.peekNextScheduledAlarm())
    }

    @Test
    fun `new view model lock preserves previous instances pending wipe`() = vmTest {
        runCurrent()
        viewModel.copyToClipboard(application, "secret")
        val record = wipePrefs.all.toMap()
        store.clear()
        viewModel = VaultViewModel(application, securityPreferences, dispatcher)
        store = ViewModelStore().apply { put("vault", viewModel) }
        runCurrent()
        viewModel.lockVault()
        // Peek only: removing the alarm here would hide accidental cancellation by lockVault.
        val intent = scheduledWipeIntent()
        assertEquals(record, wipePrefs.all)
        assertEquals("secret", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertTrue(ClipboardWipe.onAlarm(application, intent))
        assertNotEquals("secret", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertTrue(wipePrefs.all.isEmpty())
    }

    @Test
    fun `newer copy replaces metadata and stale alarm cannot retire it`() = vmTest {
        runCurrent()
        viewModel.copyToClipboard(application, "first secret")
        val oldIntent = Intent(scheduledWipeIntent())
        val oldMarker = clipboard.primaryClipDescription?.label?.toString()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        viewModel.copyToClipboard(application, "second secret")
        val intent = scheduledWipeIntent()
        val marker = clipboard.primaryClipDescription?.label?.toString()
        assertNotEquals(oldMarker, marker)
        clipboardShadow.ownershipHidden = true
        assertFalse(ClipboardWipe.onAlarm(application, oldIntent))
        clipboardShadow.ownershipHidden = false
        // Exact contents also guard against accidentally persisting any copied text.
        val expectedRecord = mapOf("marker" to marker, "deadline" to intent.getLongExtra("deadline", -1))
        assertEquals(expectedRecord, wipePrefs.all)
        assertEquals("second secret", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertTrue(ClipboardWipe.onAlarm(application, intent))
        assertTrue(wipePrefs.all.isEmpty())
    }

    @Test
    fun `creating a view model recovers an expired wipe after Back`() = vmTest {
        runCurrent()
        viewModel.copyToClipboard(application, "secret")
        store.clear()
        deliverLateHiddenAlarm()
        viewModel = VaultViewModel(application, securityPreferences, dispatcher)
        store = ViewModelStore().apply { put("vault", viewModel) }
        assertNotEquals("secret", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertTrue(wipePrefs.all.isEmpty())
        assertEquals(null, alarms.peekNextScheduledAlarm())
    }

    @Test
    fun `failed in-process clear keeps the alarm and pending record for retry`() = vmTest {
        runCurrent()
        viewModel.copyToClipboard(application, "secret")
        val record = wipePrefs.all.toMap()
        clipboardShadow.clearDenied = true
        viewModel.lockVault()
        val intent = scheduledWipeIntent()
        assertEquals(record, wipePrefs.all)
        clipboardShadow.clearDenied = false
        assertTrue(ClipboardWipe.onAlarm(application, intent))
        assertNotEquals("secret", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertTrue(wipePrefs.all.isEmpty())
    }
}
