package com.prominder.myapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prominder.myapp.R
import com.prominder.myapp.ui.theme.Palette

/** A rounded card. */
@Composable
fun AppCard(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.Card, contentColor = Palette.Text),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** A small uppercase section heading. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
        color = Palette.Muted,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
    )
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
}

/**
 * A text link in a bar or a card (Edit, Change, ＋ New): plain text colour, semi-bold. Amber is kept for buttons that
 * act (see Theme.kt).
 */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Palette.Text, enabled: Boolean = true) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp)) {
        Text(text, color = if (enabled) color else Palette.Muted, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** An icon button (48dp to touch), with the words TalkBack reads. A [badge] dot says "something to see in here". */
@Composable
fun AppIconButton(
    icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Palette.Text,
    badge: Boolean = false, enabled: Boolean = true,
) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp), enabled = enabled) {
        Box {
            Icon(icon, contentDescription = label, tint = if (enabled) tint else Palette.Line)
            if (badge) Box(Modifier.align(Alignment.TopEnd).offset(5.dp, (-3).dp).size(9.dp).clip(CircleShape).background(Palette.Accent))
        }
    }
}

/** A top bar with an optional back arrow (it points the right way in Hebrew too). */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onBack)
        } else {
            Spacer(Modifier.width(12.dp))
        }
        // One line, whatever the language and font size: smaller before it would wrap ("ProMinde / r").
        val style = MaterialTheme.typography.titleLarge
        Text(
            title, style = style, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false,
            overflow = TextOverflow.Ellipsis, autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = style.fontSize),
        )
        actions()
    }
}

/** A setup row's state: a green check when done, an amber warning when not. */
@Composable
fun StatusDot(done: Boolean) {
    Icon(
        if (done) Icons.Filled.CheckCircle else Icons.Filled.Warning,
        contentDescription = stringResource(if (done) R.string.cd_done else R.string.cd_not_done),
        tint = if (done) Palette.Ok else Palette.Accent,
        modifier = Modifier.size(22.dp),
    )
}

/**
 * A short answer to a tap ("Done ✓: Taxes"), at the bottom of every screen for a few seconds. [undo] puts things back as
 * they were (✓ Done, Later and Cancel offer it, since one tap is easy to make by mistake).
 */
data class Notice(val text: String, val undo: (suspend () -> Unit)? = null)

/** How [Notice] shows: a snackbar with Undo when there's one. */
@Composable
fun NoticeBar(notice: Notice, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    Snackbar(
        modifier = modifier.padding(horizontal = 12.dp),
        containerColor = Palette.Text,
        contentColor = Palette.Bg,
        action = notice.undo?.let {
            { TextButton(onClick = onUndo) { Text(stringResource(R.string.undo), color = Palette.Bg, fontWeight = FontWeight.Bold) } }
        },
    ) { Text(notice.text, style = MaterialTheme.typography.bodyMedium) }
}
