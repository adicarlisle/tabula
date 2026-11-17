package app.tabula

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tabula.core.AiPause
import app.tabula.core.AiRouter
import app.tabula.core.AppEntry
import app.tabula.core.AppSearch
import app.tabula.core.CapAction
import app.tabula.core.LocalCommand
import app.tabula.core.LocalCommandParser
import app.tabula.core.ModelTier
import app.tabula.core.SettingKind
import kotlinx.coroutines.delay
import java.text.DecimalFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val Ink = Color(0xFF0A0A0A)
private val Paper = Color(0xFFF2F2F2)
private val Muted = Color(0xFFA3A3A3)
private val Faint = Color(0xFF5A5A5A)
private val Accent = Color(0xFFFF4A3D)

private val Mono = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)

@OptIn(ExperimentalTextApi::class)
private val Grotesk = FontFamily(
    Font(R.font.space_grotesk, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light status and navigation icons on Tabula's black background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { Launcher() }
    }
}

private enum class Mode { HOME, DRAWER }

@Composable
private fun Launcher() {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val apps = remember { loadApps(context) }
    val pins = remember { Pins(context, apps) }
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(Mode.HOME) }
    var answer by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = query.isNotEmpty() || mode != Mode.HOME || answer != null) {
        query = ""; answer = null; mode = Mode.HOME
    }

    Column(
        Modifier.fillMaxSize().background(Ink).systemBarsPadding().imePadding().padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Clock()
        Spacer(Modifier.height(28.dp))

        val list = when {
            query.isNotBlank() -> AppSearch.search(apps, query)
            mode == Mode.DRAWER -> AppSearch.search(apps, "")
            else -> pins.entries
        }
        val wordSize = if (mode == Mode.HOME && query.isBlank()) 34.sp else 22.sp
        LazyColumn(Modifier.weight(1f)) {
            if (mode == Mode.HOME && query.isBlank() && list.isEmpty()) {
                item { Label("OPEN ALL AND LONG-PRESS AN APP TO PIN IT") }
            }
            items(list, key = { it.id }) { app ->
                AppWord(
                    label = app.label.uppercase(locale),
                    size = wordSize,
                    pinned = mode == Mode.DRAWER && pins.isPinned(app),
                    onOpen = { launch(context, app); query = ""; mode = Mode.HOME },
                    onLongPress = { pins.toggle(app) },
                )
            }
        }

        answer?.let { Label(it, color = Paper, modifier = Modifier.padding(vertical = 8.dp)) }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Label(
                if (mode == Mode.DRAWER) "← HOME" else "ALL",
                modifier = Modifier.combinedClickable(onClick = { mode = if (mode == Mode.DRAWER) Mode.HOME else Mode.DRAWER; query = "" }).padding(vertical = 12.dp),
            )
        }

        SearchBar(query, onChange = { query = it; answer = null }) {
            answer = runLocal(context, query)
            if (answer == null) query = ""
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Clock() {
    val locale = LocalConfiguration.current.locales[0]
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay((60 - now.second) * 1000L)
        }
    }
    BasicText(
        now.format(DateTimeFormatter.ofPattern("HH:mm")),
        style = TextStyle(fontFamily = Grotesk, fontSize = 96.sp, color = Paper, letterSpacing = (-4).sp),
    )
    Label(now.format(DateTimeFormatter.ofPattern("EEE d MMM", locale)).uppercase(locale))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppWord(label: String, size: androidx.compose.ui.unit.TextUnit, pinned: Boolean, onOpen: () -> Unit, onLongPress: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLongPress).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = size, color = Paper, letterSpacing = 1.sp))
        if (pinned) {
            Spacer(Modifier.size(10.dp))
            Box(Modifier.size(6.dp).background(Accent, CircleShape))
        }
    }
}

@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit, onGo: () -> Unit) {
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(fontFamily = Mono, fontSize = 15.sp, color = Paper),
        cursorBrush = SolidColor(Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().height(56.dp).border(1.dp, Paper, RoundedCornerShape(28.dp)).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).background(Accent, CircleShape))
                Spacer(Modifier.size(12.dp))
                Box {
                    if (query.isEmpty()) BasicText("ask or search…", style = TextStyle(fontFamily = Mono, fontSize = 15.sp, color = Muted))
                    inner()
                }
            }
        },
    )
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier, color: Color = Muted) =
    BasicText(text, modifier, style = TextStyle(fontFamily = Mono, fontSize = 12.sp, color = color, letterSpacing = 2.sp))

/** Pinned apps, in order, saved on the phone. First run pins the first five apps A to Z. */
private class Pins(context: Context, private val apps: List<AppEntry>) {
    private val prefs = context.getSharedPreferences("pins", Context.MODE_PRIVATE)
    private val ids = mutableStateListOf<String>().apply {
        val saved = prefs.getString("ids", null)
        addAll(saved?.split('\n')?.filter { it.isNotEmpty() } ?: AppSearch.search(apps, "").take(5).map { it.id })
    }

    val entries: List<AppEntry> get() = ids.mapNotNull { id -> apps.firstOrNull { it.id == id } }

    fun isPinned(app: AppEntry) = app.id in ids

    fun toggle(app: AppEntry) {
        if (!ids.remove(app.id)) ids.add(app.id)
        prefs.edit { putString("ids", ids.joinToString("\n")) }
    }
}

private fun loadApps(context: Context): List<AppEntry> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val pm = context.packageManager
    return pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName != context.packageName }
        .map { AppEntry(it.loadLabel(pm).toString(), "${it.activityInfo.packageName}/${it.activityInfo.name}") }
        .distinctBy { it.id }
}

private fun launch(context: Context, app: AppEntry) {
    val (pkg, cls) = app.id.split('/', limit = 2)
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(ComponentName(pkg, cls))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { }
}

/** Runs a typed command on the phone. Returns a line to show, or null when handed to another app. */
private fun runLocal(context: Context, text: String): String? {
    fun start(intent: Intent): String? = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); null
    } catch (_: ActivityNotFoundException) { "NO APP CAN DO THAT" }

    return when (val cmd = LocalCommandParser.parse(text)) {
        is LocalCommand.Timer -> start(
            Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, cmd.seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, false),
        )
        is LocalCommand.Alarm -> start(
            Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, cmd.hour).putExtra(AlarmClock.EXTRA_MINUTES, cmd.minute),
        )
        is LocalCommand.Calculate -> "= " + DecimalFormat("#,##0.########").format(cmd.result)
        is LocalCommand.Setting -> when (cmd.kind) {
            SettingKind.WIFI -> start(Intent(Settings.Panel.ACTION_WIFI))
            SettingKind.BLUETOOTH -> start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            SettingKind.TORCH -> torch(context, cmd.on)
        }
        is LocalCommand.AskAi -> if (cmd.text.isBlank()) null else {
            // No keys yet, so the router says phone-only: the same quiet line as offline or out of credit.
            AiPause.line(AiRouter(emptyList(), cap = null, onCap = CapAction.QUICK_ONLY).route(ModelTier.QUICK, 0.0))
        }
    }
}

private fun torch(context: Context, on: Boolean): String? {
    val cm = context.getSystemService(CameraManager::class.java)
    val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        ?: return "NO TORCH ON THIS PHONE"
    cm.setTorchMode(id, on)
    return null
}
