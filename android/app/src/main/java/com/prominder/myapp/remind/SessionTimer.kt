package com.prominder.myapp.remind

import android.content.Context
import com.prominder.myapp.data.Store
import com.prominder.myapp.data.currentSession
import com.prominder.myapp.logic.Logic
import com.prominder.myapp.notify.Notifier

/**
 * Keeps the focus timer's countdown notification in step with the database: shown while a session runs, gone once
 * its time is up (the tick's end check-in takes over) or it's closed. Called after every change and every tick.
 */
object SessionTimer {
    suspend fun refresh(context: Context, store: Store) {
        val open = store.currentSession()
        val now = store.now()
        val end = open?.session?.let(Logic::sessionEndAt)
        if (open == null || end == null || now >= end) {
            Notifier.cancelSession(context)
            return
        }
        val task = open.task
        val title = task?.title ?: "Focus session"
        val step = task?.nextAction?.takeIf { it.isNotEmpty() } ?: title
        val lang = Logic.chipLang(store.dao.latestOfKindsBy(listOf("chat"), "user")?.content, title)
        Notifier.showSession(context, Notifier.SessionInfo(open.session.id, step, title, end.toEpochMilli(), lang), now.toEpochMilli())
    }
}
