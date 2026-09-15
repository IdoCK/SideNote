package com.sidenote.app.review.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
const val REVIEW_DATES_CONTENT_TAG = "review-dates-content"
const val REVIEW_DATE_BROWSER_TAG = "review-date-browser"

internal val ReviewBackground = Color(0xFF111111)
internal val ReviewSurface = Color(0xFF1D1D1D)
internal val ReviewText = Color(0xFFF4F1EA)
internal val ReviewSubdued = Color(0xFFAAA7A0)

private val LocalReviewInteractive = staticCompositionLocalOf { true }
private const val ReviewMotionMillis = 220

/** Full state targets retain the outgoing page's data until its exit finishes. */
@Composable
private fun ReviewPageTransition(
    state: ReviewState,
    key: (ReviewState) -> Any?,
    direction: (ReviewState, ReviewState) -> Int,
    fullSlide: Boolean = false,
    content: @Composable (ReviewState) -> Unit,
) {
    val parentInteractive = LocalReviewInteractive.current
    AnimatedContent(
        targetState = state,
        contentKey = key,
        modifier = Modifier.fillMaxSize().clipToBounds(),
        transitionSpec = {
            val sign = direction(initialState, targetState)
            (slideInHorizontally(tween(ReviewMotionMillis, easing = FastOutSlowInEasing)) {
                sign * if (fullSlide) it else it / 12
            } + fadeIn(tween(ReviewMotionMillis))) togetherWith
                (slideOutHorizontally(tween(ReviewMotionMillis, easing = FastOutSlowInEasing)) {
                    -sign * if (fullSlide) it else it / 12
                } + fadeOut(tween(150))) using null
        },
        label = "Review page",
    ) { page ->
        val interactive = parentInteractive && key(page) == key(state)
        CompositionLocalProvider(LocalReviewInteractive provides interactive) {
            Box(
                modifier = Modifier.fillMaxSize().then(
                    if (interactive) Modifier else Modifier.clearAndSetSemantics {},
                ),
            ) {
                content(page)
            }
        }
    }
}

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
    onSelectDate: (LocalDate) -> Unit = {},
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
            ReviewPageTransition(
                state = state,
                key = { it.tab },
                direction = { _, target -> if (target.tab == ReviewTab.Projects) 1 else -1 },
            ) { page ->
                when (page.tab) {
                    ReviewTab.Dates -> DatesContent(
                        state = page,
                        onPreviousDay = onPreviousDay,
                        onNextDay = onNextDay,
                        onSelectDate = onSelectDate,
                        onToggleExpanded = onToggleExpanded,
                        onProcessedChange = onProcessedChange,
                    )
                    ReviewTab.Projects -> ProjectsContent(
                        state = page,
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
}

@Composable
internal fun ReviewNavigation(
    selected: ReviewTab?,
    onShowDates: () -> Unit,
    onShowProjects: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .height(IntrinsicSize.Min).selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NavigationChoice("Dates", selected == ReviewTab.Dates, onShowDates, Modifier.weight(1f))
        NavigationChoice("Projects", selected == ReviewTab.Projects, onShowProjects, Modifier.weight(1f))
        NavigationChoice("Settings", selected == null, onOpenSettings, Modifier.weight(1f))
    }
}

@Composable
private fun NavigationChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val background by animateColorAsState(
        if (selected) Color.White else Color.Transparent,
        tween(ReviewMotionMillis), label = "Tab background",
    )
    val foreground by animateColorAsState(
        if (selected) Color.Black else ReviewSubdued,
        tween(ReviewMotionMillis), label = "Tab text",
    )
    Box(
        modifier = modifier.fillMaxHeight().sizeIn(minHeight = 48.dp)
            .background(background, RectangleShape)
            .semantics { contentDescription = label }
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = foreground,
            fontWeight = FontWeight.Normal,
        )
    }
}

