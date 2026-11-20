package com.prominder.myapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prominder.myapp.logic.Logic
import com.prominder.myapp.ui.theme.Palette
import kotlinx.coroutines.delay

/** The running focus session, as the timer shows it. Times in epoch milliseconds. */
data class SessionUi(
    val title: String,
    /** The next action (or the title when there's none). */
    val step: String,
    /** "Where did you stop?" from last time, if answered. */
    val resumeNote: String?,
    val plannedMin: Int,
    val startedAt: Long,
    val endsAt: Long,
    /** en | he: the buttons' language, as the chips'. */
    val lang: String = "en",
)

/** A focus session that's running while its screen is closed: "⏱ 12:34 · Taxes" above the chat. */
data class SessionBarUi(val title: String, val endsAt: Long, val lang: String = "en")

/** "Where did you stop?" after a stopped or unanswered session: optional, never in the way. */
data class BreadcrumbUi(val sessionId: Long, val title: String, val lang: String = "en")

/** 12:34, or 0:00 at the end. Seconds rounded up, so the last second shows 0:01 and never a premature 0:00. */
internal fun clockText(ms: Long): String {
    val sec = maxOf(0L, (ms + 999) / 1000)
    return "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
}

internal fun timesUp(lang: String) = if (lang == "he") "הזמן נגמר" else "Time's up"

/** The clock, once a second while shown. Screenshot tests pass a fixed [fixed] instead. */
@Composable
internal fun rememberTicker(fixed: Long? = null): Long {
    if (fixed != null) return fixed
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L - System.currentTimeMillis() % 1000L)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * The focus timer, full screen (the web app's session screen): a ring that empties as time runs, the next action,
 * where you stopped last time, one tap for 5 / 15 / 25 / 45 minutes, and ✓ Done · +5 · Stop. When the time is up the
 * ring closes in green (a finished round, not a failure) and the buttons become ✓ Done · ▶ Another round · Stop.
 * ✕ only hides it: the session keeps running (the countdown notification and the bar above the chat show it).
 */
@Composable
fun SessionScreen(
    ui: SessionUi,
    now: Long,
    busy: Boolean = false,
    onDone: () -> Unit,
    onStop: () -> Unit,
    onAnother: () -> Unit,
    onLength: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val left = ui.endsAt - now
    val over = left <= 0
    val total = ui.plannedMin * 60_000L
    val he = ui.lang == "he"
    Column(Modifier.fillMaxSize().background(Palette.Bg).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(ui.title, color = Palette.Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("✕", color = Palette.Muted, fontSize = 20.sp) }
        }
        Spacer(Modifier.weight(0.5f))
        Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
            val fraction = if (over) 1f else (left.toFloat() / total).coerceIn(0f, 1f)
            Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                val stroke = 14.dp.toPx()
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(stroke / 2, stroke / 2)
                drawArc(Palette.Line, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                drawArc(if (over) Palette.Ok else Palette.Accent, -90f, 360f * fraction, false, topLeft, arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text(if (over) timesUp(ui.lang) else clockText(left), fontSize = if (over) 34.sp else 60.sp, fontWeight = FontWeight.Bold,
                color = if (over) Palette.Ok else Palette.Text)
        }
        Spacer(Modifier.height(20.dp))
        Text("▶ ${ui.step}", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        ui.resumeNote?.let {
            Spacer(Modifier.height(8.dp))
            Text((if (he) "עצרת ב: " else "You stopped at: ") + it, color = Palette.Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        if (!over) {
            val elapsed = now - ui.startedAt
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in Logic.SESSION_CHOICES) {
                    val active = m == ui.plannedMin
                    val enabled = !busy && (active || m * 60_000L > elapsed)
                    Box(
                        Modifier.clip(RoundedCornerShape(18.dp)).background(if (active) Palette.Accent else Palette.Card2)
                            .clickable(enabled = enabled && !active) { onLength(m) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text("$m", color = when {
                            active -> Palette.Bg
                            enabled -> Palette.Text
                            else -> Palette.Line
                        }, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val done = Logic.chipLabel("done", ui.lang)
        val stop = Logic.chipLabel("stop", ui.lang)
        Button(onClick = onDone, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Ok, contentColor = Palette.Bg)) {
            Text(done, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (over) {
                Button(onClick = onAnother, enabled = !busy, modifier = Modifier.weight(1f).height(52.dp)) {
                    Text(Logic.chipLabel("another", ui.lang), fontSize = 16.sp)
                }
            } else {
                OutlinedButton(onClick = { onLength(minOf(ui.plannedMin + 5, Logic.SESSION_MAX_MIN)) }, enabled = !busy && ui.plannedMin < Logic.SESSION_MAX_MIN,
                    modifier = Modifier.weight(1f).height(52.dp)) { Text("+5", fontSize = 16.sp) }
            }
            OutlinedButton(onClick = onStop, enabled = !busy, modifier = Modifier.weight(1f).height(52.dp)) { Text(stop, fontSize = 16.sp) }
        }
    }
}

/** Above the chat while a session runs with its screen closed. Tap to open the timer again. */
@Composable
fun SessionBar(bar: SessionBarUi, now: Long, onOpen: () -> Unit) {
    val left = bar.endsAt - now
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Card2).clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⏱ " + (if (left > 0) clockText(left) else timesUp(bar.lang)), color = if (left > 0) Palette.Accent else Palette.Ok,
            fontWeight = FontWeight.Bold)
        Text(" · ${bar.title}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("›", color = Palette.Muted)
    }
}
