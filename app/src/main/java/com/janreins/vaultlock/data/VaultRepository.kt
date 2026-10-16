package com.janreins.vaultlock.data

import com.janreins.vaultlock.crypto.BackupEnvelope
import com.janreins.vaultlock.crypto.CryptoManager
import com.janreins.vaultlock.crypto.SessionManager
import com.janreins.vaultlock.ui.EntryValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.SecretKey

class VaultDecryptionException(entryId: Long, cause: Exception) :
    IllegalStateException("Cannot decrypt vault entry $entryId. Original data has been preserved.", cause)

/** Starts the note line that keeps an imported 2FA secret the editor can't read. */
const val INVALID_TOTP_NOTE_PREFIX = "2FA secret from backup (not recognised): "

class VaultRepository(
    private val vaultDao: VaultDao,
    private val securityPreferences: SecurityPreferences
) {
    private suspend fun <T> mutate(block: suspend (SecretKey) -> T): T {
        val generation = SessionManager.generation()
        return withContext(Dispatchers.IO) {
            VaultAccess.mutations.withLock {
                check(SessionManager.generation() == generation) { "Session changed. Please retry." }
                block(SessionManager.getKey())
            }
        }
    }

    /**
     * Decrypts database entities into domain models in real-time when the vault session is unlocked.
     */
    fun getAllEntries(): Flow<List<VaultEntry>> {
        return combine(vaultDao.getAllEntriesFlow(), SessionManager.activeKey) { entities, key ->
            if (key == null) emptyList() else {
                val entries = entities.map { it.toDomain(key) }
                // Discard work from a session that expired during decryption.
                if (SessionManager.activeKey.value === key) entries else emptyList()
            }
        }.flowOn(Dispatchers.Default)
    }

    suspend fun getEntryById(id: Long): VaultEntry? = withContext(Dispatchers.IO) {
        val entity = vaultDao.getEntryById(id) ?: return@withContext null
        val key = if (SessionManager.hasKey()) SessionManager.getKey() else null
        entity.toDomain(key)
    }

    suspend fun saveEntry(entry: VaultEntry) = mutate { key ->
        if (entry.id != 0L) {
            val existing = vaultDao.getEntryById(entry.id)
                ?: throw IllegalStateException("Entry no longer exists")
            existing.toDomainStrict(key)
        }
        val entity = entry.toEntity(key)
        check(SessionManager.activeKey.value === key) { "Vault locked before save." }
        if (entry.id == 0L) {
            vaultDao.insertEntry(entity)
        } else {
            vaultDao.updateEntry(entity)
        }
    }

    suspend fun toggleFavorite(id: Long, isFavorite: Boolean) = mutate { _ ->
        val entity = vaultDao.getEntryById(id) ?: return@mutate
        vaultDao.updateEntry(entity.copy(isFavorite = isFavorite, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteEntry(id: Long) = mutate { _ ->
        vaultDao.deleteEntryById(id)
    }

    /**
     * Whether [key] strictly decrypts at least one row: null for an empty vault. A batch rotation leaves
     * every row under one key, so a single row is positive evidence; one damaged row is not taken as
     * evidence against the key.
     */
    suspend fun keyDecryptsVault(key: SecretKey): Boolean? = withContext(Dispatchers.IO) {
        val entities = vaultDao.getAllEntriesSync()
        if (entities.isEmpty()) return@withContext null
        entities.any { entity ->
            try {
                entity.toDomainStrict(key)
                true
            } catch (_: VaultDecryptionException) {
                false
            }
        }
    }

    suspend fun reEncryptAll(
        oldKey: SecretKey,
        newKey: SecretKey,
        beforeWrite: () -> Unit = {},
        afterWrite: () -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        VaultAccess.mutations.withLock {
            val entities = vaultDao.getAllEntriesSync()
            val updated = entities.map { it.toDomainStrict(oldKey).toEntity(newKey) }
            beforeWrite()
            try {
                vaultDao.insertAll(updated)
                // Credential promotion is inside the same gate as the row rewrite.
                afterWrite()
            } catch (failure: Throwable) {
                // A cancelled Room continuation can hide a successful commit. Keep the journal
                // and close the session BEFORE releasing the gate; no old-key writes may follow.
                SessionManager.lock()
                throw failure
            }
        }
    }

    suspend fun createEncryptedBackupPayload(password: CharArray): ByteArray = mutate { key ->
        val entities = vaultDao.getAllEntriesSync()
        require(entities.size <= 50_000) { "Too many backup entries." }
        val itemsArray = JSONArray()

        for (entity in entities) {
            val domain = entity.toDomainStrict(key)
            validateBackupEntry(domain)
            val jsonItem = JSONObject().apply {
                put("id", domain.id)
                put("title", domain.title)
                put("username", domain.username)
                put("password", domain.password)
                put("url", domain.url)
                put("notes", domain.notes)
                put("totpSecret", domain.totpSecret)
                put("category", domain.category)
                put("isFavorite", domain.isFavorite)
                put("createdAt", domain.createdAt)
                put("updatedAt", domain.updatedAt)
            }
            itemsArray.put(jsonItem)
        }

        val backupRoot = JSONObject().apply {
            put("version", 2)
            put("app", "VaultLock")
            put("timestamp", System.currentTimeMillis())
            put("items", itemsArray)
        }

        val rawBytes = backupRoot.toString().toByteArray(Charsets.UTF_8)
        try { BackupEnvelope.encrypt(rawBytes, password) } finally { rawBytes.fill(0) }
    }

    /** Legacy import is explicit; never silently downgrade a damaged portable envelope. */
    suspend fun restoreEncryptedBackupPayload(
        encryptedBytes: ByteArray,
        password: CharArray,
        legacy: Boolean = false,
        skipDuplicates: Boolean = true
    ): Int = mutate { key ->
        require(encryptedBytes.size in 28..BackupEnvelope.MAX_BYTES) { "Invalid backup size." }
        val decryptedBytes = if (legacy) {
            require(!BackupEnvelope.isPortable(encryptedBytes)) { "Use portable restore for this file." }
            CryptoManager.decryptBytes(encryptedBytes, key)
        } else BackupEnvelope.decrypt(encryptedBytes, password)
        try {
            val root = JSONObject(String(decryptedBytes, Charsets.UTF_8))
            require(root.getString("app") == "VaultLock") { "Wrong backup application." }
            require(root.getInt("version") == if (legacy) 1 else 2) { "Unsupported backup version." }
            val items = root.getJSONArray("items")
            require(items.length() <= 50_000) { "Too many backup entries." }
            // Validate all entries before the single transactional Room batch insert.
            val existing = if (skipDuplicates) vaultDao.getAllEntriesSync().map {
                it.toDomainStrict(key).identity()
            }.toMutableSet() else mutableSetOf()
            val restored = mutableListOf<VaultEntryEntity>()
            for (i in 0 until items.length()) {
                val obj = items.getJSONObject(i)
                fun field(name: String): String {
                    val value = obj.get(name)
                    require(value is String && value.length <= 1_000_000) { "Invalid entry field: $name" }
                    return value
                }
                val entry = VaultEntry(
                    title = field("title"), username = field("username"), password = field("password"),
                    url = field("url"), notes = field("notes"), totpSecret = field("totpSecret"),
                    category = field("category"), isFavorite = obj.getBoolean("isFavorite"),
                    createdAt = obj.getLong("createdAt"), updatedAt = obj.getLong("updatedAt")
                ).withEditableTotpSecret()
                validateBackupEntry(entry)
                if (!skipDuplicates || existing.add(entry.identity())) {
                    restored.add(entry.toEntity(key).copy(updatedAt = entry.updatedAt))
                }
            }
            check(SessionManager.activeKey.value === key) { "Vault locked before import." }
            vaultDao.insertAll(restored)
            restored.size
        } finally {
            decryptedBytes.fill(0)
            password.fill('\u0000')
        }
    }

    /**
     * The editor refuses to save an entry whose 2FA secret it can't read, so an imported entry with
     * such a secret could never be edited again. Keep the original text in the notes instead of
     * dropping it, and leave the 2FA field empty.
     */
    private fun VaultEntry.withEditableTotpSecret(): VaultEntry {
        if (EntryValidation.isValidTotpSecret(totpSecret)) return this
        val kept = "$INVALID_TOTP_NOTE_PREFIX${totpSecret.trim()}"
        return copy(totpSecret = "", notes = if (notes.isBlank()) kept else "${notes.trimEnd()}\n\n$kept")
    }

    private fun validateBackupEntry(entry: VaultEntry) {
        require(entry.title.isNotBlank() && entry.createdAt >= 0 && entry.updatedAt >= 0) { "Invalid entry metadata." }
        require(entry.identity().all { it.length <= 1_000_000 }) { "Entry field exceeds backup limit." }
    }

    // Compare exact content, excluding local IDs/timestamps/favorite state. Never replace existing entries.
    private fun VaultEntry.identity(): List<String> = listOf(title, username, password, url, notes, totpSecret, category)

    suspend fun wipeEverything() = mutate { _ ->
        vaultDao.deleteAllEntries()
        securityPreferences.wipeAll()
        SessionManager.lock()
    }

    private fun VaultEntryEntity.toDomain(key: SecretKey?): VaultEntry {
        if (key == null) {
            return VaultEntry(
                id = id,
                title = "••••",
                username = "••••",
                password = "••••",
                url = "",
                notes = "",
                totpSecret = "",
                category = category,
                isFavorite = isFavorite,
                createdAt = createdAt,
                updatedAt = updatedAt
            )
        }

        return try {
            toDomainStrict(key)
        } catch (_: Exception) {
            VaultEntry(
                id = id,
                title = "[Decryption Failed]",
                username = "",
                password = "",
                url = "",
                notes = "",
                totpSecret = "",
                category = category,
                isFavorite = isFavorite,
                createdAt = createdAt,
                updatedAt = updatedAt
            )
        }
    }

    private fun VaultEntryEntity.toDomainStrict(key: SecretKey): VaultEntry {
        return try {
            VaultEntry(
                id = id,
                title = CryptoManager.decrypt(encryptedTitle, key),
                username = CryptoManager.decrypt(encryptedUsername, key),
                password = CryptoManager.decrypt(encryptedPassword, key),
                url = CryptoManager.decrypt(encryptedUrl, key),
                notes = CryptoManager.decrypt(encryptedNotes, key),
                totpSecret = CryptoManager.decrypt(encryptedTotpSecret, key),
                category = category,
                isFavorite = isFavorite,
                createdAt = createdAt,
                updatedAt = updatedAt
            )
        } catch (e: Exception) {
            throw VaultDecryptionException(id, e)
        }
    }

    private fun VaultEntry.toEntity(key: SecretKey): VaultEntryEntity {
        return VaultEntryEntity(
            id = id,
            encryptedTitle = CryptoManager.encrypt(title.trim(), key),
            encryptedUsername = CryptoManager.encrypt(username, key),
            encryptedPassword = CryptoManager.encrypt(password, key),
            encryptedUrl = CryptoManager.encrypt(url.trim(), key),
            encryptedNotes = CryptoManager.encrypt(notes, key),
            encryptedTotpSecret = CryptoManager.encrypt(totpSecret.trim(), key),
            category = category,
            isFavorite = isFavorite,
            createdAt = if (createdAt == 0L) System.currentTimeMillis() else createdAt,
            updatedAt = System.currentTimeMillis()
        )
    }
}
