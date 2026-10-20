package com.janreins.vaultlock.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/** Ciphertext-only document access. All calls run on the ViewModel's IO dispatcher. */
interface BackupDocumentStore {
    fun write(uri: Uri, bytes: ByteArray)
    fun delete(uri: Uri): Boolean
    /** Implementations must enforce the import size limit before returning bytes. */
    fun read(uri: Uri): ByteArray
}

internal class ContentResolverBackupDocumentStore(
    private val resolver: ContentResolver
) : BackupDocumentStore {
    override fun write(uri: Uri, bytes: ByteArray) {
        checkNotNull(resolver.openOutputStream(uri, "wt")) {
            "Cannot open the backup destination."
        }.use { it.write(bytes) }
    }

    override fun delete(uri: Uri): Boolean = DocumentsContract.deleteDocument(resolver, uri)

    override fun read(uri: Uri): ByteArray = checkNotNull(resolver.openInputStream(uri)) {
        "Cannot open the selected backup."
    }.use { BackupFileIO.readBounded(it) }
}
