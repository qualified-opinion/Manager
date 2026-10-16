package com.janreins.vaultlock

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.janreins.vaultlock.crypto.BackupEnvelope
import com.janreins.vaultlock.crypto.CryptoManager
import com.janreins.vaultlock.crypto.SessionManager
import com.janreins.vaultlock.data.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortableBackupTest {
    private lateinit var db: VaultDatabase
    private lateinit var repository: VaultRepository
    private val sourceKey = SecretKeySpec(ByteArray(32) { 1 }, "AES")
    private val destinationKey = SecretKeySpec(ByteArray(32) { 2 }, "AES")
    private fun password() = "separate backup password".toCharArray()
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).allowMainThreadQueries().build()
        repository = VaultRepository(db.vaultDao(), SecurityPreferences(context,
            context.getSharedPreferences("portable_test", Context.MODE_PRIVATE)))
        SessionManager.setKey(sourceKey)
    }
    @After fun cleanup() { SessionManager.lock(); db.close() }

    @Test fun `fresh vault with unrelated key restores every field and skips repeat imports`() = runTest {
        repository.saveEntry(VaultEntry(title = "Bank", username = "user", password = "secret",
            url = "https://example.com", notes = "notes", totpSecret = "JBSWY3DP",
            category = "Login", isFavorite = true, createdAt = 12345))
        val original = repository.getAllEntries().first().single()
        val backup = repository.createEncryptedBackupPayload(password())
        db.vaultDao().deleteAllEntries()
        SessionManager.setKey(destinationKey)
        assertEquals(1, repository.restoreEncryptedBackupPayload(backup, password()))
        val restored = repository.getAllEntries().first().single()
        assertEquals(original.copy(id = restored.id), restored)
        assertEquals(0, repository.restoreEncryptedBackupPayload(backup, password()))
        assertEquals(1, db.vaultDao().getCount())
        assertEquals(1, repository.restoreEncryptedBackupPayload(backup, password(), skipDuplicates = false))
        assertEquals(2, db.vaultDao().getCount())
    }

    @Test fun `invalid entry late in backup imports nothing and preserves destination`() = runTest {
        repository.saveEntry(VaultEntry(title = "Existing", password = "preserve"))
        val backup = repository.createEncryptedBackupPayload(password())
        val root = JSONObject(String(BackupEnvelope.decrypt(backup, password()), Charsets.UTF_8))
        root.getJSONArray("items").put(JSONObject().put("title", "Missing required fields"))
        val invalid = BackupEnvelope.encrypt(root.toString().toByteArray(), password())
        val before = db.vaultDao().getAllEntriesSync()
        try { repository.restoreEncryptedBackupPayload(invalid, password()); fail("Must reject invalid entry") }
        catch (_: org.json.JSONException) { }
        assertEquals(before, db.vaultDao().getAllEntriesSync())
        root.put("version", 999)
        val future = BackupEnvelope.encrypt(root.toString().toByteArray(), password())
        try { repository.restoreEncryptedBackupPayload(future, password()); fail("Must reject version") }
        catch (_: IllegalArgumentException) { }
        assertEquals(before, db.vaultDao().getAllEntriesSync())
    }

    @Test fun `unreadable 2FA secret imports into notes so the entry stays editable`() = runTest {
        repository.saveEntry(VaultEntry(title = "Mail", password = "secret", notes = "work account"))
        repository.saveEntry(VaultEntry(title = "Bank", password = "secret", totpSecret = "JBSWY3DP"))
        val backup = repository.createEncryptedBackupPayload(password())
        val root = JSONObject(String(BackupEnvelope.decrypt(backup, password()), Charsets.UTF_8))
        val items = root.getJSONArray("items")
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.getString("title") == "Mail") item.put("totpSecret", "MZ")
        }
        val edited = BackupEnvelope.encrypt(root.toString().toByteArray(), password())
        db.vaultDao().deleteAllEntries()
        SessionManager.setKey(destinationKey)

        assertEquals(2, repository.restoreEncryptedBackupPayload(edited, password()))
        val restored = repository.getAllEntries().first().associateBy { it.title }
        val mail = restored.getValue("Mail")
        assertEquals("", mail.totpSecret)
        assertEquals("work account\n\n${INVALID_TOTP_NOTE_PREFIX}MZ", mail.notes)
        assertEquals("JBSWY3DP", restored.getValue("Bank").totpSecret)
        assertEquals(0, repository.restoreEncryptedBackupPayload(edited, password()))
    }

    @Test fun `legacy backup requires explicit mode and original key`() = runTest {
        repository.saveEntry(VaultEntry(title = "Old backup", password = "secret"))
        val portable = repository.createEncryptedBackupPayload(password())
        val root = JSONObject(String(BackupEnvelope.decrypt(portable, password()), Charsets.UTF_8)).put("version", 1)
        val legacy = CryptoManager.encryptBytes(root.toString().toByteArray(), sourceKey)
        try { repository.restoreEncryptedBackupPayload(legacy, password()); fail("No silent downgrade") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, repository.restoreEncryptedBackupPayload(legacy, charArrayOf(), legacy = true))
        SessionManager.setKey(destinationKey)
        try { repository.restoreEncryptedBackupPayload(legacy, charArrayOf(), legacy = true); fail("Wrong key") }
        catch (_: javax.crypto.AEADBadTagException) { }
    }
}
