package com.sidenote.app.data.recovery

import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RecoveryDraftWriter(
    private val store: RecoveryDraftStore,
    private val scope: CoroutineScope,
    private val debounce: Duration,
) {
    private val writeMutex = Mutex()
    private var pendingWrite: Job? = null

    fun onDraftChanged(draft: RecoveryDraft) {
        pendingWrite?.cancel()
        pendingWrite = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            delay(debounce)
            writeMutex.withLock {
                store.save(draft)
            }
        }
    }

    suspend fun flushOnStop(draft: RecoveryDraft) {
        pendingWrite?.cancelAndJoin()
        pendingWrite = null
        writeMutex.withLock {
            store.save(draft)
        }
    }
}
