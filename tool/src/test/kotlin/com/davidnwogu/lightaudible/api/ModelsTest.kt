package com.davidnwogu.lightaudible.api

import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelsTest {
    @Test
    fun decodesLiveLibraryItem() {
        val b = ApiJson.decodeFromString<LibraryBook>(
            """{"asin":"B0X","title":"T","authors":["A"],"narrators":[],"runtime_ms":null,"playable":true}""",
        )
        assertEquals(LibraryBook("B0X", "T", listOf("A"), emptyList(), null, true), b)
    }

    @Test
    fun decodesReadyState() {
        val s = ApiJson.decodeFromString<BookState>(
            """{"status":"ready","url":"https://b/x","url_expires_at":"2026-11-06T18:00:00+00:00"}""",
        )
        assertEquals(BookStatus.Ready, s.status)
        assertEquals("https://b/x", s.url)
        assertEquals("2026-11-06T18:00:00+00:00", s.urlExpiresAt)
    }

    @Test
    fun decodesPreparingWithEmptyChapters() {
        val s = ApiJson.decodeFromString<BookState>("""{"status":"preparing","chapters":[]}""")
        assertEquals(BookStatus.Preparing, s.status)
        assertTrue(s.chapters!!.isEmpty())
        assertNull(s.url)
    }

    @Test
    fun ignoresUnknownFields() {
        val p = ApiJson.decodeFromString<RemotePosition>(
            """{"position_ms":5,"updated_at":"1970-01-01T00:00:00+00:00","extra":1}""",
        )
        assertEquals(5L, p.positionMs)
    }

    @Test
    fun parsesOffsetTimestamp() {
        assertEquals(Instant.EPOCH, OffsetDateTime.parse("1970-01-01T00:00:00+00:00").toInstant())
    }
}
