package com.davidnwogu.lightaudible.playback

sealed interface Phase {
    data object Idle : Phase
    data object Preparing : Phase
    data object Connecting : Phase
    data object Ready : Phase
    data object Reconnecting : Phase
    data class Failed(val message: String) : Phase
    data object NeedsSignIn : Phase
    data object Unplayable : Phase
}

data class SessionState(
    val asin: String?,
    val title: String?,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val phase: Phase,
)

/** [positionMs] + [deltaMs] clamped to the book; floored at 0 only while the duration is unknown. */
fun skipTarget(positionMs: Long, durationMs: Long, deltaMs: Long): Long {
    val target = positionMs + deltaMs
    return if (durationMs <= 0) target.coerceAtLeast(0) else target.coerceIn(0, durationMs)
}
