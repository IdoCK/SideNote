package com.sidenote.app.privacy

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface LockState {
    val locked: StateFlow<Boolean>

    fun refresh()

    fun requestDismissKeyguard(activity: Activity)
}

class AndroidLockState(context: Context) : LockState {
    private val keyguardManager =
        (context.applicationContext ?: context).getSystemService(KeyguardManager::class.java)
    private val mutableLocked = MutableStateFlow(keyguardManager.isDeviceLocked)
    override val locked: StateFlow<Boolean> = mutableLocked.asStateFlow()

    override fun refresh() {
        mutableLocked.value = keyguardManager.isDeviceLocked
    }

    override fun requestDismissKeyguard(activity: Activity) {
        refresh()
        if (!mutableLocked.value) return
        keyguardManager.requestDismissKeyguard(
            activity,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    mutableLocked.value = false
                }

                override fun onDismissCancelled() {
                    mutableLocked.value = true
                }

                override fun onDismissError() {
                    mutableLocked.value = true
                }
            },
        )
    }
}

object UnlockedLockState : LockState {
    private val unlocked = MutableStateFlow(false)
    override val locked: StateFlow<Boolean> = unlocked.asStateFlow()

    override fun refresh() = Unit

    override fun requestDismissKeyguard(activity: Activity) = Unit
}

@Composable
fun UnlockGate(modifier: Modifier = Modifier) {
    Surface(
        color = Color(0xFF111111),
        contentColor = Color(0xFFF4F1EA),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Unlock SideNote",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}
