package com.prominder.myapp.remind

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReplannerTest {
    @Test
    fun `a burst of changes is one plan`() = runTest {
        val scope = TestScope(testScheduler)
        var plans = 0
        val r = Replanner(scope, settle = 300) { plans++ }
        repeat(5) { r.request(); advanceTimeBy(50) }   // a chat turn's five tool calls
        scope.advanceUntilIdle()
        assertEquals(1, plans)
        scope.cancel()
    }

    @Test
    fun `a change during a plan gets one more plan after it`() = runTest {
        val scope = TestScope(testScheduler)
        var plans = 0
        val r = Replanner(scope, settle = 300) { delay(1000); plans++ }
        r.request()
        advanceTimeBy(500)          // the plan is running
        repeat(3) { r.request() }
        scope.advanceUntilIdle()
        assertEquals(2, plans)
        scope.cancel()
    }
}
