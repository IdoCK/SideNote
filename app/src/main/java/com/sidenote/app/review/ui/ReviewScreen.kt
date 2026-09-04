package com.sidenote.app.review.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import com.sidenote.app.review.ProjectEntry
import com.sidenote.app.review.ProjectGroup
import com.sidenote.app.review.ReviewEntry
import com.sidenote.app.review.ReviewState
import com.sidenote.app.review.ReviewTab
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

const val REVIEW_ROOT_TAG = "review-root"
const val REVIEW_ENTRY_TAG = "review-entry"
const val REVIEW_CHECKBOX_TAG = "review-checkbox"
const val REVIEW_TIMESTAMP_TAG = "review-timestamp"

internal val ReviewBackground = Color(0xFF111111)
internal val ReviewSurface = Color(0xFF1D1D1D)
internal val ReviewText = Color(0xFFF4F1EA)
internal val ReviewSubdued = Color(0xFFAAA7A0)

@Composable
fun ReviewScreen(
    state: ReviewState,
    onShowDates: () -> Unit,
    onShowProjects: () -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleExpanded: (ReviewEntry) -> Unit,
    onProcessedChange: (ReviewEntry, Boolean) -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenSourceDay: (ProjectEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = ReviewBackground,
        contentColor = ReviewText,
        modifier = modifier.fillMaxSize().testTag(REVIEW_ROOT_TAG),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ReviewNavigation(
                selected = state.tab,
                onShowDates = onShowDates,
                onShowProjects = onShowProjects,
                onOpenSettings = onOpenSettings,
            )
            HorizontalDivider(color = Color(0xFF3A3A3A))
            state.message?.let { message ->
                Text(
                    text = message.everydayText(),
                    color = ReviewText,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            when (state.tab) {
                ReviewTab.Dates -> DatesContent(
                    state = state,
                    onPreviousDay = onPreviousDay,
                    onNextDay = onNextDay,
                    onToggleExpanded = onToggleExpanded,
                    onProcessedChange = onProcessedChange,
                )
                ReviewTab.Projects -> ProjectsContent(
                    state = state,
                    onShowProjects = onShowProjects,
                    onOpenProject = onOpenProject,
                    onOpenSourceDay = onOpenSourceDay,
                    onToggleExpanded = onToggleExpanded,
                    onProcessedChange = onProcessedChange,
                )
            }
        }
    }
}

@Composable
private fun ReviewNavigation(
    selected: ReviewTab,
    onShowDates: () -> Unit,
    onShowProjects: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavigationChoice("Dates", selected == ReviewTab.Dates, onShowDates, Modifier.weight(1f))
        NavigationChoice("Projects", selected == ReviewTab.Projects, onShowProjects, Modifier.weight(1f))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = "Settings" }
                .clickable(role = Role.Button, onClick = onOpenSettings),
        ) {
            Text("⚙", fontSize = 22.sp)
        }
    }
}

