package com.davidnwogu.lightaudible.store

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.davidnwogu.lightaudible.api.Credentials
import com.davidnwogu.lightaudible.api.LibraryBook
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class DataStoreToolStoreTest {
    private fun TestScope.newStore(): DataStoreToolStore {
        val dir = createTempDirectory("ds").toFile()
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(dir, "t.preferences_pb") }
        return DataStoreToolStore(ds)
    }

    @Test
    fun credentialsRoundTripAndClearTokenKeepsUrl() = runTest {
        val s = newStore()
        assertNull(s.credentials())
        assertNull(s.serverUrl())
        s.saveCredentials(Credentials("http://h:1", "tok"))
        assertEquals(Credentials("http://h:1", "tok"), s.credentials())
        s.clearToken()
        assertNull(s.credentials())
        assertEquals("http://h:1", s.serverUrl())
    }

    @Test
    fun libraryCacheRoundTrips() = runTest {
        val s = newStore()
        assertNull(s.libraryCache())
        val book = LibraryBook("B1", "T", listOf("A"), listOf("N"), 1000L, true)
        s.saveLibraryCache(LibraryCache(listOf(book), "etag1", 42L))
        assertEquals(LibraryCache(listOf(book), "etag1", 42L), s.libraryCache())
        s.saveLibraryCache(LibraryCache(emptyList(), null, 43L))
        assertEquals(LibraryCache(emptyList(), null, 43L), s.libraryCache())
    }

    @Test
    fun putPendingCoalescesAndIgnoresOlder() = runTest {
        val s = newStore()
        s.putPendingPosition(PendingPosition("A", 100, 10))
        s.putPendingPosition(PendingPosition("B", 5, 10))
        s.putPendingPosition(PendingPosition("A", 200, 20))
        s.putPendingPosition(PendingPosition("A", 50, 15))
        assertEquals(
            setOf(PendingPosition("A", 200, 20), PendingPosition("B", 5, 10)),
            s.pendingPositions().toSet(),
        )
        assertEquals(2, s.pendingPositions().size)
    }

    @Test
    fun removeWithStaleCopyKeepsNewer() = runTest {
        val s = newStore()
        val old = PendingPosition("A", 100, 10)
        val new = PendingPosition("A", 200, 20)
        s.putPendingPosition(old)
        s.putPendingPosition(new)
        s.removePendingPosition(old)
        assertEquals(listOf(new), s.pendingPositions())
        s.removePendingPosition(new)
        assertEquals(emptyList(), s.pendingPositions())
    }

    @Test
    fun clockOffsetDefaultsToZeroAndRoundTrips() = runTest {
        val s = newStore()
        assertEquals(0L, s.clockOffsetMs())
        s.saveClockOffsetMs(-1234L)
        assertEquals(-1234L, s.clockOffsetMs())
    }

    @Test
    fun playbackSpeedDefaultsToOneAndRoundTrips() = runTest {
        val s = newStore()
        assertEquals(1f, s.playbackSpeed())
        s.savePlaybackSpeed(1.25f)
        assertEquals(1.25f, s.playbackSpeed())
    }
}
