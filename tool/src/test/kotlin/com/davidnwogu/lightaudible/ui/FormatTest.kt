package com.davidnwogu.lightaudible.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test fun durationHours() = assertEquals("1:02:03", formatDuration(3_723_000))
    @Test fun durationUnderHour() = assertEquals("2:03", formatDuration(123_000))
    @Test fun runtime() = assertEquals("11 h 5 min", formatRuntime(39_900_000))
    @Test fun runtimeNull() = assertEquals("", formatRuntime(null))
    @Test fun runtimeUnderHour() = assertEquals("45 min", formatRuntime(45 * 60_000L))
}
