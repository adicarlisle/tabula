package app.tabula

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val pages = listOf(
    "TABULA" to "A calm home screen. Your apps are words, nothing more.",
    "PIN" to "Open ALL and long-press any app to pin it to your home screen. Long-press again to unpin.",
    "SEARCH" to "Type to find an app. Press enter for timers, alarms, sums, wifi and the torch. All of it works on the phone, with no AI.",
    "MAKE IT YOURS" to "SETTINGS changes the colour, theme, font, word size and corners.",
    "PRO" to "Pro is hidden to keep things simple. In SETTINGS, tap the version number 7 times to unlock it. It sets up AI on your own computer.",
)

/** First-run introduction. Shown once; can be replayed from Settings. */
@Composable
internal fun Intro(onDone: () -> Unit) {
    val look = LocalLook.current
    var page by remember { mutableIntStateOf(0) }
    val (title, body) = pages[page]
    Column(Modifier.fillMaxSize().padding(vertical = 32.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            Spacer(Modifier.height(48.dp))
            Title(title)
            BasicText(body, style = TextStyle(fontFamily = Mono, fontSize = 17.sp, lineHeight = 27.sp, color = look.text))
        }
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pages.indices.forEach { i ->
                    Box(Modifier.size(8.dp).background(if (i == page) look.accent else look.faint, CircleShape))
                }
            }
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier.fillMaxWidth().height(56.dp).background(look.text, RoundedCornerShape(look.radius))
                    .combinedClickable(onClick = { if (page < pages.lastIndex) page++ else onDone() }),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    if (page < pages.lastIndex) "NEXT" else "START",
                    style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp, color = look.bg),
                )
            }
            if (page < pages.lastIndex) TapLabel("SKIP", onTap = onDone)
        }
    }
}
