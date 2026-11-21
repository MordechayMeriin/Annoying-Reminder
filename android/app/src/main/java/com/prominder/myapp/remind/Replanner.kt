package com.prominder.myapp.remind

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs [plan] after changes, once per burst. Every change asks for a re-plan ([request]), but a chat turn with five
 * tool calls, or a tick that writes a few rows, is one burst: the requests are conflated (a pending one absorbs the
 * next), and [settle] waits for the burst to end. One collector, so two plans never run at once; a request that comes
 * in while a plan runs gets one more plan after it.
 */
class Replanner(scope: CoroutineScope, private val settle: Long = 300, private val plan: suspend () -> Unit) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in requests) {
                delay(settle)
                requests.tryReceive()   // asked again while settling: this plan covers it
                plan()
            }
        }
    }

    fun request() {
        requests.trySend(Unit)
    }
}
