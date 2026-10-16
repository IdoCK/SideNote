package com.sidenote.app.capture

internal class ExternalSetupLaunchGate(
    private val disarm: () -> Unit,
    private val rearm: () -> Unit,
) {
    private val lock = Any()
    private var nextToken = 0L
    private var activeToken: Long? = null

    fun begin(): Long? = synchronized(lock) {
        if (activeToken != null) return@synchronized null

        val token = ++nextToken
        activeToken = token
        disarm()
        token
    }

    fun activeToken(): Long? = synchronized(lock) { activeToken }

    fun onLaunchFailed(token: Long): Boolean = finish(token)

    fun onResult(token: Long): Boolean = finish(token)

    private fun finish(token: Long): Boolean = synchronized(lock) {
        if (activeToken != token) return@synchronized false

        activeToken = null
        rearm()
        true
    }
}
