package com.davidnwogu.lightaudible.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.davidnwogu.lightaudible.api.AuthState

/** Every non-Setup screen calls this and navigates to Setup when the token is lost. */
@Composable
fun SignedOutWatcher(auth: AuthState, onSignedOut: () -> Unit) {
    val signedOut by auth.signedOut.collectAsState()
    val current by rememberUpdatedState(onSignedOut)
    LaunchedEffect(signedOut) {
        if (signedOut) current()
    }
}
