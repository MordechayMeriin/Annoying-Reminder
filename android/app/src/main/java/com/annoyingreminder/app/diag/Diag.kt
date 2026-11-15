package com.annoyingreminder.app.diag

import android.content.Context
import android.util.Log
import java.io.File

/** The app-wide [DiagLog]. Set up once in ReminderApp; safe to use from receivers and the UI. */
object Diag {
    @Volatile private var instance: DiagLog? = null

    fun init(context: Context) {
        if (instance == null) {
            instance = DiagLog(File(context.filesDir, "diagnostics.log"), echo = { Log.i("Reminder", it) })
        }
    }

    val log: DiagLog
        get() = checkNotNull(instance) { "Diag.init() wasn't called" }

    /** Before init (unit tests) it just prints. */
    fun log(tag: String, message: String) = instance?.log(tag, message) ?: println("[$tag] $message")
}
