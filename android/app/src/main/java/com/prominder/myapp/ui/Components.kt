package com.prominder.myapp.ui

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prominder.myapp.R
import com.prominder.myapp.ui.theme.Palette

/** A rounded card. */
@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.Card, contentColor = Palette.Text),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** A small uppercase section heading. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
        color = Palette.Muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
    )
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
}

/** A top bar with an optional back button. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back), color = Palette.Muted) }
        } else {
            Spacer(Modifier.width(8.dp))
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

/** A coloured dot: green when done, amber when not. */
@Composable
fun StatusDot(done: Boolean) {
    Text(if (done) "✓" else "!", color = if (done) Palette.Ok else Palette.Accent, fontWeight = FontWeight.Bold,
        modifier = Modifier.size(24.dp), fontSize = 18.sp)
}
