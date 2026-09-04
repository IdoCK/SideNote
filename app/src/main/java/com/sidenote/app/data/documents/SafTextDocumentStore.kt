package com.sidenote.app.data.documents

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

class SafTextDocumentStore(
    context: Context,
    private val treeUri: Uri,
) : TextDocumentStore {
    private val appContext = context.applicationContext ?: context
    private val contentResolver = appContext.contentResolver
    private val ownerToken: String by lazy(::loadOrCreateOwnerToken)

    override suspend fun listNames(): List<String> = try {
        val root = rootWithRecovery(requireWrite = false)
        val names = root.listFiles().mapNotNull { it.name }
            .filterNot(::isOwnedArtifactName)
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
        val match = findExact(rootWithRecovery(requireWrite = false), name)
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
    ): WriteOutcome {
        if (name.isBlank() || '/' in name || '\\' in name || isOwnedArtifactName(name)) {
            return WriteOutcome.Failure(RepositoryError.WriteFailed)
        }
        var targetMutationPossible = false
        var stage: DocumentFile? = null
        return try {
            val root = rootWithRecovery(requireWrite = true)
            if (!supports(root, DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE)) {
                return WriteOutcome.Failure(RepositoryError.WriteFailed)
            }
            val initialMatch = findExact(root, name)
            when {
                initialMatch == ExactDocument.Ambiguous ->
                    return WriteOutcome.Failure(RepositoryError.WriteFailed)
                expected == null && initialMatch != ExactDocument.Missing ->
                    return WriteOutcome.Conflict
                expected != null && initialMatch !is ExactDocument.Found ->
                    return WriteOutcome.Conflict
            }
            val original = (initialMatch as? ExactDocument.Found)?.document
            if (original != null && readDocument(original) != expected) return WriteOutcome.Conflict

            val transaction = ArtifactMetadata(
                targetName = name,
                expectedHash = expected?.contentHash(),
                replacementHash = replacement.contentHash(),
                transactionId = UUID.randomUUID().toString().replace("-", ""),
            )
            val stageName = transaction.name(ArtifactKind.Stage)
            val backupName = transaction.name(ArtifactKind.Backup)
            val stageUri = DocumentsContract.createDocument(
                contentResolver,
                root.uri,
                MIME_TYPE_TEXT,
                stageName,
            ) ?: return WriteOutcome.Failure(RepositoryError.WriteFailed)
            stage = when (val created = findExact(root, stageName)) {
                is ExactDocument.Found -> created.document.takeIf { sameDocument(it.uri, stageUri) }
                else -> null
            } ?: return WriteOutcome.Failure(RepositoryError.WriteFailed)
            writeFreshDocument(stage, replacement)
            if (
                readDocument(stage) != replacement ||
                !supports(stage, DocumentsContract.Document.FLAG_SUPPORTS_RENAME)
            ) {
                deleteOwnedIfSupported(stage)
                return WriteOutcome.Failure(RepositoryError.WriteFailed)
            }

            // The temporary sibling is complete. Revalidate exact identity and content at the
            // last possible point before any operation can move the original.
            val revalidated = findExact(root, name)
            if (original == null) {
                if (revalidated != ExactDocument.Missing) {
                    deleteOwnedIfSupported(stage)
                    return WriteOutcome.Conflict
                }
                // Once rename is invoked, an exception cannot tell us whether the provider
                // rejected it before mutation or renamed successfully and failed afterward.
                targetMutationPossible = true
                val target = renameAndFind(root, stage, name)
                stage = null
                return if (readDocument(target) == replacement) {
                    verifyTreeAccess(requireWrite = true)
                    WriteOutcome.Success
                } else {
                    WriteOutcome.Uncertain(RepositoryError.WriteFailed)
                }
            }
            if (
                revalidated !is ExactDocument.Found ||
                !sameDocument(revalidated.document.uri, original.uri) ||
                readDocument(revalidated.document) != expected
            ) {
                deleteOwnedIfSupported(stage)
                return WriteOutcome.Conflict
            }
            if (!supports(original, DocumentsContract.Document.FLAG_SUPPORTS_RENAME)) {
                deleteOwnedIfSupported(stage)
                return WriteOutcome.Failure(RepositoryError.WriteFailed)
            }

            targetMutationPossible = true
            val backup = renameAndFind(root, original, backupName)
            if (readDocument(backup) != expected || findExact(root, name) != ExactDocument.Missing) {
                return WriteOutcome.Uncertain(RepositoryError.WriteFailed)
            }
            val target = renameAndFind(root, stage, name)
            stage = null
            val confirmed = readDocument(target) == replacement &&
                readDocument(backup) == expected &&
                findExact(root, backupName) is ExactDocument.Found
            if (!confirmed) return WriteOutcome.Uncertain(RepositoryError.WriteFailed)

            // The replacement is now confirmed. Backup removal is best effort: an undeletable
            // owned artifact remains hidden and recoverable, never treated as Markdown truth.
            deleteOwnedIfSupported(backup)
            verifyTreeAccess(requireWrite = true)
            WriteOutcome.Success
        } catch (error: SecurityException) {
            if (targetMutationPossible) WriteOutcome.Uncertain(RepositoryError.PermissionLost)
            else WriteOutcome.Failure(RepositoryError.PermissionLost)
        } catch (error: DocumentStoreException) {
            if (targetMutationPossible) WriteOutcome.Uncertain(error.error)
            else WriteOutcome.Failure(error.error)
        } catch (_: Exception) {
            if (targetMutationPossible) WriteOutcome.Uncertain(RepositoryError.WriteFailed)
            else WriteOutcome.Failure(RepositoryError.WriteFailed)
        } finally {
            if (!targetMutationPossible) stage?.let(::deleteOwnedIfSupported)
        }
    }

    private fun rootWithRecovery(requireWrite: Boolean): DocumentFile {
        val root = root(requireWrite)
        val artifacts = ownedArtifacts(root)
        if (artifacts.isNotEmpty()) {
            verifyTreeAccess(requireWrite = true)
            if (!recoverOwnedArtifacts(root, artifacts)) {
                throw DocumentStoreException(RepositoryError.WriteFailed)
            }
        }
        return root
    }

    private fun recoverOwnedArtifacts(root: DocumentFile, artifacts: List<OwnedArtifact>): Boolean {
        return artifacts.groupBy(OwnedArtifact::metadata).values.all { transaction ->
            if (transaction.count { it.kind == ArtifactKind.Stage } > 1 ||
                transaction.count { it.kind == ArtifactKind.Backup } > 1
            ) return@all false
            val metadata = transaction.first().metadata
            val stage = transaction.singleOrNull { it.kind == ArtifactKind.Stage }?.document
            val backup = transaction.singleOrNull { it.kind == ArtifactKind.Backup }?.document
            val stageState = when {
                stage == null -> StageState.Absent
                readDocument(stage).contentHash() == metadata.replacementHash -> StageState.Complete
                else -> StageState.Incomplete
            }
            val verifiedBackup = backup?.takeIf {
                metadata.expectedHash != null &&
                    readDocument(it).contentHash() == metadata.expectedHash
            }
            val completeStage = stage.takeIf { stageState == StageState.Complete }
            when (val target = findExact(root, metadata.targetName)) {
                ExactDocument.Ambiguous -> false
                ExactDocument.Missing -> when {
                    verifiedBackup != null ->
                        restoreBackup(root, verifiedBackup, metadata, completeStage)
                    metadata.expectedHash == null -> {
                        // Creation had not produced an authoritative target. A complete stage
                        // can be removed, while partial/unknown bytes remain hidden as an owned
                        // quarantine artifact and are never promoted to Markdown truth.
                        completeStage?.let(::deleteOwnedIfSupported)
                        true
                    }
                    else -> false
                }
                is ExactDocument.Found -> {
                    val targetHash = readDocument(target.document).contentHash()
                    if (targetHash != metadata.expectedHash && targetHash != metadata.replacementHash) {
                        false
                    } else {
                        verifiedBackup?.let(::deleteOwnedIfSupported)
                        completeStage?.let(::deleteOwnedIfSupported)
                        true
                    }
                }
            }
        }
    }

    private fun restoreBackup(
        root: DocumentFile,
        backup: DocumentFile,
        metadata: ArtifactMetadata,
        stage: DocumentFile?,
    ): Boolean = try {
        if (!supports(backup, DocumentsContract.Document.FLAG_SUPPORTS_RENAME)) return false
        val restored = renameAndFind(root, backup, metadata.targetName)
        if (readDocument(restored).contentHash() != metadata.expectedHash) return false
        stage?.let(::deleteOwnedIfSupported)
        true
    } catch (_: Exception) {
        val restored = findExact(root, metadata.targetName) as? ExactDocument.Found ?: return false
        if (readDocument(restored.document).contentHash() != metadata.expectedHash) return false
        stage?.let(::deleteOwnedIfSupported)
        true
    }

    private fun ownedArtifacts(root: DocumentFile): List<OwnedArtifact> =
        root.listFiles().mapNotNull { document ->
            val name = document.name ?: return@mapNotNull null
            parseOwnedArtifact(name)?.let { (metadata, kind) ->
                OwnedArtifact(metadata, kind, document)
            }
        }

    private fun parseOwnedArtifact(name: String): Pair<ArtifactMetadata, ArtifactKind>? {
        val match = artifactPattern().matchEntire(name) ?: return null
        val target = runCatching {
            Base64.decode(match.groupValues[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                .toString(StandardCharsets.UTF_8)
        }.getOrNull() ?: return null
        val expectedHash = match.groupValues[2].takeUnless { it == NO_EXPECTED_HASH }
        val metadata = ArtifactMetadata(
            targetName = target,
            expectedHash = expectedHash,
            replacementHash = match.groupValues[3],
            transactionId = match.groupValues[4],
        )
        val kind = when (match.groupValues[5]) {
            ArtifactKind.Stage.suffix -> ArtifactKind.Stage
            ArtifactKind.Backup.suffix -> ArtifactKind.Backup
            else -> return null
        }
        return metadata to kind
    }

    private fun isOwnedArtifactName(name: String): Boolean =
        name.startsWith(artifactPrefix())

    private fun ArtifactMetadata.name(kind: ArtifactKind): String {
        val target = Base64.encodeToString(
            targetName.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        return buildString {
            append(artifactPrefix())
            append(target)
            append('.')
            append(expectedHash ?: NO_EXPECTED_HASH)
            append('.')
            append(replacementHash)
            append('.')
            append(transactionId)
            append('.')
            append(kind.suffix)
        }
    }

    private fun artifactPrefix(): String = ".sidenote-$ownerToken."

    private fun artifactPattern(): Regex = Regex(
        "^${Regex.escape(artifactPrefix())}([A-Za-z0-9_-]+)\\.([a-f0-9]{32}|$NO_EXPECTED_HASH)" +
            "\\.([a-f0-9]{32})\\.([a-f0-9]{32})\\.(stage|backup)$",
    )

    private fun loadOrCreateOwnerToken(): String {
        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.getString(PREFERENCE_OWNER_TOKEN, null)?.let { return it }
        val created = UUID.randomUUID().toString().replace("-", "")
        if (!preferences.edit().putString(PREFERENCE_OWNER_TOKEN, created).commit()) {
            throw DocumentStoreException(RepositoryError.WriteFailed)
        }
        return created
    }

    private fun renameAndFind(
        root: DocumentFile,
        document: DocumentFile,
        newName: String,
    ): DocumentFile {
        val renamedUri = DocumentsContract.renameDocument(contentResolver, document.uri, newName)
            ?: throw DocumentStoreException(RepositoryError.WriteFailed)
        val renamed = findExact(root, newName) as? ExactDocument.Found
            ?: throw DocumentStoreException(RepositoryError.WriteFailed)
        if (!sameDocument(renamed.document.uri, renamedUri)) {
            throw DocumentStoreException(RepositoryError.WriteFailed)
        }
        return renamed.document
    }

    private fun writeFreshDocument(document: DocumentFile, text: String) {
        val output = contentResolver.openOutputStream(document.uri, "wt")
            ?: throw DocumentStoreException(RepositoryError.WriteFailed)
        output.bufferedWriter(StandardCharsets.UTF_8).use { writer -> writer.write(text) }
    }

    private fun deleteOwnedIfSupported(document: DocumentFile) {
        val name = document.name ?: return
        if (!isOwnedArtifactName(name)) return
        runCatching {
            if (supports(document, DocumentsContract.Document.FLAG_SUPPORTS_DELETE)) {
                DocumentsContract.deleteDocument(contentResolver, document.uri)
            }
        }
    }

    private fun supports(document: DocumentFile, capability: Int): Boolean =
        documentFlags(document.uri) and capability != 0

    private fun documentFlags(uri: Uri): Int {
        val cursor = contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
            null,
            null,
            null,
        ) ?: throw DocumentStoreException(RepositoryError.WriteFailed)
        return cursor.use {
            if (!it.moveToFirst()) throw DocumentStoreException(RepositoryError.WriteFailed)
            it.getInt(0)
        }
    }

    private fun sameDocument(left: Uri, right: Uri): Boolean = runCatching {
        left.authority == right.authority &&
            DocumentsContract.getDocumentId(left) == DocumentsContract.getDocumentId(right)
    }.getOrDefault(left == right)

    private fun String.contentHash(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .take(16)
        .joinToString("") { byte -> "%02x".format(byte) }

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

    private data class ArtifactMetadata(
        val targetName: String,
        val expectedHash: String?,
        val replacementHash: String,
        val transactionId: String,
    )

    private data class OwnedArtifact(
        val metadata: ArtifactMetadata,
        val kind: ArtifactKind,
        val document: DocumentFile,
    )

    private enum class ArtifactKind(val suffix: String) {
        Stage("stage"),
        Backup("backup"),
    }

    private enum class StageState {
        Absent,
        Complete,
        Incomplete,
    }

    private companion object {
        const val MIME_TYPE_TEXT = "text/plain"
        const val PREFERENCES_NAME = "saf_staged_replacement"
        const val PREFERENCE_OWNER_TOKEN = "owner_token"
        const val NO_EXPECTED_HASH = "none"
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
