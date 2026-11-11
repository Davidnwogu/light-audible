package com.davidnwogu.lightaudible

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.davidnwogu.lightaudible.api.AuthState
import com.davidnwogu.lightaudible.api.OkHttpServerApi
import com.davidnwogu.lightaudible.api.ServerApi
import com.davidnwogu.lightaudible.api.ServerClock
import com.davidnwogu.lightaudible.playback.PlaybackSession
import com.davidnwogu.lightaudible.playback.SpeedSetting
import com.davidnwogu.lightaudible.store.DataStoreToolStore
import com.davidnwogu.lightaudible.store.ToolStore
import com.davidnwogu.lightaudible.sync.ChapterCache
import com.davidnwogu.lightaudible.sync.Downloads
import com.davidnwogu.lightaudible.sync.Preparer
import com.davidnwogu.lightaudible.sync.PositionSync
import java.util.concurrent.TimeUnit
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** Process-wide object graph. */
class AppGraph(dataStore: DataStore<Preferences>, val filesDir: File) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store: ToolStore = DataStoreToolStore(dataStore)
    val auth = AuthState(store)

    // Read once at creation so the first request already uses the stored offset.
    val clock = ServerClock(
        deviceNowMs = System::currentTimeMillis,
        initialOffsetMs = runBlocking(Dispatchers.IO) { store.clockOffsetMs() },
        onOffsetChanged = { offset -> scope.launch { store.saveClockOffsetMs(offset) } },
    )
    val api: ServerApi = OkHttpServerApi(OkHttpClient(), store::credentials, clock, auth)
    val positions = PositionSync(api, store, clock)
    val preparer = Preparer(api)
    val chapters = ChapterCache(api)

    init {
        scope.launch { positions.flush() } // reports queued while offline in an earlier run
    }

    // Presigned URLs: no bearer token, no overall call timeout (books are large).
    private val downloadHttp = OkHttpClient.Builder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    val downloads = Downloads(filesDir, downloadHttp, preparer, scope)
    val speed = SpeedSetting(store, scope)
    val playback = PlaybackSession(positions, preparer, downloads, speed.rate, scope) { clock.now().toEpochMilli() }
}

object Graph {
    @Volatile
    private var instance: AppGraph? = null

    fun get(dataStore: DataStore<Preferences>, filesDir: File): AppGraph =
        instance ?: synchronized(this) {
            instance ?: AppGraph(dataStore, filesDir).also { instance = it }
        }
}

/** Lowercases scheme and host, drops any path. Returns null unless the result is a valid https URL. */
fun normalizeServerUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val url = withScheme.toHttpUrlOrNull() ?: return null
    if (url.scheme != "https") return null
    val port = if (url.port == 443) "" else ":${url.port}"
    return "https://${url.host}$port"
}
