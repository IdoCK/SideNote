package com.sidenote.app.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sidenote.app.data.documents.DocumentRepository
import com.sidenote.app.data.documents.DocumentStoreException
import com.sidenote.app.data.documents.RepositoryError
import com.sidenote.app.data.documents.UpdateResult
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ReviewViewModel(
    private val repository: DocumentRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReviewState())
    val state: StateFlow<ReviewState> = mutableState.asStateFlow()
    private val repositoryMutex = Mutex()
    private val processedActionsLock = Any()
    private val pendingProcessedActions = linkedMapOf<ProcessedTarget, Boolean>()
    private var processedActionsActive = false
    private var documentSourceInitialized = false
    private var documentSource: DocumentSource? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            repositoryMutex.withLock {
                loadDays(messageAfterLoad = null)
            }
        }
    }

    fun onDocumentSourceObserved(treeUri: String?, onboardingComplete: Boolean) {
        val observed = DocumentSource(treeUri, onboardingComplete)
        if (!documentSourceInitialized) {
            documentSourceInitialized = true
            documentSource = observed
            return
        }
        if (documentSource == observed) return
        documentSource = observed
        if (onboardingComplete) refresh()
    }

    fun selectDate(date: LocalDate) {
        if (mutableState.value.days.any { day -> day.date == date }) {
            mutableState.value = mutableState.value.copy(selectedDate = date)
        }
    }

    fun previousDay() {
        moveSelectedDate(-1)
    }

    fun nextDay() {
        moveSelectedDate(1)
    }

    fun showDates() {
        mutableState.value = mutableState.value.copy(
            tab = ReviewTab.Dates,
            selectedProjectKey = null,
        )
    }

    fun showProjects() {
        mutableState.value = mutableState.value.copy(
            tab = ReviewTab.Projects,
            selectedProjectKey = null,
        )
    }

    fun openProject(key: String) {
        if (mutableState.value.projects.any { project -> project.key == key }) {
            mutableState.value = mutableState.value.copy(
                tab = ReviewTab.Projects,
                selectedProjectKey = key,
            )
        }
    }

    fun openSourceDay(entry: ProjectEntry) {
        mutableState.value = mutableState.value.copy(
            tab = ReviewTab.Dates,
            selectedDate = entry.entry.date,
            selectedProjectKey = null,
        )
    }

    fun toggleExpanded(entry: ReviewEntry) {
        val current = mutableState.value
        val next = current.expanded.toMutableSet().apply {
            if (!add(entry.id)) remove(entry.id)
        }
        mutableState.value = current.copy(expanded = next)
    }

    fun setProcessed(entry: ReviewEntry, processed: Boolean) {
        val startDrain = synchronized(processedActionsLock) {
            pendingProcessedActions[entry.processedTarget()] = processed
            if (processedActionsActive) {
                false
            } else {
                processedActionsActive = true
                true
            }
        }
        if (startDrain) viewModelScope.launch { drainProcessedActions() }
    }

    fun clearMessage() {
        mutableState.value = mutableState.value.copy(message = null)
    }

    class Factory(
        private val repository: DocumentRepository,
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ReviewViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return ReviewViewModel(repository, ioDispatcher) as T
        }
    }

    private fun moveSelectedDate(delta: Int) {
        val current = mutableState.value
        val index = current.days.indexOfFirst { day -> day.date == current.selectedDate }
        val destination = index + delta
        if (index >= 0 && destination in current.days.indices) {
            mutableState.value = current.copy(selectedDate = current.days[destination].date)
        }
    }

    private suspend fun drainProcessedActions() {
        while (true) {
            val action = synchronized(processedActionsLock) {
                val pending = pendingProcessedActions.entries.firstOrNull()
                if (pending == null) {
                    processedActionsActive = false
                    null
                } else {
                    val target = pending.key
                    val processed = pending.value
                    pendingProcessedActions.remove(target)
                    ProcessedAction(target, processed)
                }
            } ?: return
            val continueDraining = repositoryMutex.withLock { applyProcessedAction(action) }
            if (!continueDraining) {
                synchronized(processedActionsLock) { pendingProcessedActions.clear() }
            }
        }
    }

    private suspend fun applyProcessedAction(action: ProcessedAction): Boolean {
        val currentEntry = mutableState.value.days
            .asSequence()
            .flatMap { day -> day.entries.asSequence() }
            .filter(action.target::matches)
            .singleOrNull()
        if (currentEntry == null) {
            loadDays(messageAfterLoad = ReviewMessage.FileChanged)
            return false
        }
        if (currentEntry.entry.processed == action.processed) return true
        val result = withContext(ioDispatcher) {
            repository.setProcessed(
                source = currentEntry.entry.source,
                fileName = currentEntry.fileName,
                expectedRaw = currentEntry.expectedRaw,
                processed = action.processed,
            )
        }
        return when (result) {
            UpdateResult.Success -> {
                loadDays(messageAfterLoad = null)
            }
            UpdateResult.Conflict -> {
                loadDays(messageAfterLoad = ReviewMessage.FileChanged)
                false
            }
            is UpdateResult.Failure -> {
                mutableState.value = mutableState.value.copy(
                    message = result.error.toReviewMessage(),
                )
                false
            }
        }
    }

    private suspend fun loadDays(messageAfterLoad: ReviewMessage?): Boolean {
        try {
            val parsedDays = withContext(ioDispatcher) { repository.days() }
                .sortedBy { day -> day.date }
            val days = parsedDays.map { day ->
                val fileName = "${day.date}.md"
                ReviewDay(
                    date = day.date,
                    entries = day.entries.map { entry ->
                        ReviewEntry(
                            entry = entry,
                            fileName = fileName,
                            expectedRaw = day.raw,
                        )
                    },
                )
            }
            val allEntriesChronological = days
                .flatMap(ReviewDay::entries)
                .sortedWith(
                    compareBy<ReviewEntry> { it.entry.date }
                        .thenBy { it.entry.time }
                        .thenBy { it.entry.source.ordinal },
                )
            val entriesByProject = linkedMapOf<String, MutableList<ProjectEntry>>()
            val displayByProject = linkedMapOf<String, String>()
            allEntriesChronological.forEach { reviewEntry ->
                reviewEntry.entry.projects.forEach { project ->
                    displayByProject.putIfAbsent(project.key, project.display)
                    entriesByProject.getOrPut(project.key, ::mutableListOf).add(reviewEntry)
                }
            }
            val newestFirst = compareByDescending<ProjectEntry> { it.entry.date }
                .thenByDescending { it.entry.time }
                .thenByDescending { it.entry.source.ordinal }
            val projects = displayByProject.map { (key, displayName) ->
                ProjectGroup(
                    key = key,
                    displayName = displayName,
                    entries = entriesByProject.getValue(key).sortedWith(newestFirst),
                )
            }
            val current = mutableState.value
            val availableDates = days.map(ReviewDay::date).toSet()
            val selectedDate = current.selectedDate
                ?.takeIf(availableDates::contains)
                ?: days.lastOrNull { day -> day.entries.any { entry -> !entry.entry.processed } }?.date
                ?: days.lastOrNull()?.date
            val selectedProjectKey = current.selectedProjectKey
                ?.takeIf { key -> projects.any { project -> project.key == key } }
            val availableEntries = days.flatMap(ReviewDay::entries).map(ReviewEntry::id).toSet()
            mutableState.value = current.copy(
                days = days,
                selectedDate = selectedDate,
                projects = projects,
                selectedProjectKey = selectedProjectKey,
                expanded = current.expanded.intersect(availableEntries),
                message = messageAfterLoad,
            )
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: DocumentStoreException) {
            mutableState.value = mutableState.value.copy(message = error.error.toReviewMessage())
            return false
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(message = ReviewMessage.CouldNotLoad)
            return false
        }
    }
}

private data class DocumentSource(
    val treeUri: String?,
    val onboardingComplete: Boolean,
)

private data class ProcessedAction(
    val target: ProcessedTarget,
    val processed: Boolean,
)

private data class ProcessedTarget(
    val fileName: String,
    val date: LocalDate,
    val time: LocalTime,
    val text: String,
    val lineStart: Int,
    val ordinal: Int,
) {
    fun matches(entry: ReviewEntry): Boolean =
        entry.fileName == fileName &&
            entry.entry.date == date &&
            entry.entry.time == time &&
            entry.entry.text == text &&
            entry.entry.source.lineStart == lineStart &&
            entry.entry.source.ordinal == ordinal
}

private fun ReviewEntry.processedTarget(): ProcessedTarget = ProcessedTarget(
    fileName = fileName,
    date = entry.date,
    time = entry.time,
    text = entry.text,
    lineStart = entry.source.lineStart,
    ordinal = entry.source.ordinal,
)

private fun RepositoryError.toReviewMessage(): ReviewMessage = when (this) {
    RepositoryError.PermissionLost -> ReviewMessage.FolderAccessLost
    RepositoryError.WriteFailed -> ReviewMessage.CouldNotUpdate
}
