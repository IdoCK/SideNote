package com.sidenote.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.os.Bundle
import androidx.compose.material3.Text
import com.sidenote.app.ui.theme.SideNoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SideNoteTheme {
                Text("Capture")
            }
        }
    }
}
