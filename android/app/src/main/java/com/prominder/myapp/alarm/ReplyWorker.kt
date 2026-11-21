package com.prominder.myapp.alarm

import com.prominder.myapp.R
import com.prominder.myapp.i18n.AppLanguage
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.prominder.myapp.ReminderApp
import com.prominder.myapp.diag.Diag
import com.prominder.myapp.notify.Notifier

/**
 * A reply typed into a notification, sent to the assistant like a chat message. It runs as a background job rather
 * than in the notification's receiver, which Android stops after a few seconds; a turn can take longer. The answer
 * comes as a notification, or in the chat when the app is open.
 */
class ReplyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ReminderApp
        val text = inputData.getString(KEY_TEXT)?.trim().orEmpty()
        if (text.isEmpty() || !app.store.hasData()) return Result.success()
        val (result, error) = app.chat.sendAndWait(text)
        if (!app.visible) {
            if (result != null) Notifier.showReply(app, result.reply, result.replyMessageId)
            else Notifier.showReply(app, error ?: AppLanguage.localized(app).getString(R.string.notif_no_reply), System.currentTimeMillis())
        }
        return Result.success()
    }

    companion object {
        private const val KEY_TEXT = "text"

        fun enqueue(context: Context, text: String) {
            val request = OneTimeWorkRequestBuilder<ReplyWorker>().setInputData(workDataOf(KEY_TEXT to text)).build()
            runCatching { WorkManager.getInstance(context).enqueue(request) }
                .onFailure { Diag.log("notify", "Couldn't send the reply: ${it.message}") }
        }
    }
}
