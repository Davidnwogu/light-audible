package com.davidnwogu.lightaudible.playback

import com.davidnwogu.lightaudible.store.ToolStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The saved playback rate. Main-thread only; [rate] is 1f until the saved value has loaded. */
class SpeedSetting(private val store: ToolStore, private val scope: CoroutineScope) {
    private val _rate = MutableStateFlow(1f)
    val rate: StateFlow<Float> = _rate.asStateFlow()

    init {
        scope.launch {
            val saved = store.playbackSpeed()
            _rate.value = if (saved in RATES) saved else 1f
        }
    }

    /** Moves to the next rate in [RATES], wrapping, and saves it. */
    fun cycle() {
        val next = RATES[(RATES.indexOf(_rate.value) + 1) % RATES.size]
        _rate.value = next
        scope.launch { store.savePlaybackSpeed(next) }
    }

    companion object {
        val RATES = listOf(1f, 1.25f, 1.5f, 2f)
    }
}
