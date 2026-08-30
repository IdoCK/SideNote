package com.sidenote.app.data.documents

interface TextDocumentStore {
    suspend fun listNames(): List<String>

    suspend fun read(name: String): String?

    suspend fun writeAtomically(
        name: String,
        expected: String?,
        replacement: String,
    ): WriteOutcome
}

sealed interface WriteOutcome {
    data object Success : WriteOutcome

    data object Conflict : WriteOutcome

    data class Failure(val error: RepositoryError) : WriteOutcome
}

class DocumentStoreException(
    val error: RepositoryError,
    cause: Throwable? = null,
) : Exception(cause)
