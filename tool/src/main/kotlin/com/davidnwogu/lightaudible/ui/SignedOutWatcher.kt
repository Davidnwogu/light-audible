package com.davidnwogu.lightaudible.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.davidnwogu.lightaudible.api.AuthState

/**
 * Every non-Setup screen calls this. When the token is lost, [onSignedOut] should be the screen's
 * `goBack()` so the stack unwinds to the root Setup screen, which then shows the pairing form.
 */
@Composable
fun SignedOutWatcher(auth: AuthState, onSignedOut: () -> Unit) {
    val signedOut by auth.signedOut.collectAsState()
    val current by rememberUpdatedState(onSignedOut)
    LaunchedEffect(signedOut) {
        if (signedOut) current()
    }
}
