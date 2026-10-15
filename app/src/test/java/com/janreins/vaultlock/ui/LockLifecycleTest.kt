package com.janreins.vaultlock.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.janreins.vaultlock.crypto.SessionManager
import com.janreins.vaultlock.data.SecurityPreferences
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockLifecycleTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var viewModel: VaultViewModel
    private lateinit var store: ViewModelStore
    private var written: ByteArray? = null
    private var writtenWhileLocked = false

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val prefs = application.getSharedPreferences("lock_lifecycle_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        SessionManager.setKey(SecretKeySpec(ByteArray(32) { 1 }, "AES"))
        val documents = object : BackupDocumentStore {
            override fun write(uri: Uri, bytes: ByteArray) {
                writtenWhileLocked = !SessionManager.hasKey()
                written = bytes.copyOf()
            }
            override fun delete(uri: Uri) = true
            override fun read(uri: Uri): ByteArray = error("No import expected")
        }
        viewModel = VaultViewModel(application, SecurityPreferences(application, customPrefs = prefs),
            dispatcher, backupDocumentStore = documents)
        store = ViewModelStore().apply { put("vault", viewModel) }
    }

    /** Ends each test with the ViewModel cleared so no auto-lock/expiry loop keeps the test scheduler busy. */
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
    fun `generator has a fresh password after lock and unlock`() = vmTest {
        runCurrent()
        assertTrue(viewModel.uiState.value.currentGeneratedPassword.isNotEmpty())
        viewModel.lockVault()
        runCurrent()
        assertEquals("", viewModel.uiState.value.currentGeneratedPassword)
        SessionManager.setKey(SecretKeySpec(ByteArray(32) { 1 }, "AES"))
        runCurrent()
        assertTrue(viewModel.uiState.value.isUnlocked)
        assertTrue(viewModel.uiState.value.currentGeneratedPassword.isNotEmpty())
    }

    @Test
    fun `normal stop always locks for timeout 120`() = vmTest {
        runCurrent()
        viewModel.setAutoLockDuration(120)
        viewModel.onActivityStopped(false)
        assertFalse(viewModel.uiState.value.isUnlocked)
        assertFalse(SessionManager.hasKey())
    }

    @Test
    fun `normal stop always locks for timeout 0`() = vmTest {
        runCurrent()
        viewModel.setAutoLockDuration(0)
        viewModel.onActivityStopped(false)
        assertFalse(viewModel.uiState.value.isUnlocked)
        assertFalse(SessionManager.hasKey())
    }

    @Test
    fun `stop during an open export picker locks and completion writes ciphertext while locked`() = vmTest {
        runCurrent()
        viewModel.exportBackup("BackupPassword123!".toCharArray())
        awaitState { viewModel.uiState.value.exportReadyFileName != null }
        val pending = VaultViewModel::class.java.getDeclaredField("pendingExport")
            .apply { isAccessible = true }.get(viewModel) as ByteArray
        val ciphertext = pending.copyOf()
        // The UI consumes the filename immediately before opening CreateDocument.
        viewModel.consumeExportReadyFileName()
        viewModel.onActivityStopped(false)
        assertFalse(viewModel.uiState.value.isUnlocked)
        assertFalse(SessionManager.hasKey())
        viewModel.completeExport(Uri.parse("content://lock-lifecycle/backup.vault"))
        awaitState { !viewModel.uiState.value.isBackupBusy }
        assertArrayEquals(ciphertext, written)
        assertTrue(writtenWhileLocked)
        assertTrue(pending.all { it == 0.toByte() })
        assertFalse(viewModel.uiState.value.isUnlocked)
        assertFalse(SessionManager.hasKey())
    }

    private suspend fun TestScope.awaitState(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 40_000_000_000L
        while (true) {
            runCurrent()
            if (condition()) return
            check(System.nanoTime() < deadline) { "Timed out waiting for backup IO" }
            withContext(Dispatchers.IO) { delay(5) }
        }
    }

    @Test
    fun `rotate then foreground then Home locks normally`() = vmTest {
        runCurrent()
        viewModel.onActivityStopped(true)
        runCurrent()
        advanceTimeBy(1_000)
        assertTrue(viewModel.uiState.value.isUnlocked)
        viewModel.onAppForegrounded()
        viewModel.onActivityStopped(false)
        assertFalse(viewModel.uiState.value.isUnlocked)
        assertFalse(SessionManager.hasKey())
    }

    @Test
    fun `configuration change without foreground locks at five seconds`() = vmTest {
        runCurrent()
        viewModel.onActivityStopped(true)
        runCurrent()
        advanceTimeBy(VaultViewModel.CONFIG_CHANGE_GRACE_MS - 1)
        assertTrue(viewModel.uiState.value.isUnlocked)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(viewModel.uiState.value.isUnlocked)
        assertFalse(SessionManager.hasKey())
    }

    @Test
    fun `foreground cancels configuration guard`() = vmTest {
        runCurrent()
        viewModel.onActivityStopped(true)
        runCurrent()
        viewModel.onAppForegrounded()
        advanceTimeBy(VaultViewModel.CONFIG_CHANGE_GRACE_MS)
        runCurrent()
        assertTrue(viewModel.uiState.value.isUnlocked)
    }

    @Test
    fun `draft survives recreation and all drafts clear on lock`() = vmTest {
        runCurrent()
        val draft = viewModel.entryDraft("back-stack-id")
        draft.title = "Edited title"
        draft.username = "user"
        draft.password = "secret"
        draft.url = "offline.example"
        draft.notes = "unsaved notes"
        draft.totpSecret = "JBSWY3DPEHPK3PXP"
        draft.category = "Secure Note"
        draft.isFavorite = true
        draft.createdAt = 123L
        draft.loadedEntryId = 7L
        val another = viewModel.entryDraft("another-entry").apply { password = "another secret" }

        viewModel.onActivityStopped(true)
        runCurrent()
        viewModel.onAppForegrounded()
        assertSame(draft, viewModel.entryDraft("back-stack-id"))
        assertEquals("secret", draft.password)
        assertEquals("unsaved notes", draft.notes)
        assertEquals(7L, checkNotNull(draft.loadedEntryId))
        assertEquals(123L, draft.createdAt)
        assertTrue(draft.isFavorite)

        viewModel.lockVault()
        assertEquals("", draft.title)
        assertEquals("", draft.username)
        assertEquals("", draft.password)
        assertEquals("", draft.url)
        assertEquals("", draft.notes)
        assertEquals("", draft.totpSecret)
        assertNull(draft.loadedEntryId)
        assertEquals("", another.password)
        assertNotSame(draft, viewModel.entryDraft("back-stack-id"))
    }

    @Test
    fun `explicit draft discard removes and clears only that draft`() = vmTest {
        runCurrent()
        val discarded = viewModel.entryDraft("discard").apply { password = "secret" }
        val retained = viewModel.entryDraft("keep").apply { title = "keep" }
        viewModel.discardEntryDraft("discard")
        assertEquals("", discarded.password)
        assertNotSame(discarded, viewModel.entryDraft("discard"))
        assertSame(retained, viewModel.entryDraft("keep"))
    }

    @Test
    fun `session lock collector clears editor drafts`() = vmTest {
        runCurrent()
        val draft = viewModel.entryDraft("editor").apply { password = "secret" }
        SessionManager.lock()
        runCurrent()
        assertEquals("", draft.password)
        assertNotSame(draft, viewModel.entryDraft("editor"))
        assertFalse(viewModel.uiState.value.isUnlocked)
    }
}
