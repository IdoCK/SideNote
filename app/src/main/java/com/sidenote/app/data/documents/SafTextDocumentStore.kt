package com.sidenote.app.data.documents

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.nio.charset.StandardCharsets

class SafTextDocumentStore(
    context: Context,
    private val treeUri: Uri,
) : TextDocumentStore {
    private val appContext = context.applicationContext ?: context
    private val contentResolver = appContext.contentResolver

    override suspend fun listNames(): List<String> = try {
        val names = root(requireWrite = false).listFiles().mapNotNull { it.name }
        verifyTreeAccess(requireWrite = false)
        names
    } catch (error: DocumentStoreException) {
        throw error
    } catch (error: SecurityException) {
        throw DocumentStoreException(RepositoryError.PermissionLost, error)
    } catch (error: Exception) {
        throw DocumentStoreException(RepositoryError.WriteFailed, error)
    }

    override suspend fun read(name: String): String? = try {
        val match = findExact(root(requireWrite = false), name)
        verifyTreeAccess(requireWrite = false)
        when (match) {
            ExactDocument.Missing -> null
            ExactDocument.Ambiguous -> throw DocumentStoreException(RepositoryError.WriteFailed)
            is ExactDocument.Found -> readDocument(match.document)
        }
    } catch (error: DocumentStoreException) {
        throw error
    } catch (error: SecurityException) {
        throw DocumentStoreException(RepositoryError.PermissionLost, error)
    } catch (error: Exception) {
        throw DocumentStoreException(RepositoryError.WriteFailed, error)
    }

    override suspend fun writeAtomically(
        name: String,
        expected: String?,
        replacement: String,
    ): WriteOutcome = try {
        val root = root(requireWrite = true)
        val initialMatch = findExact(root, name)
        verifyTreeAccess(requireWrite = true)
        val document = when (initialMatch) {
            ExactDocument.Missing -> {
                if (expected != null) return WriteOutcome.Conflict
                val created = root.createFile(MIME_TYPE_TEXT, name)
                verifyTreeAccess(requireWrite = true)
                created
                    ?.takeIf { it.name == name }
                    ?: return WriteOutcome.Failure(RepositoryError.WriteFailed)
            }
            ExactDocument.Ambiguous -> return WriteOutcome.Failure(RepositoryError.WriteFailed)
            is ExactDocument.Found -> {
                if (expected == null) return WriteOutcome.Conflict
                initialMatch.document
            }
        }

        val verifiedMatch = findExact(root, name)
        verifyTreeAccess(requireWrite = true)
        if (verifiedMatch !is ExactDocument.Found || verifiedMatch.document.uri != document.uri) {
            return WriteOutcome.Conflict
        }
        val current = readDocument(document)
        val expectedBeforeTruncation = expected ?: ""
        if (current != expectedBeforeTruncation) return WriteOutcome.Conflict

        val output = contentResolver.openOutputStream(document.uri, "wt")
        if (output == null) {
            verifyTreeAccess(requireWrite = true)
            return WriteOutcome.Failure(RepositoryError.WriteFailed)
        }
        output.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            writer.write(replacement)
        }

        if (readDocument(document) != replacement) {
            WriteOutcome.Failure(RepositoryError.WriteFailed)
        } else {
            verifyTreeAccess(requireWrite = true)
            WriteOutcome.Success
        }
    } catch (error: SecurityException) {
        WriteOutcome.Failure(RepositoryError.PermissionLost)
    } catch (error: DocumentStoreException) {
        WriteOutcome.Failure(error.error)
    } catch (error: Exception) {
        WriteOutcome.Failure(RepositoryError.WriteFailed)
    }

    private fun root(requireWrite: Boolean): DocumentFile {
        verifyTreeAccess(requireWrite)
        return DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw DocumentStoreException(RepositoryError.WriteFailed)
    }

    private fun verifyTreeAccess(requireWrite: Boolean) {
        val hasPersistedPermission = contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == treeUri &&
                permission.isReadPermission &&
                (!requireWrite || permission.isWritePermission)
        }
        if (!hasPersistedPermission) {
            throw DocumentStoreException(RepositoryError.PermissionLost)
        }

        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val cursor = contentResolver.query(
            rootDocumentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        ) ?: throw DocumentStoreException(RepositoryError.WriteFailed)
        cursor.use {
            if (!it.moveToFirst()) throw DocumentStoreException(RepositoryError.WriteFailed)
        }
    }

    private fun findExact(root: DocumentFile, name: String): ExactDocument {
        val matches = root.listFiles().filter { it.name == name }
        return when (matches.size) {
            0 -> ExactDocument.Missing
            1 -> ExactDocument.Found(matches.single())
            else -> ExactDocument.Ambiguous
        }
    }

    private fun readDocument(document: DocumentFile): String {
        val input = contentResolver.openInputStream(document.uri)
            ?: throw DocumentStoreException(RepositoryError.WriteFailed)
        return input.use { stream ->
            stream.readBytes().toString(StandardCharsets.UTF_8)
        }
    }

    private sealed interface ExactDocument {
        data object Missing : ExactDocument

        data object Ambiguous : ExactDocument

        data class Found(val document: DocumentFile) : ExactDocument
    }

    private companion object {
        const val MIME_TYPE_TEXT = "text/plain"
    }
}

sealed interface TreePermissionOutcome {
    data object Success : TreePermissionOutcome

    data class Failure(val error: RepositoryError) : TreePermissionOutcome
}

object SafTreePermission {
    const val REQUIRED_FLAGS: Int =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun persist(
        contentResolver: ContentResolver,
        treeUri: Uri,
        grantedFlags: Int,
    ): TreePermissionOutcome {
        if (grantedFlags and REQUIRED_FLAGS != REQUIRED_FLAGS) {
            return TreePermissionOutcome.Failure(RepositoryError.PermissionLost)
        }
        return try {
            contentResolver.takePersistableUriPermission(treeUri, REQUIRED_FLAGS)
            TreePermissionOutcome.Success
        } catch (error: SecurityException) {
            TreePermissionOutcome.Failure(RepositoryError.PermissionLost)
        } catch (error: Exception) {
            TreePermissionOutcome.Failure(RepositoryError.WriteFailed)
        }
    }
}
