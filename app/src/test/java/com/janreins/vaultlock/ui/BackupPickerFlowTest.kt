package com.janreins.vaultlock.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.janreins.vaultlock.crypto.SessionManager
import com.janreins.vaultlock.data.SecurityPreferences
import com.janreins.vaultlock.data.VaultDatabase
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupPickerFlowTest {
    private val dispatcher = StandardTestDispatcher()
    private val uri = Uri.parse("content://backup-test/backup.vault")
    private lateinit var application: Application
    private lateinit var viewModel: VaultViewModel
    private lateinit var store: ViewModelStore
    private lateinit var documents: FakeDocumentStore
    private var elapsed = 1_000L

    private class FakeDocumentStore : BackupDocumentStore {
        var input = ByteArray(40) { (it + 1).toByte() }
        var written: ByteArray? = null
        var writtenUri: Uri? = null
        var deletedUri: Uri? = null
        var failWrite = false
        var failDelete = false
        var deleteResult = true

        override fun write(uri: Uri, bytes: ByteArray) {
            if (failWrite) throw IOException("Cannot write")
            written = bytes.copyOf()
            writtenUri = uri
        }

        override fun delete(uri: Uri): Boolean {
            deletedUri = uri
            if (failDelete) throw IOException("Cannot delete")
            return deleteResult
        }

        override fun read(uri: Uri): ByteArray = ByteArrayInputStream(input).use {
            BackupFileIO.readBounded(it)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        application = ApplicationProvider.getApplicationContext()
        val prefs = application.getSharedPreferences("backup_picker_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        SessionManager.setKey(SecretKeySpec(ByteArray(32) { 1 }, "AES"))
        documents = FakeDocumentStore()
        viewModel = VaultViewModel(application, SecurityPreferences(application, customPrefs = prefs),
            dispatcher, elapsedRealtime = { elapsed }, backupDocumentStore = documents, ioDispatcher = dispatcher)
        store = ViewModelStore().apply { put("vault", viewModel) }
    }

    /**
     * Ends each test with the ViewModel cleared so no auto-lock/expiry loop keeps the test scheduler busy,
     * then waits for its cancelled jobs to finish. Backup document IO runs on the test dispatcher, but
     * database work still runs on real threads and resumes on Main; if that outlives the test it touches
     * Dispatchers.Main while the next test's setMain/resetMain is swapping it, which fails unrelated tests.
     */
    private fun vmTest(body: suspend TestScope.() -> Unit) = runTest {
        val vmJob = viewModel.viewModelScope.coroutineContext.job
        try {
            body()
        } finally {
            store.clear()
            SessionManager.lock()
            awaitState { vmJob.isCompleted }
        }
    }

    @After
    fun tearDown() {
        store.clear()
        SessionManager.lock()
        Dispatchers.resetMain()
    }

    // Inspect ownership/zeroing without adding a production API that exposes backup bytes.
    private fun pendingBytes(name: String): ByteArray? =
        VaultViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
            .get(viewModel) as ByteArray?

    private fun seedExport(bytes: ByteArray) {
        VaultViewModel::class.java.getDeclaredField("pendingExport").apply { isAccessible = true }
            .set(viewModel, bytes)
    }

    /**
     * Pump Main while database work on real threads finishes; never advance the repeating inactivity timer.
     * The deadline is generous because shared CI runners can be slow and busy with PBKDF2 work from
     * earlier tests; it only costs time when a test is genuinely stuck.
     */
    private suspend fun TestScope.awaitState(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 60_000_000_000L
        while (true) {
            runCurrent()
            if (condition()) return
            check(System.nanoTime() < deadline) { "Timed out waiting for backup IO" }
            withContext(Dispatchers.IO) { delay(1) }
        }
    }

    @Test
    fun `export prepared in VM survives lock and writes exact ciphertext then zeroes`() = vmTest {
        runCurrent()
        val password = "SeparateBackupPassword!".toCharArray()
        viewModel.exportBackup(password)
        assertTrue(viewModel.uiState.value.isBackupBusy)
        awaitState { viewModel.uiState.value.exportReadyFileName != null }
        assertTrue(password.all { it == '\u0000' })
        val vmBytes = checkNotNull(pendingBytes("pendingExport"))
        val expectedCiphertext = vmBytes.copyOf()
        assertNotNull(viewModel.uiState.value.exportReadyFileName)
        viewModel.consumeExportReadyFileName()
        viewModel.lockVault()
        assertFalse(SessionManager.hasKey())
        assertArrayEquals(expectedCiphertext, pendingBytes("pendingExport"))

        viewModel.completeExport(uri)
        awaitState { !viewModel.uiState.value.isBackupBusy }
        assertEquals(uri, documents.writtenUri)
        assertArrayEquals(expectedCiphertext, documents.written)
        assertTrue(vmBytes.all { it == 0.toByte() })
        assertNull(pendingBytes("pendingExport"))
        assertEquals("Encrypted backup saved", viewModel.uiState.value.userMessage)
        assertFalse(viewModel.uiState.value.isUnlocked)
    }

    @Test
    fun `export cancellation zeroes pending bytes and clears busy state`() = vmTest {
        runCurrent()
        val bytes = byteArrayOf(1, 2, 3, 4)
        seedExport(bytes)
        viewModel.completeExport(null)
        assertTrue(bytes.all { it == 0.toByte() })
        assertNull(pendingBytes("pendingExport"))
        assertFalse(viewModel.uiState.value.isBackupBusy)
        assertNull(documents.written)
        viewModel.onActivityStopped(false)
        assertFalse(SessionManager.hasKey())
    }

    @Test
    fun `missing export data deletes empty document and reports lost data`() = vmTest {
        runCurrent()
        viewModel.lockVault()
        viewModel.completeExport(uri)
        awaitState { !viewModel.uiState.value.isBackupBusy }
        assertEquals(uri, documents.deletedUri)
        assertNull(documents.written)
        assertEquals("Backup was not saved: the encrypted data was lost. Unlock and export again.",
            viewModel.uiState.value.userMessage)
    }

    @Test
    fun `write failure deletes document reports error and zeroes bytes`() = vmTest {
        runCurrent()
        val bytes = byteArrayOf(1, 2, 3, 4)
        seedExport(bytes)
        documents.failWrite = true
        viewModel.completeExport(uri)
        awaitState { !viewModel.uiState.value.isBackupBusy }
        assertEquals(uri, documents.deletedUri)
        assertTrue(bytes.all { it == 0.toByte() })
        assertEquals("Backup could not be saved. Try another destination.", viewModel.uiState.value.userMessage)
    }

    @Test
    fun `missing export data reports empty file when deletion returns false or throws`() = vmTest {
        runCurrent()
        viewModel.lockVault()
        for (throws in listOf(false, true)) {
            documents.failDelete = throws
            documents.deleteResult = false
            viewModel.completeExport(uri)
            awaitState { !viewModel.uiState.value.isBackupBusy }
            assertEquals(uri, documents.deletedUri)
            assertNull(documents.written)
            assertEquals(
                "Backup was not saved: the encrypted data was lost. An empty file was left in the chosen folder; delete it, then unlock and export again.",
                viewModel.uiState.value.userMessage
            )
        }
    }

    @Test
    fun `write failure reports possible empty file when deletion returns false or throws`() = vmTest {
        runCurrent()
        documents.failWrite = true
        for (throws in listOf(false, true)) {
            val bytes = byteArrayOf(1, 2, 3, 4)
            seedExport(bytes)
            documents.failDelete = throws
            documents.deleteResult = false
            viewModel.completeExport(uri)
            awaitState { !viewModel.uiState.value.isBackupBusy }
            assertEquals(uri, documents.deletedUri)
            assertTrue(bytes.all { it == 0.toByte() })
            assertNull(pendingBytes("pendingExport"))
            assertEquals(
                "Backup could not be saved. An empty file may have been left in the chosen folder. Try another destination.",
                viewModel.uiState.value.userMessage
            )
        }
    }

    @Test
    fun `unlaunched export is discarded by explicit and observed session locks`() = vmTest {
        runCurrent()
        for (externalLock in listOf(false, true)) {
            SessionManager.setKey(SecretKeySpec(ByteArray(32) { 1 }, "AES"))
            runCurrent()
            viewModel.exportBackup("BackupPassword123!".toCharArray())
            awaitState { viewModel.uiState.value.exportReadyFileName != null }
            val bytes = checkNotNull(pendingBytes("pendingExport"))
            if (externalLock) {
                SessionManager.lock()
                runCurrent()
            } else {
                viewModel.lockVault()
            }
            assertTrue(bytes.all { it == 0.toByte() })
            assertNull(pendingBytes("pendingExport"))
            assertNull(viewModel.uiState.value.exportReadyFileName)
            assertFalse(viewModel.uiState.value.isBackupBusy)
            assertFalse(viewModel.uiState.value.isUnlocked)
        }
    }

    @Test
    fun `picker launch failure clears busy state and export bytes`() = vmTest {
        runCurrent()
        val bytes = byteArrayOf(1, 2, 3, 4)
        seedExport(bytes)
        viewModel.onBackupPickerLaunchFailed(export = true)
        assertTrue(bytes.all { it == 0.toByte() })
        assertNull(pendingBytes("pendingExport"))
        assertFalse(viewModel.uiState.value.isBackupBusy)
        viewModel.onActivityStopped(false)
        assertFalse(SessionManager.hasKey())
    }

    @Test
    fun `import result can finish reading after a background lock`() = vmTest {
        runCurrent()
        assertTrue(viewModel.beginImportPicker())
        viewModel.onImportFilePicked(uri)
        viewModel.onActivityStopped(false)
        assertFalse(SessionManager.hasKey())
        awaitState { viewModel.uiState.value.hasPendingImport }
        assertArrayEquals(documents.input, pendingBytes("pendingImport"))
    }

    @Test
    fun `null import result clears busy state`() = vmTest {
        runCurrent()
        viewModel.beginImportPicker()
        viewModel.onImportFilePicked(null)
        assertFalse(viewModel.uiState.value.isBackupBusy)
        assertFalse(viewModel.uiState.value.hasPendingImport)
        viewModel.onActivityStopped(false)
        assertFalse(SessionManager.hasKey())
    }

    @Test
    fun `import picked while locked holds only encrypted data waits for unlock and discard zeroes`() = vmTest {
        runCurrent()
        val dao = VaultDatabase.getInstance(application).vaultDao()
        val countBefore = withContext(Dispatchers.IO) { dao.getCount() }
        viewModel.lockVault()
        viewModel.onImportFilePicked(uri)
        awaitState { viewModel.uiState.value.hasPendingImport }
        val vmBytes = checkNotNull(pendingBytes("pendingImport"))
        assertArrayEquals(documents.input, vmBytes)
        // runBlocking: suspending here would let the test scheduler skip ahead to the import expiry.
        assertEquals(countBefore, runBlocking(Dispatchers.IO) { dao.getCount() })
        assertNull(viewModel.uiState.value.userMessage)
        assertFalse(viewModel.uiState.value.isUnlocked && viewModel.uiState.value.hasPendingImport)

        // Confirmation while locked cannot reach the restore path either.
        val password = "backup password".toCharArray()
        viewModel.confirmPendingImport(password, legacy = false, skipDuplicates = true)
        assertTrue(password.all { it == '\u0000' })
        assertArrayEquals(documents.input, vmBytes)
        SessionManager.setKey(SecretKeySpec(ByteArray(32) { 1 }, "AES"))
        runCurrent()
        assertTrue(viewModel.uiState.value.isUnlocked && viewModel.uiState.value.hasPendingImport)
        viewModel.discardPendingImport()
        assertTrue(vmBytes.all { it == 0.toByte() })
        assertNull(pendingBytes("pendingImport"))
        assertFalse(viewModel.uiState.value.hasPendingImport)
        assertFalse(viewModel.uiState.value.isBackupBusy)
    }

    @Test
    fun `bounded import read failure reports validation error and clears busy`() = vmTest {
        runCurrent()
        documents.input = byteArrayOf(1, 2, 3)
        viewModel.onImportFilePicked(uri)
        awaitState { !viewModel.uiState.value.isBackupBusy }
        assertFalse(viewModel.uiState.value.hasPendingImport)
        assertNull(pendingBytes("pendingImport"))
        assertEquals("File is too short to be an encrypted backup.", viewModel.uiState.value.userMessage)
    }

    @Test
    fun `pending import expires at ten minutes while locked and zeroes ciphertext`() = vmTest {
        runCurrent()
        viewModel.lockVault()
        viewModel.onImportFilePicked(uri)
        awaitState { viewModel.uiState.value.hasPendingImport }
        val bytes = checkNotNull(pendingBytes("pendingImport"))
        advanceTimeBy(VaultViewModel.PENDING_IMPORT_TTL_MS - 1)
        runCurrent()
        assertTrue(viewModel.uiState.value.hasPendingImport)
        assertArrayEquals(documents.input, bytes)
        assertNull(viewModel.uiState.value.userMessage)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(bytes.all { it == 0.toByte() })
        assertNull(pendingBytes("pendingImport"))
        assertFalse(viewModel.uiState.value.hasPendingImport)
        assertFalse(viewModel.uiState.value.isBackupBusy)
        assertEquals("The selected backup expired. Pick it again to restore.",
            viewModel.uiState.value.userMessage)
        assertFalse(SessionManager.hasKey())
    }

    @Test
    fun `confirmation checks monotonic expiry even before the expiry job runs`() = vmTest {
        runCurrent()
        viewModel.onImportFilePicked(uri)
        awaitState { viewModel.uiState.value.hasPendingImport }
        val bytes = checkNotNull(pendingBytes("pendingImport"))
        val password = "backup password".toCharArray()
        // Advance only the injected clock, leaving the coroutine scheduler unchanged.
        elapsed += VaultViewModel.PENDING_IMPORT_TTL_MS
        assertTrue(viewModel.uiState.value.hasPendingImport)
        viewModel.confirmPendingImport(password, legacy = false, skipDuplicates = true)
        assertTrue(password.all { it == '\u0000' })
        assertTrue(bytes.all { it == 0.toByte() })
        assertNull(pendingBytes("pendingImport"))
        assertFalse(viewModel.uiState.value.hasPendingImport)
        assertFalse(viewModel.uiState.value.isBackupBusy)
        assertEquals("The selected backup expired. Pick it again to restore.",
            viewModel.uiState.value.userMessage)
        viewModel.consumeUserMessage()
        advanceTimeBy(VaultViewModel.PENDING_IMPORT_TTL_MS)
        runCurrent()
        assertNull(viewModel.uiState.value.userMessage)
    }

    @Test
    fun `replacement zeroes old import and starts a fresh expiry timer`() = vmTest {
        runCurrent()
        viewModel.lockVault()
        viewModel.onImportFilePicked(uri)
        awaitState { viewModel.uiState.value.hasPendingImport }
        val old = checkNotNull(pendingBytes("pendingImport"))
        advanceTimeBy(VaultViewModel.PENDING_IMPORT_TTL_MS / 2)
        elapsed += VaultViewModel.PENDING_IMPORT_TTL_MS / 2
        viewModel.onImportFilePicked(uri)
        awaitState { pendingBytes("pendingImport") !== old }
        val replacement = checkNotNull(pendingBytes("pendingImport"))
        assertTrue(old.all { it == 0.toByte() })
        advanceTimeBy(VaultViewModel.PENDING_IMPORT_TTL_MS / 2)
        runCurrent()
        assertTrue(viewModel.uiState.value.hasPendingImport)
        assertArrayEquals(documents.input, replacement)
        assertNull(viewModel.uiState.value.userMessage)
        advanceTimeBy(VaultViewModel.PENDING_IMPORT_TTL_MS / 2)
        runCurrent()
        assertTrue(replacement.all { it == 0.toByte() })
        assertFalse(viewModel.uiState.value.hasPendingImport)
        assertEquals("The selected backup expired. Pick it again to restore.",
            viewModel.uiState.value.userMessage)
    }

    @Test
    fun `discard cancels pending import expiry`() = vmTest {
        runCurrent()
        viewModel.onImportFilePicked(uri)
        awaitState { viewModel.uiState.value.hasPendingImport }
        val bytes = checkNotNull(pendingBytes("pendingImport"))
        viewModel.discardPendingImport()
        assertTrue(bytes.all { it == 0.toByte() })
        advanceTimeBy(VaultViewModel.PENDING_IMPORT_TTL_MS)
        runCurrent()
        assertNull(viewModel.uiState.value.userMessage)
        assertFalse(viewModel.uiState.value.hasPendingImport)
        assertFalse(viewModel.uiState.value.isBackupBusy)
    }

    @Test
    fun `confirmation after unlock uses pending import and clears it on restore failure`() = vmTest {
        runCurrent()
        viewModel.lockVault()
        viewModel.onImportFilePicked(uri)
        awaitState { viewModel.uiState.value.hasPendingImport }
        val imported = checkNotNull(pendingBytes("pendingImport"))
        SessionManager.setKey(SecretKeySpec(ByteArray(32) { 1 }, "AES"))
        runCurrent()
        val password = "backup password".toCharArray()
        viewModel.confirmPendingImport(password, legacy = false, skipDuplicates = true)
        awaitState { !viewModel.uiState.value.isBackupBusy }
        assertFalse(viewModel.uiState.value.hasPendingImport)
        assertNull(pendingBytes("pendingImport"))
        assertTrue(imported.all { it == 0.toByte() })
        assertTrue(password.all { it == '\u0000' })
        assertTrue(checkNotNull(viewModel.uiState.value.userMessage).startsWith("Restore failed:"))
        viewModel.consumeUserMessage()
        advanceTimeBy(VaultViewModel.PENDING_IMPORT_TTL_MS)
        runCurrent()
        assertNull(viewModel.uiState.value.userMessage)
    }

    @Test
    fun `wipe clears pending buffers and editor drafts`() = vmTest {
        runCurrent()
        val exported = byteArrayOf(1, 2, 3, 4)
        seedExport(exported)
        viewModel.onImportFilePicked(uri)
        awaitState { viewModel.uiState.value.hasPendingImport }
        val imported = checkNotNull(pendingBytes("pendingImport"))
        val draft = viewModel.entryDraft("editor").apply { password = "unsaved secret" }
        var completed = false
        viewModel.wipeAllData { completed = true }
        assertTrue(exported.all { it == 0.toByte() })
        assertTrue(imported.all { it == 0.toByte() })
        assertEquals("", draft.password)
        assertNull(pendingBytes("pendingExport"))
        assertNull(pendingBytes("pendingImport"))
        assertFalse(viewModel.uiState.value.hasPendingImport)
        assertFalse(viewModel.uiState.value.isBackupBusy)
        awaitState { completed }
        assertFalse(SessionManager.hasKey())
        advanceTimeBy(VaultViewModel.PENDING_IMPORT_TTL_MS)
        runCurrent()
        assertNull(viewModel.uiState.value.userMessage)
    }

    @Test
    fun `onCleared zeroes both pending buffers`() = vmTest {
        runCurrent()
        val exported = byteArrayOf(1, 2, 3, 4)
        seedExport(exported)
        viewModel.onImportFilePicked(uri)
        awaitState { viewModel.uiState.value.hasPendingImport }
        val imported = checkNotNull(pendingBytes("pendingImport"))
        store.clear()
        assertTrue(exported.all { it == 0.toByte() })
        assertTrue(imported.all { it == 0.toByte() })
        assertNull(pendingBytes("pendingExport"))
        assertNull(pendingBytes("pendingImport"))
        advanceTimeBy(VaultViewModel.PENDING_IMPORT_TTL_MS)
        runCurrent()
        assertNull(viewModel.uiState.value.userMessage)
    }
}
