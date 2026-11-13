package com.annoyingreminder.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.annoyingreminder.app.ui.theme.Palette

data class LoginState(
    val email: String = "",
    val code: String = "",
    /** False: asking for the email. True: the code was sent, asking for it. */
    val codeSent: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    /** False when the build has no Supabase URL / key (see android/README.md). */
    val configured: Boolean = true,
)

/** Log in with the code Supabase emails you: the same account and the same flow as the web app. */
@Composable
fun LoginScreen(
    state: LoginState,
    onEmailChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onSendCode: () -> Unit,
    onVerify: () -> Unit,
    onUseOtherEmail: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        AppCard {
            Text("🔔 Annoying Reminder", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            if (!state.configured) {
                Text(
                    "This build has no Supabase settings, so it can't log in. " +
                        "Set SUPABASE_URL and SUPABASE_ANON_KEY for the build (see android/README.md).",
                    color = Palette.Danger,
                )
                return@AppCard
            }
            if (!state.codeSent) {
                OutlinedTextField(
                    value = state.email,
                    onValueChange = onEmailChange,
                    label = { Text("Email") },
                    singleLine = true,
                    enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSendCode() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onSendCode, enabled = !state.busy && state.email.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text("Send login code")
                }
            } else {
                Muted("We emailed a code to ${state.email}.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.code,
                    onValueChange = onCodeChange,
                    label = { Text("Code from the email") },
                    singleLine = true,
                    enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onVerify() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onVerify, enabled = !state.busy && state.code.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text("Log in")
                }
                TextButton(onClick = onUseOtherEmail, enabled = !state.busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Use a different email, or send a new code", color = Palette.Muted)
                }
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