@Composable
private fun DatesContent(
    state: ReviewState,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onToggleExpanded: (ReviewEntry) -> Unit,
    onProcessedChange: (ReviewEntry, Boolean) -> Unit,
) {
    val interactive = LocalReviewInteractive.current
    val swipeThreshold = with(LocalDensity.current) { 64.dp.toPx() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(REVIEW_DATES_CONTENT_TAG)
            .pointerInput(state.selectedDate, state.canGoPrevious, state.canGoNext, interactive) {
                if (!interactive) return@pointerInput
                var horizontalTravel = 0f
                detectHorizontalDragGestures(
                    onDragStart = { horizontalTravel = 0f },
                    onHorizontalDrag = { _, dragAmount -> horizontalTravel += dragAmount },
                    onDragEnd = {
                        when {
                            horizontalTravel <= -swipeThreshold && state.canGoNext -> onNextDay()
                            horizontalTravel >= swipeThreshold && state.canGoPrevious -> onPreviousDay()
                        }
                    },
                )
            },
    ) {
        Text(
            text = state.selectedDate?.displayDate().orEmpty(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .semantics { heading() },
        )
        LazyRow(
            userScrollEnabled = interactive,
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
                .testTag(REVIEW_DATE_BROWSER_TAG),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.days, key = { day -> day.date.toString() }) { day ->
                val selected = day.date == state.selectedDate
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .sizeIn(minWidth = 96.dp, minHeight = 56.dp)
                        .background(if (selected) ReviewText else ReviewSurface, RectangleShape)
                        .border(1.dp, if (selected) ReviewText else Color(0xFF555555))
                        .semantics { contentDescription = "Open date ${day.date}" }
                        .selectable(selected = selected, enabled = interactive, role = Role.Tab) {
                            onSelectDate(day.date)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = day.date.shortDisplayDate(),
                        color = if (selected) Color.Black else ReviewText,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        ReviewPageTransition(
            state = state,
            key = { it.selectedDate },
            direction = { initial, target ->
                if (target.selectedDate != null && initial.selectedDate != null &&
                    target.selectedDate.isAfter(initial.selectedDate)
                ) 1 else -1
            },
            fullSlide = true,
        ) { dayState ->
            val selectedDay = dayState.selectedDay
            val entries = selectedDay?.entries.orEmpty()
            LazyColumn(
                userScrollEnabled = LocalReviewInteractive.current,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp,
                    top = 8.dp,
                    end = 12.dp,
                    bottom = 24.dp,
                ),
            ) {
                if (entries.isEmpty()) {
                    item {
                        Text(
                            text = "No SideNote entries for this day.",
                            color = ReviewSubdued,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                } else {
                    itemsIndexed(entries, key = { _, item -> item.id.toString() }) { index, entry ->
                        ReviewEntryRow(
                            entry = entry,
                            index = index,
                            expanded = dayState.isExpanded(entry),
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
    ReviewPageTransition(
        state = state,
        key = { it.selectedProject?.key },
        direction = { _, target -> if (target.selectedProject == null) -1 else 1 },
    ) { page ->
        val project = page.selectedProject
        if (project == null) {
            ProjectList(page.projects, onOpenProject)
        } else {
            ProjectDetail(
                state = page,
                project = project,
                onShowProjects = onShowProjects,
                onOpenSourceDay = onOpenSourceDay,
                onToggleExpanded = onToggleExpanded,
                onProcessedChange = onProcessedChange,
            )
        }
    }
}

@Composable
private fun ProjectList(
    projects: List<ProjectGroup>,
    onOpenProject: (String) -> Unit,
) {
    val interactive = LocalReviewInteractive.current
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
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                userScrollEnabled = interactive,
            ) {
                itemsIndexed(projects, key = { _, project -> project.key }) { _, project ->
                    Surface(
                        color = ReviewSurface,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = interactive, role = Role.Button) { onOpenProject(project.key) },
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
                enabled = LocalReviewInteractive.current,
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
            userScrollEnabled = LocalReviewInteractive.current,
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
    val interactive = LocalReviewInteractive.current
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
                onCheckedChange = if (interactive) onProcessedChange else null,
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
            BoxWithConstraints(modifier = subduedModifier.weight(1f)) {
                val textStyle = LocalTextStyle.current
                val textMeasurer = rememberTextMeasurer()
                // A fixed disclosure gutter prevents the arrow changing the overflow decision.
                val gutter = with(LocalDensity.current) { 48.dp.roundToPx() }
                val collapsedLayout = textMeasurer.measure(
                    text = entry.entry.text,
                    style = textStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = (constraints.maxWidth - gutter).coerceAtLeast(0)),
                )
                val expandable = collapsedLayout.hasVisualOverflow
                Row {
                    Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
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
                        AnimatedContent(
                            targetState = expanded && expandable,
                            transitionSpec = {
                                fadeIn(tween(150)) togetherWith fadeOut(tween(100)) using
                                    SizeTransform(clip = true) { _, _ ->
                                        tween(ReviewMotionMillis, easing = FastOutSlowInEasing)
                                    }
                            },
                            contentAlignment = Alignment.TopStart,
                            label = "Note disclosure",
                        ) { open ->
                            Text(
                                text = styledText,
                                style = textStyle,
                                maxLines = if (open) Int.MAX_VALUE else 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = if (open == (expanded && expandable)) Modifier
                                    else Modifier.clearAndSetSemantics {},
                            )
                        }
                        if (showSourceDate && onOpenSourceDay != null) {
                            TextButton(
                                enabled = interactive,
                                onClick = onOpenSourceDay,
                                modifier = Modifier.sizeIn(minHeight = 48.dp),
                            ) {
                                Text("Open ${entry.entry.date}")
                            }
                        }
                    }
                    if (expandable) {
                        val rotation by animateFloatAsState(
                            if (expanded) 180f else 0f,
                            tween(ReviewMotionMillis, easing = FastOutSlowInEasing), label = "Note arrow",
                        )
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
                                .clickable(enabled = interactive, role = Role.Button, onClick = onToggleExpanded),
                        ) {
                            Canvas(Modifier.size(28.dp, 16.dp).rotate(rotation)) {
                                val chevron = Path().apply {
                                    moveTo(size.width * 0.1f, size.height * 0.25f)
                                    lineTo(size.width * 0.5f, size.height * 0.75f)
                                    lineTo(size.width * 0.9f, size.height * 0.25f)
                                }
                                drawPath(chevron, ReviewText, style = Stroke(
                                    width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                                    ))
                            }
                        }
                    } else {
                        Spacer(Modifier.width(48.dp))
                    }
                }
            }
        }
    }
}

private fun List<*>.entryCountLabel(): String =
    if (size == 1) "1 entry" else "$size entries"

private fun LocalDate.displayDate(): String =
    "\u2066${format(DateTimeFormatter.ofPattern("EEEE, MMMM d, uuuu", Locale.getDefault()))}\u2069"

private fun LocalDate.shortDisplayDate(): String =
    format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))

private fun java.time.LocalTime.isolatedTime(): String =
    "\u2066${format(DateTimeFormatter.ofPattern("HH:mm"))}\u2069"

private fun com.sidenote.app.review.ReviewMessage.everydayText(): String = when (this) {
    com.sidenote.app.review.ReviewMessage.FileChanged ->
        "The Markdown file changed, so SideNote reloaded it without overwriting anything."
    com.sidenote.app.review.ReviewMessage.FolderAccessLost ->
        "SideNote no longer has access to the notes folder. Open Settings to choose it again."
    com.sidenote.app.review.ReviewMessage.CouldNotUpdate ->
        "That checkbox could not be updated. Your Markdown file was left unchanged."
    com.sidenote.app.review.ReviewMessage.UpdateUncertain ->
        "Android could not confirm that checkbox update. Check the Markdown file before trying again."
    com.sidenote.app.review.ReviewMessage.CouldNotLoad ->
        "The notes folder could not be read. Try again from Settings."
}
