package com.davidnwogu.lightaudible.ui

import com.davidnwogu.lightaudible.api.ApiResult
import com.davidnwogu.lightaudible.api.AuthState
import com.davidnwogu.lightaudible.api.Credentials
import com.davidnwogu.lightaudible.fakes.FakeServerApi
import com.davidnwogu.lightaudible.fakes.InMemoryToolStore
import com.davidnwogu.lightaudible.normalizeServerUrl
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

class NormalizeServerUrlTest {
    @Test
    fun cases() {
        assertEquals("https://light-audible-production.up.railway.app", normalizeServerUrl("light-audible-production.up.railway.app"))
        assertEquals("https://host", normalizeServerUrl("https://host/"))
        assertEquals("https://host", normalizeServerUrl("https://host/setup/"))
        assertEquals("https://host", normalizeServerUrl("https://host/api"))
        assertEquals("https://host", normalizeServerUrl(" HTTPS://Host "))
        assertNull(normalizeServerUrl("http://host"))
        assertNull(normalizeServerUrl(""))
        assertNull(normalizeServerUrl("not a url"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {
    private val store = InMemoryToolStore()
    private val api = FakeServerApi()
    private val auth = AuthState(store)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(): SetupViewModel = SetupViewModel(store, api, auth).also { advanceUntilIdle() }

    private fun SetupViewModel.form() = state.value as SetupUiState.Form

    @Test
    fun existingCredentialsArePairedWithoutNetwork() = runTest {
        store.saveCredentials(Credentials("https://host", "tok"))
        val vm = vm()
        assertEquals(SetupUiState.Paired, vm.state.value)
        assertTrue(api.pairCalls.isEmpty())
    }

    @Test
    fun noCredentialsShowsFormPrefilledWithStoredUrl() = runTest {
        store.saveCredentials(Credentials("https://host", "tok"))
        store.clearToken()
        val form = vm().form()
        assertEquals("https://host", form.serverUrl)
        assertEquals("", form.code)
        assertNull(form.error)
    }

    @Test
    fun badCodeShowsErrorAndMakesNoCall() = runTest {
        val vm = vm()
        vm.setServerUrl("host")
        vm.setCode("12345")
        vm.submit()
        advanceUntilIdle()
        assertEquals("Enter the 6-digit code from your setup page.", vm.form().error)
        assertTrue(api.pairCalls.isEmpty())
    }

    @Test
    fun invalidCode() = runTest {
        api.pairResult = { _, _ -> ApiResult.InvalidCode }
        val vm = vm()
        vm.setServerUrl("host")
        vm.setCode("123456")
        vm.submit()
        advanceUntilIdle()
        assertEquals("That code didn't work. Make a new one on your setup page.", vm.form().error)
        assertEquals(false, vm.form().busy)
    }

    @Test
    fun unreachable() = runTest {
        api.pairResult = { _, _ -> ApiResult.Failed(null) }
        val vm = vm()
        vm.setServerUrl("host")
        vm.setCode("123456")
        vm.submit()
        advanceUntilIdle()
        assertEquals("Can't reach the server. Check the address.", vm.form().error)
    }

    @Test
    fun badUrlShowsAddressError() = runTest {
        val vm = vm()
        vm.setServerUrl("http://host")
        vm.setCode("123456")
        vm.submit()
        advanceUntilIdle()
        assertEquals("Can't reach the server. Check the address.", vm.form().error)
        assertTrue(api.pairCalls.isEmpty())
    }

    @Test
    fun successSavesCredentialsResetsAuthAndPairs() = runTest {
        auth.lost()
        api.pairResult = { _, _ -> ApiResult.Ok("tok123") }
        val vm = vm()
        vm.setServerUrl("Host/setup")
        vm.setCode("12 34 56")
        vm.submit()
        advanceUntilIdle()
        assertEquals(listOf("https://host" to "123456"), api.pairCalls)
        assertEquals(Credentials("https://host", "tok123"), store.credentials())
        assertEquals(false, auth.signedOut.value)
        assertEquals(SetupUiState.Paired, vm.state.value)
    }
}
