package com.sidenote.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val SideNoteColorScheme = lightColorScheme(
    primary = SideNotePrimary,
    onPrimary = SideNoteOnPrimary,
    background = SideNoteBackground,
    onBackground = SideNoteOnBackground,
    surface = SideNoteSurface,
    onSurface = SideNoteOnSurface,
)

@Composable
fun SideNoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SideNoteColorScheme,
        typography = SideNoteTypography,
        content = content,
    )
}
