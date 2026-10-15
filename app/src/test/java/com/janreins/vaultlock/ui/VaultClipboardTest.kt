package com.janreins.vaultlock.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
@LooperMode(LooperMode.Mode.PAUSED)
class VaultClipboardTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var application: Application
    private lateinit var clipboard: ClipboardManager
    private lateinit var viewModel: VaultViewModel
    private lateinit var store: ViewModelStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        application = ApplicationProvider.getApplicationContext()
        clipboard = application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val prefs = application.getSharedPreferences("clipboard_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        SessionManager.setKey(SecretKeySpec(ByteArray(32), "AES"))
        viewModel = VaultViewModel(application, SecurityPreferences(application, customPrefs = prefs), dispatcher)
        store = ViewModelStore().apply { put("vault", viewModel) }
    }

    @After
    fun tearDown() {
        store.clear()
        SessionManager.lock()
        Dispatchers.resetMain()
    }

    @Test
    fun `copy then lock clears sensitive clip`() = runTest {
        runCurrent()
        viewModel.copyToClipboard(application, "secret")
        assertEquals("secret", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertTrue(clipboard.primaryClipDescription?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)
        }
        viewModel.lockVault()
        assertNotEquals("secret", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `lock preserves clip replaced by another app even with identical text`() = runTest {
        runCurrent()
        viewModel.copyToClipboard(application, "secret")
        clipboard.setPrimaryClip(ClipData.newPlainText("Other app", "secret"))
        viewModel.lockVault()
        assertEquals("Other app", clipboard.primaryClipDescription?.label?.toString())
        assertEquals("secret", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `timeout preserves a replacement clip`() = runTest {
        runCurrent()
        viewModel.copyToClipboard(application, "secret")
        clipboard.setPrimaryClip(ClipData.newPlainText("Other app", "replacement"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals("replacement", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `background lock keeps clip for pasting and timeout still clears it`() = runTest {
        runCurrent()
        viewModel.copyToClipboard(application, "secret")
        viewModel.onAppBackgrounded()
        runCurrent()
        assertEquals("secret", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertNotEquals("secret", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `timeout clears latest copy after ten seconds`() = runTest {
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
}
