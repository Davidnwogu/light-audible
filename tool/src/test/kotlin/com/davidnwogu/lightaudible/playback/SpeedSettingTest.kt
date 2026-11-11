package com.davidnwogu.lightaudible.playback

import com.davidnwogu.lightaudible.fakes.InMemoryToolStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SpeedSettingTest {
    private val store = InMemoryToolStore()

    private fun TestScope.setting() = SpeedSetting(store, backgroundScope)

    @Test
    fun cycleWalksRatesAndWraps() = runTest(UnconfinedTestDispatcher()) {
        val s = setting()
        assertEquals(1f, s.rate.value)
        for (expected in listOf(1.25f, 1.5f, 2f, 1f)) {
            s.cycle()
            assertEquals(expected, s.rate.value)
        }
        assertEquals(1f, store.playbackSpeed())
        s.cycle()
        assertEquals(1.25f, store.playbackSpeed())
    }

    @Test
    fun loadsSavedRate() = runTest(UnconfinedTestDispatcher()) {
        store.savePlaybackSpeed(1.5f)
        assertEquals(1.5f, setting().rate.value)
    }

    @Test
    fun unknownSavedRateFallsBackTo1() = runTest(UnconfinedTestDispatcher()) {
        store.savePlaybackSpeed(1.7f)
        assertEquals(1f, setting().rate.value)
    }
}
