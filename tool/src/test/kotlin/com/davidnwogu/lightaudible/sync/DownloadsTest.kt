package com.davidnwogu.lightaudible.sync

import com.davidnwogu.lightaudible.api.ApiResult
import com.davidnwogu.lightaudible.api.BookState
import com.davidnwogu.lightaudible.api.BookStatus
import com.davidnwogu.lightaudible.fakes.FakeServerApi
import com.davidnwogu.lightaudible.fakes.ScriptedInterceptor
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsTest {
    private val dir = kotlin.io.path.createTempDirectory("dl").toFile()
    private val api = FakeServerApi().apply {
        prepareResult = { ApiResult.Ok(BookState(BookStatus.Ready, "https://cdn.example/a.m4b", "2030-01-01T00:00:00Z")) }
    }
    private val net = ScriptedInterceptor()
    private val http = OkHttpClient.Builder().addInterceptor(net).build()

    private fun downloads() = Downloads(
        dir, http, Preparer(api, 5_000), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined,
    )

    @Test
    fun successWritesFileAndNoPart() {
        val body = ByteArray(100_000) { (65 + it % 26).toByte() }
        net.enqueue(200, String(body, Charsets.US_ASCII))
        val d = downloads()
        d.start("B1")
        assertTrue(d.isDownloaded("B1"))
        assertFalse(File(dir, "B1.m4b.part").exists())
        assertEquals(100_000L, d.file("B1").length())
        assertFalse("Authorization" in net.requests.single().headers)
        assertTrue(d.progress.value.isEmpty())
    }

    @Test
    fun droppedConnectionLeavesNothingAndEmitsFailure() = runTest {
        net.enqueueFailure(IOException("dropped"))
        val d = downloads()
        val seen = mutableListOf<Pair<String, String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { d.failures.collect { seen += it } }
        d.start("B1")
        assertFalse(d.file("B1").exists())
        assertFalse(File(dir, "B1.m4b.part").exists())
        assertEquals(1, seen.size)
        assertEquals("B1", seen[0].first)
        assertFalse(d.isDownloaded("B1"))
    }

    @Test
    fun midBodyDropLeavesNothing() = runTest {
        net.enqueuePartialThenFail(ByteArray(5000), IOException("reset"))
        val d = downloads()
        val seen = mutableListOf<Pair<String, String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { d.failures.collect { seen += it } }
        d.start("B1")
        assertFalse(d.file("B1").exists())
        assertFalse(File(dir, "B1.m4b.part").exists())
        assertEquals(1, seen.size)
    }

    @Test
    fun leftoverPartIsNotDownloaded() {
        File(dir, "B1.m4b.part").writeBytes(byteArrayOf(1))
        assertFalse(downloads().isDownloaded("B1"))
    }

    @Test
    fun removeDeletesBoth() {
        File(dir, "B1.m4b.part").writeBytes(byteArrayOf(1))
        File(dir, "B1.m4b").writeBytes(byteArrayOf(1))
        val d = downloads()
        d.remove("B1")
        assertFalse(File(dir, "B1.m4b.part").exists())
        assertFalse(d.isDownloaded("B1"))
    }

    @Test
    fun contentMatches() {
        net.enqueue(200, "hello")
        val d = downloads()
        d.start("B2")
        assertContentEquals("hello".toByteArray(), d.file("B2").readBytes())
    }
}
