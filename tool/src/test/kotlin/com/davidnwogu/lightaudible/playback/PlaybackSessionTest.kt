package com.davidnwogu.lightaudible.playback

import com.davidnwogu.lightaudible.api.ApiResult
import com.davidnwogu.lightaudible.api.BookState
import com.davidnwogu.lightaudible.api.BookStatus
import com.davidnwogu.lightaudible.api.LibraryBook
import com.davidnwogu.lightaudible.api.RemotePosition
import com.davidnwogu.lightaudible.api.ServerClock
import com.davidnwogu.lightaudible.fakes.FakePlayback
import com.davidnwogu.lightaudible.fakes.FakePlayback.Call
import com.davidnwogu.lightaudible.fakes.FakePlaybackFactory
import com.davidnwogu.lightaudible.fakes.FakeServerApi
import com.davidnwogu.lightaudible.fakes.InMemoryToolStore
import com.davidnwogu.lightaudible.sync.Downloads
import com.davidnwogu.lightaudible.sync.PositionSync
import com.davidnwogu.lightaudible.sync.Preparer
import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionTest {
    private val api = FakeServerApi()
    private val store = InMemoryToolStore()
    private val dir = createTempDirectory("pb").toFile()
    private val factory = FakePlaybackFactory()
    private val t0 = Instant.parse("2030-01-01T00:00:00Z").toEpochMilli()
    private val clock = ServerClock({ t0 }, 0, {})
    private val bookA = LibraryBook("A", "Book A", listOf("Ann"), listOf("N"), 36_000_000L, true)
    private val bookB = LibraryBook("B", "Book B", listOf("Bob"), listOf("N"), 1_000_000L, true)
    private val resume = mutableMapOf<String, Long>()
    private val sourceError = PlaybackError(isSource = true, diagnostic = "ERROR_CODE_IO_BAD_HTTP_STATUS")

    init {
        api.prepareResult = { ApiResult.Ok(BookState(BookStatus.Ready, url(it, api.prepareCalls.size), "2030-01-01T06:00:00Z")) }
        api.positionResult = { ApiResult.Ok(RemotePosition(resume[it] ?: 0L, "2029-12-31T00:00:00Z")) }
        api.putPositionResult = { ApiResult.Ok(Unit) }
    }

    private fun url(asin: String, n: Int) = "https://s/$asin?v=$n"
    private fun item(book: LibraryBook, source: PlaybackSource) =
        PlaybackItem(book.asin, book.title, book.authors.first(), source)

    private fun TestScope.session(): PlaybackSession {
        val preparer = Preparer(api, 5_000)
        val downloads = Downloads(dir, OkHttpClient(), preparer, backgroundScope)
        return PlaybackSession(PositionSync(api, store, clock), preparer, downloads, backgroundScope) {
            t0 + testScheduler.currentTime
        }
    }

    private fun records() = api.putPositionCalls.map { it.asin to it.positionMs }
    private fun FakePlayback.replaces() = calls.filterIsInstance<Call.ReplaceItem>()

    @Test
    fun openWithNothingLive() = runTest {
        resume["A"] = 42_000
        val s = session()
        s.open(bookA, factory)
        runCurrent()
        assertEquals(1, factory.openCount)
        assertEquals(
            listOf(Call.AwaitReady, Call.SetItem(item(bookA, PlaybackSource.Url(url("A", 1))), 42_000), Call.Play),
            factory.last.calls,
        )
        val st = s.state.value
        assertEquals(Phase.Ready, st.phase)
        assertEquals("A", st.asin)
        assertEquals("Book A", st.title)
        assertEquals(42_000, st.positionMs)
        assertTrue(st.isPlaying)
    }

    @Test
    fun openReusesLiveItemForSameAsin() = runTest {
        factory.next = {
            FakePlayback().apply {
                currentItemId.value = "A"
                positionMs.value = 99_000
                isPlaying.value = true
            }
        }
        val s = session()
        s.open(bookA, factory)
        runCurrent()
        val calls = factory.last.calls
        assertTrue(calls.none { it is Call.SetItem || it is Call.SeekTo || it is Call.ReplaceItem }, "$calls")
        assertTrue(api.positionCalls.isEmpty())
        assertTrue(api.prepareCalls.isEmpty())
        assertEquals(99_000, s.state.value.positionMs)
        assertEquals(Phase.Ready, s.state.value.phase)
    }

    @Test
    fun openDifferentBookWhileOnePlaysRecordsOldPositionFirst() = runTest {
        val s = session()
        s.open(bookA, factory)
        runCurrent()
        val p = factory.last
        p.positionMs.value = 500_000
        runCurrent()
        resume["B"] = 7_000
        s.open(bookB, factory)
        assertEquals(0, s.state.value.positionMs)
        assertEquals(1_000_000, s.state.value.durationMs)
        runCurrent()
        assertEquals(1, factory.openCount)
        assertEquals(listOf("A" to 500_000L), records())
        val sets = p.calls.filterIsInstance<Call.SetItem>()
        assertEquals(Call.SetItem(item(bookB, PlaybackSource.Url(url("B", 2))), 7_000), sets.last())
        assertEquals("B", s.state.value.asin)
        assertEquals(7_000, s.state.value.positionMs)
    }

    @Test
    fun downloadedBookUsesFileSource() = runTest {
        val f = File(dir, "A.m4b").apply { writeBytes(byteArrayOf(1)) }
        val s = session()
        s.open(bookA, factory)
        runCurrent()
        assertEquals(Call.SetItem(item(bookA, PlaybackSource.File(f)), 0), factory.last.calls[1])
        assertTrue(api.prepareCalls.isEmpty())
    }

    @Test
    fun cachedUrlReusedUntilTenMinutesBeforeExpiry() = runTest {
        val s = session()
        suspend fun openPauseDetach() {
            s.open(bookA, factory)
            runCurrent()
            s.togglePlay()
            s.detach()
            runCurrent()
            assertTrue(factory.last.released)
        }
        openPauseDetach()
        assertEquals(1, api.prepareCalls.size)
        advanceTimeBy(5 * 3_600_000L + 49 * 60_000L)
        openPauseDetach()
        assertEquals(1, api.prepareCalls.size)
        assertEquals(PlaybackSource.Url(url("A", 1)), (factory.last.calls[1] as Call.SetItem).item.source)
        advanceTimeBy(2 * 60_000L)
        openPauseDetach()
        assertEquals(2, api.prepareCalls.size)
        assertEquals(PlaybackSource.Url(url("A", 2)), (factory.last.calls[1] as Call.SetItem).item.source)
        assertEquals(3, factory.openCount)
    }

    @Test
    fun reportsOnPauseAndEvery60Seconds() = runTest {
        val s = session()
        s.open(bookA, factory)
        runCurrent()
        val p = factory.last
        advanceTimeBy(30_000)
        p.positionMs.value = 30_000
        advanceTimeBy(60_000)
        p.positionMs.value = 90_000
        advanceTimeBy(35_000)
        assertEquals(listOf("A" to 30_000L, "A" to 90_000L), records())
        p.positionMs.value = 125_000
        s.togglePlay()
        runCurrent()
        assertEquals(listOf("A" to 30_000L, "A" to 90_000L, "A" to 125_000L), records())
        advanceTimeBy(180_000)
        assertEquals(3, records().size)
        assertFalse(p.released)
    }

    @Test
    fun detachWhilePausedReleasesHandle() = runTest {
        val s = session()
        s.open(bookA, factory)
        runCurrent()
        val p = factory.last
        p.positionMs.value = 7_000
        s.togglePlay()
        runCurrent()
        assertEquals(listOf("A" to 7_000L), records())
        s.detach()
        runCurrent()
        assertTrue(p.released)
        assertEquals(listOf("A" to 7_000L, "A" to 7_000L), records())
        assertEquals(Phase.Idle, s.state.value.phase)
        s.open(bookA, factory)
        runCurrent()
        assertEquals(2, factory.openCount)
    }

    @Test
    fun detachWhilePlayingKeepsHandleUntilPause() = runTest {
        val s = session()
        s.open(bookA, factory)
        runCurrent()
        val p = factory.last
        s.detach()
        runCurrent()
        assertFalse(p.released)
        p.positionMs.value = 8_000
        p.isPlaying.value = false // paused from the lock screen
        runCurrent()
        assertFalse(p.released) // waits out an error that may follow the pause
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(p.released)
        assertEquals(listOf("A" to 8_000L), records())
    }

    @Test
    fun sourceErrorRefreshesUrlAndResumesAtSavedPosition() = runTest {
        val s = session()
        s.open(bookA, factory)
        runCurrent()
        val p = factory.last
        p.positionMs.value = 3_600_000
        runCurrent()
        p.error.value = sourceError
        p.isPlaying.value = false
        p.positionMs.value = 0 // the player resets its position on error
        runCurrent()
        assertEquals(2, api.prepareCalls.size)
        val tail = p.calls.takeLast(2)
        assertEquals(Call.ReplaceItem(item(bookA, PlaybackSource.Url(url("A", 2))), 3_600_000), tail[0])
        assertEquals(Call.Play, tail[1])
        assertEquals(listOf("A" to 3_600_000L), records())
        assertEquals(Phase.Ready, s.state.value.phase)
    }

    @Test
    fun repeatedIdenticalSourceErrorsBackOff() = runTest {
        val s = session()
        s.open(bookA, factory)
        runCurrent()
        val p = factory.last
        p.positionMs.value = 1_000
        runCurrent()
        p.autoPrepare = false
        p.prepareFails(sourceError) // and every reload keeps the identical error: no new emission
        runCurrent()
        assertEquals(1, p.replaces().size) // first error: immediate refresh
        for (d in listOf(2_000L, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000)) {
            assertEquals(Phase.Reconnecting, s.state.value.phase)
            val before = p.replaces().size
            advanceTimeBy(1_000 + d - 1) // 1 s to see the attempt fail, then the backoff
            runCurrent()
            assertEquals(before, p.replaces().size, "waited less than $d")
            advanceTimeBy(1)
            runCurrent()
            assertEquals(before + 1, p.replaces().size, "after $d")
            assertEquals(1_000, p.replaces().last().startMs)
        }
        assertEquals(9, api.prepareCalls.size)
        p.prepareSucceeds()
        runCurrent()
        assertEquals(Phase.Ready, s.state.value.phase)
        assertTrue(s.state.value.isPlaying)
        advanceTimeBy(120_000)
        assertEquals(8, p.replaces().size) // the loop stopped
        p.prepareFails(sourceError)
        runCurrent()
        assertEquals(9, p.replaces().size) // backoff reset: immediate again
    }

    @Test
    fun recoverySucceedsOnceTheReloadPrepares() = runTest {
        val s = session()
        s.open(bookA, factory)
        runCurrent()
        val p = factory.last
        p.positionMs.value = 3_600_000
        runCurrent()
        p.autoPrepare = false
        p.prepareFails(sourceError)
        p.positionMs.value = 0
        runCurrent()
        assertEquals(Call.ReplaceItem(item(bookA, PlaybackSource.Url(url("A", 2))), 3_600_000), p.replaces().single())
        assertEquals(Call.Play, p.calls.last())
        assertEquals(Phase.Reconnecting, s.state.value.phase)
        assertFalse(s.state.value.isPlaying)
        advanceTimeBy(500)
        p.prepareSucceeds()
        runCurrent()
        assertEquals(Phase.Ready, s.state.value.phase)
        assertTrue(s.state.value.isPlaying)
        assertEquals(3_600_000, s.state.value.positionMs)
        advanceTimeBy(30_000)
        assertEquals(1, p.replaces().size)
    }

    @Test
    fun identicalErrorAfterAMissedClearIsStillRecovered() = runTest {
        val s = session()
        s.open(bookA, factory)
        runCurrent()
        val p = factory.last
        p.autoPrepare = false
        p.prepareFails(sourceError)
        runCurrent()
        assertEquals(1, p.replaces().size)
        p.isPlaying.value = true // playing again, but the collector never saw the error clear
        runCurrent()
        assertEquals(Phase.Ready, s.state.value.phase)
        p.isPlaying.value = false // fails again with the identical (unemitted) error
        runCurrent()
        assertEquals(1, p.replaces().size)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, p.replaces().size)
        assertEquals(Phase.Reconnecting, s.state.value.phase)
    }

    @Test
    fun detachedSourceErrorAfterPauseRecoversInsteadOfReleasing() = runTest {
        val s = session()
        s.open(bookA, factory)
        runCurrent()
        val p = factory.last
        p.positionMs.value = 20_000
        runCurrent()
        s.detach()
        p.autoPrepare = false
        p.isPlaying.value = false // the pause lands before the error
        runCurrent()
        p.error.value = sourceError
        runCurrent()
        assertEquals(1, p.replaces().size)
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(p.released)
        p.prepareSucceeds()
        runCurrent()
        assertTrue(s.state.value.isPlaying)
        advanceTimeBy(5_000)
        assertFalse(p.released)
        s.togglePlay()
        advanceTimeBy(1_001)
        assertTrue(p.released)
        assertEquals(20_000, p.replaces().last().startMs)
    }

    @Test
    fun loadFinishingWhileDetachedReleasesIfPlayNeverStarts() = runTest {
        resume["A"] = 5_000
        factory.next = { FakePlayback().apply { autoPrepare = false } }
        val s = session()
        s.open(bookA, factory)
        s.detach() // left the Player screen while preparing
        runCurrent()
        val p = factory.last
        assertTrue(p.calls.any { it is Call.SetItem })
        advanceTimeBy(29_000)
        assertFalse(p.released)
        advanceTimeBy(1_001)
        assertTrue(p.released)
        assertEquals(listOf("A" to 5_000L), records())
    }

    @Test
    fun loadFinishingWhileDetachedKeepsHandleOncePlaying() = runTest {
        val s = session()
        s.open(bookA, factory)
        s.detach()
        runCurrent()
        advanceTimeBy(60_000)
        assertFalse(factory.last.released)
        assertTrue(s.state.value.isPlaying)
    }

    @Test
    fun userPauseDuringRecoveryWinsAndDetachReleases() = runTest {
        val s = session()
        s.open(bookA, factory)
        runCurrent()
        val p = factory.last
        p.positionMs.value = 40_000
        runCurrent()
        p.autoPrepare = false
        p.prepareFails(sourceError)
        runCurrent()
        assertEquals(Phase.Reconnecting, s.state.value.phase)
        val replaces = p.replaces().size
        val plays = p.calls.count { it == Call.Play }
        s.togglePlay() // user pauses while reconnecting
        runCurrent()
        s.detach()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(replaces, p.replaces().size)
        assertEquals(plays, p.calls.count { it == Call.Play })
        assertTrue(p.released)
        assertEquals("A" to 40_000L, records().last())
    }

    @Test
    fun userPlayAfterPausedRecoveryReloadsFreshSource() = runTest {
        val s = session()
        s.open(bookA, factory)
        runCurrent()
        val p = factory.last
        p.positionMs.value = 40_000
        runCurrent()
        p.autoPrepare = false
        p.prepareFails(sourceError)
        runCurrent()
        s.togglePlay()
        runCurrent()
        val prepares = api.prepareCalls.size
        s.togglePlay() // play again
        runCurrent()
        assertEquals(prepares + 1, api.prepareCalls.size)
        assertEquals(40_000, p.replaces().last().startMs)
        assertEquals(Phase.Reconnecting, s.state.value.phase)
    }

    @Test
    fun seekTimeoutReportedAsSourceErrorReloadsAtSavedPosition() = runTest {
        // LightPlayback reports SEEK_TIMEOUT (keeping the requested start as the position) when a load
        // never accepts its start; the session must reload at that position rather than play from 0.
        val s = session()
        s.open(bookA, factory)
        runCurrent()
        val p = factory.last
        p.positionMs.value = 90_000
        runCurrent()
        p.isPlaying.value = false
        p.error.value = PlaybackError(isSource = true, diagnostic = SEEK_TIMEOUT)
        runCurrent()
        assertEquals(90_000, p.replaces().single().startMs)
        assertEquals("A" to 90_000L, records().last())
        assertTrue(records().none { it.second == 0L })
    }

    @Test
    fun nonSourceErrorFailsWithoutRetry() = runTest {
        val s = session()
        s.open(bookA, factory)
        runCurrent()
        val p = factory.last
        p.error.value = PlaybackError(isSource = false, diagnostic = "ERROR_CODE_DECODING_FAILED")
        p.isPlaying.value = false
        runCurrent()
        advanceTimeBy(120_000)
        assertEquals(Phase.Failed("Couldn't play this book"), s.state.value.phase)
        assertEquals(1, api.prepareCalls.size)
        assertTrue(p.replaces().isEmpty())
    }

    @Test
    fun preparingPhases() = runTest {
        api.prepareResult = { ApiResult.Ok(BookState(BookStatus.Preparing)) }
        api.bookResult = { ApiResult.Ok(BookState(BookStatus.Preparing)) }
        val s = session()
        s.open(bookA, factory)
        runCurrent()
        assertEquals(Phase.Preparing, s.state.value.phase)
        assertEquals("A", s.state.value.asin)
        assertTrue(factory.last.calls.none { it is Call.SetItem })
    }

    @Test
    fun needsSignInPhase() = runTest {
        api.prepareResult = { ApiResult.NeedsSignIn }
        val s = session()
        s.open(bookA, factory)
        runCurrent()
        assertEquals(Phase.NeedsSignIn, s.state.value.phase)
    }

    @Test
    fun failedPreparationThenRetry() = runTest {
        api.prepareResult = { ApiResult.Ok(BookState(BookStatus.Failed, error = "ffmpeg")) }
        val s = session()
        s.open(bookA, factory)
        runCurrent()
        assertEquals(Phase.Failed("Couldn't prepare this book"), s.state.value.phase)
        api.prepareResult = { ApiResult.Ok(BookState(BookStatus.Ready, url(it, 9), "2030-01-01T06:00:00Z")) }
        s.retry()
        runCurrent()
        assertEquals(2, api.prepareCalls.size)
        assertEquals(Phase.Ready, s.state.value.phase)
        assertEquals(PlaybackSource.Url(url("A", 9)), factory.last.calls.filterIsInstance<Call.SetItem>().single().item.source)
    }

    @Test
    fun refusedHandleFails() = runTest {
        factory.next = { FakePlayback().apply { ready = false } }
        val s = session()
        s.open(bookA, factory)
        runCurrent()
        assertEquals(Phase.Failed("Couldn't play this book"), s.state.value.phase)
        assertTrue(factory.last.released)
    }

    @Test
    fun skipSeeksToClampedTarget() = runTest {
        val s = session()
        s.open(bookA, factory)
        runCurrent()
        val p = factory.last
        p.durationMs.value = 60_000
        p.positionMs.value = 50_000
        runCurrent()
        s.skip(30_000)
        assertEquals(Call.SeekTo(60_000), p.calls.last())
        s.skip(-30_000)
        assertEquals(Call.SeekTo(30_000), p.calls.last())
    }

    @Test
    fun skipTargetClamps() {
        assertEquals(0, skipTarget(5_000, 60_000, -30_000))
        assertEquals(60_000, skipTarget(55_000, 60_000, 30_000))
        assertEquals(35_000, skipTarget(5_000, 0, 30_000))
        assertEquals(0, skipTarget(5_000, 0, -30_000))
    }
}
