package app.tabula

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tabula.core.ModelTier
import app.tabula.core.ProviderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings > AI & keys: bring your own keys. Stored encrypted on the phone; only the last 4 characters are ever shown. */
@Composable
internal fun AiScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val look = LocalLook.current
    val scope = rememberCoroutineScope()
    val keys = remember { AiKeys(context) }
    val client = remember { AiClient(context) }
    var refresh by remember { mutableIntStateOf(0) }
    val drafts = remember { mutableStateMapOf<ProviderKind, String>() }
    val notes = remember { mutableStateMapOf<ProviderKind, String>() }
    var busy by remember { mutableStateOf<ProviderKind?>(null) }

    @Suppress("UNUSED_EXPRESSION") refresh // re-read keys after saving or removing

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Spacer(Modifier.height(16.dp))
            TapLabel("← SETTINGS", onTap = onClose)
            Title("AI & KEYS")
            Label("THIS MONTH · $" + "%.2f".format(keys.monthSpend()))
            Note(
                "Search tries these in order: your own local AI first, then each provider you've added a key for. " +
                    "If one is out of credit, the next is used. With none working, search stays phone-only.",
            )
            val order = keys.chain()
            if (order.isNotEmpty()) Label("ORDER · " + order.joinToString(" → ") { it.label.uppercase() }, color = look.text)
        }

        item {
            Section("MY LOCAL AI")
            if (!keys.localAvailable()) {
                Note("Set up a server in Pro, or join one with a QR code, to use your own AI for free.")
            } else {
                val on = keys.localEnabled()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("Use my local AI first", Modifier.weight(1f), style = TextStyle(fontFamily = Mono, fontSize = 14.sp, color = look.text))
                    TapLabel(if (on) "ON" else "OFF", color = if (on) look.accent else look.muted) { keys.setLocal(!on); refresh++ }
                }
                Note("Model: " + keys.model(ProviderKind.LOCAL, ModelTier.QUICK).ifBlank { "pick one in Pro > Your models" })
            }
        }

        ProviderKind.entries.filter { it != ProviderKind.LOCAL }.forEach { kind ->
            item(key = kind.name) {
                val saved = keys.key(kind)
                Section(kind.label.uppercase() + if (saved != null) " · " + ProviderKind.masked(saved) else " · NO KEY")
                Field(drafts[kind] ?: "", if (saved != null) "paste a new key to replace it" else "paste your ${kind.label} API key", secret = true) {
                    drafts[kind] = it; notes.remove(kind)
                }
                Spacer(Modifier.height(8.dp))
                var quick by remember(refresh) { mutableStateOf(keys.model(kind, ModelTier.QUICK)) }
                var deep by remember(refresh) { mutableStateOf(keys.model(kind, ModelTier.DEEP)) }
                Label("QUICK MODEL")
                Field(quick, kind.quick) { quick = it; keys.setModel(kind, ModelTier.QUICK, it) }
                Spacer(Modifier.height(4.dp))
                Label("DEEP MODEL")
                Field(deep, kind.deep) { deep = it; keys.setModel(kind, ModelTier.DEEP, it) }
                Spacer(Modifier.height(10.dp))
                val draft = drafts[kind]?.trim().orEmpty()
                Button(
                    when {
                        busy == kind -> "CHECKING…"
                        draft.isNotEmpty() -> "TEST AND SAVE KEY"
                        saved != null -> "TEST SAVED KEY"
                        else -> "ADD A KEY ABOVE"
                    },
                    enabled = busy == null && (draft.isNotEmpty() || saved != null),
                ) {
                    val key = draft.ifEmpty { saved!! }
                    if (draft.isNotEmpty() && !kind.keyLooksRight(draft)) {
                        notes[kind] = "That doesn't look like a ${kind.label} key. Check you copied the right one."
                        return@Button
                    }
                    busy = kind
                    scope.launch {
                        val problem = withContext(Dispatchers.IO) { client.test(kind, key, keys.model(kind, ModelTier.QUICK)) }
                        if (problem == null && draft.isNotEmpty()) { keys.saveKey(kind, draft); drafts.remove(kind) }
                        notes[kind] = problem ?: if (draft.isNotEmpty()) "Works. Saved." else "Works."
                        busy = null; refresh++
                    }
                }
                notes[kind]?.let { BasicText(it, style = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 18.sp, color = look.accent)) }
                if (saved != null) TapLabel("REMOVE KEY", color = look.faint) { keys.removeKey(kind); notes[kind] = "Removed."; refresh++ }
            }
        }

        item {
            Spacer(Modifier.height(24.dp))
            BasicText(
                "Keys never leave this phone except to go to their own provider, over HTTPS.",
                style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 16.sp, color = look.faint),
            )
            Spacer(Modifier.height(48.dp))
        }
    }
}