@Composable
private fun NavigationChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
    ) {
        Text(
            text = label,
            color = if (selected) ReviewText else ReviewSubdued,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun DatesContent(
    state: ReviewState,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onToggleExpanded: (ReviewEntry) -> Unit,
    onProcessedChange: (ReviewEntry, Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = state.selectedDate?.displayDate().orEmpty(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .semantics { heading() },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            DayButton(
                label = "Previous",
                description = "Previous day",
                enabled = state.canGoPrevious,
                onClick = onPreviousDay,
            )
            DayButton(
                label = "Next",
                description = "Next day",
                enabled = state.canGoNext,
                onClick = onNextDay,
            )
        }
        val entries = state.selectedDay?.entries.orEmpty()
        if (entries.isEmpty()) {
            Text(
                text = "No SideNote entries for this day.",
                color = ReviewSubdued,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp,
                    top = 8.dp,
                    end = 12.dp,
                    bottom = 24.dp,
                ),
            ) {
                itemsIndexed(entries, key = { _, item -> item.id.toString() }) { index, entry ->
                    ReviewEntryRow(
                        entry = entry,
                        index = index,
                        expanded = state.isExpanded(entry),
                        showSourceDate = false,
                        onToggleExpanded = { onToggleExpanded(entry) },
                        onProcessedChange = { checked -> onProcessedChange(entry, checked) },
                        onOpenSourceDay = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun DayButton(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .sizeIn(minWidth = 96.dp, minHeight = 48.dp)
            .semantics { contentDescription = description },
    ) {
        Text(label)
    }
}

@Composable
private fun ProjectsContent(
    state: ReviewState,
    onShowProjects: () -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenSourceDay: (ProjectEntry) -> Unit,
    onToggleExpanded: (ReviewEntry) -> Unit,
    onProcessedChange: (ReviewEntry, Boolean) -> Unit,
) {
    val project = state.selectedProject
    if (project == null) {
        ProjectList(state.projects, onOpenProject)
    } else {
        ProjectDetail(
            state = state,
            project = project,
            onShowProjects = onShowProjects,
            onOpenSourceDay = onOpenSourceDay,
            onToggleExpanded = onToggleExpanded,
            onProcessedChange = onProcessedChange,
        )
    }
}

@Composable
private fun ProjectList(
    projects: List<ProjectGroup>,
    onOpenProject: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text(
            text = "Projects",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 14.dp)
                .semantics { heading() },
        )
        if (projects.isEmpty()) {
            Text("No project tags yet.", color = ReviewSubdued, modifier = Modifier.padding(8.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(projects, key = { _, project -> project.key }) { _, project ->
                    Surface(
                        color = ReviewSurface,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { onOpenProject(project.key) },
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(project.displayName, fontWeight = FontWeight.Bold)
                            Text(project.entries.entryCountLabel(), color = ReviewSubdued)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectDetail(
    state: ReviewState,
    project: ProjectGroup,
    onShowProjects: () -> Unit,
    onOpenSourceDay: (ProjectEntry) -> Unit,
    onToggleExpanded: (ReviewEntry) -> Unit,
    onProcessedChange: (ReviewEntry, Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            TextButton(
                onClick = onShowProjects,
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) {
                Text("All projects")
            }
            Text(
                project.displayName,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(project.entries.entryCountLabel(), color = ReviewSubdued)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 12.dp,
                end = 12.dp,
                bottom = 24.dp,
            ),
        ) {
            itemsIndexed(project.entries, key = { _, item -> item.id.toString() }) { index, entry ->
                ReviewEntryRow(
                    entry = entry,
                    index = index,
                    expanded = state.isExpanded(entry),
                    showSourceDate = true,
                    onToggleExpanded = { onToggleExpanded(entry) },
                    onProcessedChange = { checked -> onProcessedChange(entry, checked) },
                    onOpenSourceDay = { onOpenSourceDay(entry) },
                )
            }
        }
    }
}

@Composable
private fun ReviewEntryRow(
    entry: ReviewEntry,
    index: Int,
    expanded: Boolean,
    showSourceDate: Boolean,
    onToggleExpanded: () -> Unit,
    onProcessedChange: (Boolean) -> Unit,
    onOpenSourceDay: (() -> Unit)?,
) {
    val subduedModifier = if (entry.entry.processed) Modifier.alpha(0.64f) else Modifier
    val decoration = if (entry.entry.processed) TextDecoration.LineThrough else TextDecoration.None
    Surface(
        color = ReviewSurface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("$REVIEW_ENTRY_TAG.$index"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = entry.entry.processed,
                onCheckedChange = onProcessedChange,
                modifier = Modifier
                    .size(48.dp)
                    .testTag(REVIEW_CHECKBOX_TAG)
                    .semantics {
                        contentDescription = if (entry.entry.processed) {
                            "Mark note ${index + 1} unprocessed"
                        } else {
                            "Mark note ${index + 1} processed"
                        }
                    },
            )
            Column(modifier = subduedModifier.weight(1f).padding(vertical = 4.dp)) {
                Text(
                    text = entry.entry.time.isolatedTime(),
                    color = ReviewSubdued,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .testTag("$REVIEW_TIMESTAMP_TAG.$index")
                        .semantics { contentDescription = "Time ${entry.entry.time}" },
                )
                val styledText = buildAnnotatedString {
                    withStyle(SpanStyle(color = ReviewText, textDecoration = decoration)) {
                        append(entry.entry.text)
                    }
                }
                Text(
                    text = styledText,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                )
                if (showSourceDate && onOpenSourceDay != null) {
                    TextButton(
                        onClick = onOpenSourceDay,
                        modifier = Modifier.sizeIn(minHeight = 48.dp),
                    ) {
                        Text("Open ${entry.entry.date}")
                    }
                }
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        contentDescription = if (expanded) {
                            "Collapse note ${index + 1}"
                        } else {
                            "Expand note ${index + 1}"
                        }
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    }
                    .clickable(role = Role.Button, onClick = onToggleExpanded),
            ) {
                Text(if (expanded) "⌃" else "⌄", fontSize = 22.sp)
            }
        }
    }
}

private fun List<*>.entryCountLabel(): String =
    if (size == 1) "1 entry" else "$size entries"

private fun LocalDate.displayDate(): String =
    "\u2066${format(DateTimeFormatter.ofPattern("EEEE, MMMM d, uuuu", Locale.getDefault()))}\u2069"

private fun java.time.LocalTime.isolatedTime(): String =
    "\u2066${format(DateTimeFormatter.ofPattern("HH:mm"))}\u2069"

private fun com.sidenote.app.review.ReviewMessage.everydayText(): String = when (this) {
    com.sidenote.app.review.ReviewMessage.FileChanged ->
        "The Markdown file changed, so SideNote reloaded it without overwriting anything."
    com.sidenote.app.review.ReviewMessage.FolderAccessLost ->
        "SideNote no longer has access to the notes folder. Open Settings to choose it again."
    com.sidenote.app.review.ReviewMessage.CouldNotUpdate ->
        "That checkbox could not be updated. Your Markdown file was left unchanged."
    com.sidenote.app.review.ReviewMessage.CouldNotLoad ->
        "The notes folder could not be read. Try again from Settings."
}
