package com.prominder.myapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prominder.myapp.ui.theme.Palette

data class LoginState(
    val busy: Boolean = false,
    val message: String? = null,
    /** False when the build has no Google client id or server address (see android/README.md). */
    val configured: Boolean = true,
)

/** One button: Google shows its account sheet, and the server lets in only the owner's account. */
@Composable
fun LoginScreen(
    state: LoginState,
    onSignIn: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        AppCard {
            Text("🔔 ProMinder", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            if (!state.configured) {
                Text(
                    "This build has no login settings, so it can't log in. " +
                        "Set GOOGLE_WEB_CLIENT_ID for the build (see android/README.md).",
                    color = Palette.Danger,
                )
                return@AppCard
            }
            Button(onClick = onSignIn, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Sign in with Google")
            }
            if (state.busy) {
                Spacer(Modifier.height(12.dp))
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            }
            state.message?.let {
                Spacer(Modifier.height(12.dp))
                Muted(it)
            }
        }
    }
}
