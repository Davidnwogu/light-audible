package com.davidnwogu.lightaudible.ui

import com.davidnwogu.lightaudible.api.Chapter

/** The last chapter with `startMs <= positionMs`; null for an empty list. */
fun currentChapter(chapters: List<Chapter>, positionMs: Long): Chapter? =
    chapters.lastOrNull { it.startMs <= positionMs }

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatRuntime(ms: Long?): String {
    if (ms == null) return ""
    val minutes = ms / 60_000
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "$h h $m min" else "$m min"
}

/** Credit entries (containing " - ") dropped, at most two names, then " +N". Falls back to the first entry minus its credit. */
fun formatAuthors(authors: List<String>): String {
    val names = authors.filterNot { it.contains(" - ") }
    if (names.isEmpty()) return authors.firstOrNull()?.substringBefore(" - ") ?: ""
    val shown = names.take(2).toMutableList()
    if (names.size > 2) shown[shown.lastIndex] = shown.last() + " +${names.size - 2}"
    return shown.joinToString(", ")
}
