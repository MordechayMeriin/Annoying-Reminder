package com.prominder.myapp.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/**
 * "Sign in with Google" through Android's Credential Manager. Google Play services shows the account
 * sheet and talks to Google itself, so the app never contacts Google directly.
 *
 * [serverClientId] is the OAuth client id of type "Web application": Google addresses the ID token to
 * it, and the backend only accepts tokens addressed to it. Google also checks that this app (package
 * name + signing certificate) is registered as an "Android" client in the same Google Cloud project.
 */
object GoogleSignIn {
    /**
     * Shows Google's account sheet and returns the chosen account's ID token, or null when you closed the sheet.
     * [context] must be an Activity (the sheet opens over it).
     */
    suspend fun idToken(context: Context, serverClientId: String): String? {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(serverClientId).build())
            .build()
        val result = try {
            CredentialManager.create(context).getCredential(context, request)
        } catch (e: GetCredentialCancellationException) {
            return null
        } catch (e: NoCredentialException) {
            throw AuthException("There's no Google account on this phone. Add one in Settings → Accounts, then try again.", cause = e)
        } catch (e: GetCredentialException) {
            throw AuthException(describe(e), cause = e)
        }
        val credential = result.credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            try {
                return GoogleIdTokenCredential.createFrom(credential.data).idToken
            } catch (e: GoogleIdTokenParsingException) {
                throw AuthException("Google sent an answer the app doesn't understand.", cause = e)
            }
        }
        throw AuthException("Google sent an answer the app doesn't understand.")
    }

    /** After logging out: forget the chosen account, so the next sign-in asks which one again. */
    suspend fun forget(context: Context) {
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }

    internal fun describe(e: GetCredentialException): String {
        val detail = listOfNotNull(e.type.substringAfterLast('.'), e.errorMessage?.toString()).joinToString(": ")
        // Google's "developer error" (28444 / 10): the Android client in Google Cloud is missing, or its
        // package name or SHA-1 doesn't match this build.
        val setup = detail.contains("28444") || detail.contains("Developer console", ignoreCase = true) ||
            Regex("""\b10:""").containsMatchIn(detail)
        return if (setup) {
            "Google refused: this build isn't registered in Google Cloud. Check the Android OAuth client's " +
                "package name and SHA-1 (see android/README.md).\n($detail)"
        } else {
            "Google sign-in didn't work. Try again.\n($detail)"
        }
    }
}
