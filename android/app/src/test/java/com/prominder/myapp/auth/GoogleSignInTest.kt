package com.prominder.myapp.auth

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSignInTest {
    @Test
    fun `closing the sheet yourself stays silent`() {
        assertNull(GoogleSignIn.cancellationMessage("activity is cancelled by the user."))
        assertNull(GoogleSignIn.cancellationMessage(null))
    }

    @Test
    fun `google refusing after the account was chosen is explained`() {
        val m = GoogleSignIn.cancellationMessage("[16] Account reauth failed.")!!
        assertTrue(m, m.contains("SHA-1"))
        assertTrue(m, m.contains("[16] Account reauth failed."))
    }

    @Test
    fun `any other cancellation shows its reason`() {
        assertTrue(GoogleSignIn.cancellationMessage("something else")!!.contains("something else"))
    }
}
