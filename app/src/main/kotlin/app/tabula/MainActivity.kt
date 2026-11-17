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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import app.tabula.core.Accent
import app.tabula.core.AiPause
import app.tabula.core.AiRouter
import app.tabula.core.AppEntry
import app.tabula.core.AppSearch
import app.tabula.core.CapAction
import app.tabula.core.Corners
import app.tabula.core.LauncherStyle
import app.tabula.core.LocalCommand
import app.tabula.core.LocalCommandParser
import app.tabula.core.ModelTier
import app.tabula.core.Palette
import app.tabula.core.SettingKind
import app.tabula.core.Theme
import app.tabula.core.WordFont
import app.tabula.core.WordSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import java.text.DecimalFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal val Mono = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)

@OptIn(ExperimentalTextApi::class)
internal val Grotesk = FontFamily(
    Font(R.font.space_grotesk, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

private val Serif = FontFamily(Font(R.font.instrument_serif, FontWeight.Normal))

/** Everything on screen reads its colours, font and sizes from here, so Settings changes apply live. */
internal data class Look(
    val bg: Color, val text: Color, val muted: Color, val faint: Color, val accent: Color,
    val words: FontFamily, val wordWeight: FontWeight, val wordSp: TextUnit, val radius: Dp,
) {
    companion object {
        fun of(style: LauncherStyle): Look {
            val p = Palette.of(style)
            val (family, weight) = when (style.font) {
                WordFont.MONO -> Mono to FontWeight.Bold
                WordFont.GROTESK -> Grotesk to FontWeight.SemiBold
                WordFont.SERIF -> Serif to FontWeight.Normal
            }
            return Look(
                Color(p.background), Color(p.text), Color(p.muted), Color(p.faint), Color(p.accent),
                family, weight, style.size.sp.sp, style.corners.searchBarRadiusDp.dp,
            )
        }
    }
}

internal val LocalLook = staticCompositionLocalOf { Look.of(LauncherStyle.DEFAULT) }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("style", Context.MODE_PRIVATE) }
            var style by remember { mutableStateOf(LauncherStyle.decode(prefs.all.mapValues { it.value as? String })) }
            val look = Look.of(style)
            LaunchedEffect(style.theme) {
                val bar = if (style.theme == Theme.PAPER) {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
            }
            CompositionLocalProvider(LocalLook provides look) {
                Launcher(style) { new ->
                    style = new
                    prefs.edit { new.encode().forEach { (k, v) -> putString(k, v) } }
                }
            }
        }
    }
}

internal enum class Mode { INTRO, HOME, DRAWER, SETTINGS, PRO, AI }

@Composable
private fun Launcher(style: LauncherStyle, onStyle: (LauncherStyle) -> Unit) {
    val context = LocalContext.current
    val look = LocalLook.current
    val locale = LocalConfiguration.current.locales[0]
    val apps = remember { loadApps(context) }
    val pins = remember { Pins(context, apps) }
    var query by remember { mutableStateOf("") }
    val introPrefs = remember { context.getSharedPreferences("intro", Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(if (introPrefs.getBoolean("done", false)) Mode.HOME else Mode.INTRO) }
    var answer by remember { mutableStateOf<String?>(null) }
    var aiLine by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = query.isNotEmpty() || (mode != Mode.HOME && mode != Mode.INTRO) || answer != null) {
        query = ""; answer = null
        mode = if (mode == Mode.PRO || mode == Mode.AI) Mode.SETTINGS else Mode.HOME
    }

    Column(
        Modifier.fillMaxSize().background(look.bg).systemBarsPadding().imePadding().padding(horizontal = 24.dp),
    ) {
        when (mode) {
            Mode.INTRO -> { Intro { introPrefs.edit { putBoolean("done", true) }; mode = Mode.HOME }; return@Column }
            Mode.SETTINGS -> { SettingsScreen(style, onStyle, onPro = { mode = Mode.PRO }, onAi = { mode = Mode.AI }, onIntro = { mode = Mode.INTRO }, onClose = { mode = Mode.HOME }); return@Column }
            Mode.AI -> { AiScreen(onClose = { mode = Mode.SETTINGS }); return@Column }
            Mode.PRO -> { ProScreen(onClose = { mode = Mode.SETTINGS }); return@Column }
            else -> Unit
        }
        Spacer(Modifier.height(24.dp))
        Clock()
        Spacer(Modifier.height(28.dp))

        val list = when {
            query.isNotBlank() -> AppSearch.search(apps, query)
            mode == Mode.DRAWER -> AppSearch.search(apps, "")
            else -> pins.entries
        }
        val home = mode == Mode.HOME && query.isBlank()
        LazyColumn(Modifier.weight(1f)) {
            if (home && list.isEmpty()) item { Label("OPEN ALL AND LONG-PRESS AN APP TO PIN IT") }
            items(list, key = { it.id }) { app ->
                AppWord(
                    label = app.label.uppercase(locale),
                    size = if (home) look.wordSp else 22.sp,
                    pinned = mode == Mode.DRAWER && pins.isPinned(app),
                    onOpen = { launch(context, app); query = ""; mode = Mode.HOME },
                    onLongPress = { pins.toggle(app) },
                )
            }
        }

        answer?.let {
            Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                SelectionContainer { BasicText(it, style = TextStyle(fontFamily = Mono, fontSize = 15.sp, lineHeight = 22.sp, color = look.text)) }
                aiLine?.let { line -> Label(line, Modifier.padding(top = 8.dp)) }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TapLabel(if (mode == Mode.DRAWER) "← HOME" else "ALL") {
                mode = if (mode == Mode.DRAWER) Mode.HOME else Mode.DRAWER; query = ""
            }
            TapLabel("SETTINGS") { mode = Mode.SETTINGS; query = "" }
        }

        SearchBar(query, onChange = { query = it; answer = null; aiLine = null }) {
            val cmd = LocalCommandParser.parse(query)
            if (cmd is LocalCommand.AskAi && cmd.text.isNotBlank()) {
                // Not something the phone can do: ask the user's AI (their own keys), or stay phone-only.
                answer = "…"; aiLine = "THINKING"
                scope.launch {
                    val reply = withContext(Dispatchers.IO) { runCatching { AiClient(context).ask(cmd.text) }.getOrNull() }
                    answer = reply?.text ?: AiPause.LINE
                    aiLine = reply?.let { "${it.by} · ${it.cost}" }
                }
            } else {
                aiLine = null
                answer = runLocal(context, query)
                if (answer == null) query = ""
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsScreen(style: LauncherStyle, onStyle: (LauncherStyle) -> Unit, onPro: () -> Unit, onAi: () -> Unit, onIntro: () -> Unit, onClose: () -> Unit) {
    val look = LocalLook.current
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("pro", Context.MODE_PRIVATE) }
    var proUnlocked by remember { mutableStateOf(prefs.getBoolean("unlocked", false)) }
    var taps by remember { mutableIntStateOf(0) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Spacer(Modifier.height(16.dp))
            TapLabel("← HOME", onTap = onClose)
            Title("SETTINGS")
        }
        item {
            Section("ACCENT · ${style.accent.label.uppercase()}")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Accent.entries.forEach { a ->
                    val colour = Color(a.argb ?: Palette.of(style).text)
                    Box(
                        Modifier.size(44.dp)
                            .border(2.dp, if (a == style.accent) look.text else Color.Transparent, CircleShape)
                            .padding(6.dp).background(colour, CircleShape)
                            .combinedClickable(onClickLabel = a.label, onClick = { onStyle(style.copy(accent = a)) }),
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)); TapLabel("AI & KEYS →", color = look.text, onTap = onAi) }
        item { Choice("THEME", Theme.entries, style.theme) { onStyle(style.copy(theme = it)) } }
        item { Choice("WORD FONT", WordFont.entries, style.font) { onStyle(style.copy(font = it)) } }
        item { Choice("WORD SIZE", WordSize.entries, style.size) { onStyle(style.copy(size = it)) } }
        item { Choice("CORNERS", Corners.entries, style.corners) { onStyle(style.copy(corners = it)) } }
        item {
            Section("PREVIEW")
            BasicText("MESSAGES", style = TextStyle(fontFamily = look.words, fontWeight = look.wordWeight, fontSize = look.wordSp, color = look.text))
            Spacer(Modifier.height(16.dp))
            TapLabel("RESET TO DEFAULT") { onStyle(LauncherStyle.DEFAULT) }
        }
        if (proUnlocked) item { Spacer(Modifier.height(8.dp)); TapLabel("PRO · LOCAL AI SERVER →", color = look.accent, onTap = onPro) }
        item {
            Spacer(Modifier.height(24.dp))
            // Hidden: tap the version 7 times to show Pro, like Android's developer options.
            TapLabel("SHOW THE INTRODUCTION AGAIN", onTap = onIntro)
            TapLabel("TABULA ${BuildConfig.VERSION_NAME}", color = look.faint) {
                taps++
                if (taps >= 7 && !proUnlocked) { proUnlocked = true; prefs.edit { putBoolean("unlocked", true) } }
            }
            Label(
                when {
                    proUnlocked -> "PRO IS UNLOCKED"
                    taps in 3..6 -> "${7 - taps} MORE TAPS TO UNLOCK PRO"
                    else -> "TAP THE VERSION 7 TIMES TO UNLOCK PRO"
                },
                color = look.faint,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun <E : Enum<E>> Choice(title: String, options: List<E>, selected: E, onPick: (E) -> Unit) {
    val look = LocalLook.current
    Section(title)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier.weight(1f).height(48.dp)
                    .background(if (on) look.text else Color.Transparent, RoundedCornerShape(look.radius / 4))
                    .border(1.dp, if (on) look.text else look.faint, RoundedCornerShape(look.radius / 4))
                    .combinedClickable(onClick = { onPick(o) }),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(o.name, style = TextStyle(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.sp, color = if (on) look.bg else look.muted))
            }
        }
    }
}

@Composable
internal fun Title(text: String) = BasicText(
    text, Modifier.padding(vertical = 12.dp),
    style = TextStyle(fontFamily = Grotesk, fontSize = 44.sp, color = LocalLook.current.text, letterSpacing = (-1).sp),
)

@Composable
internal fun Section(text: String) {
    Spacer(Modifier.height(20.dp))
    Label(text, Modifier.padding(bottom = 10.dp))
}

@Composable
private fun Clock() {
    val look = LocalLook.current
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
        style = TextStyle(fontFamily = Grotesk, fontSize = 96.sp, color = look.text, letterSpacing = (-4).sp),
    )
    Label(now.format(DateTimeFormatter.ofPattern("EEE d MMM", locale)).uppercase(locale))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppWord(label: String, size: TextUnit, pinned: Boolean, onOpen: () -> Unit, onLongPress: () -> Unit) {
    val look = LocalLook.current
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLongPress).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = TextStyle(fontFamily = look.words, fontWeight = look.wordWeight, fontSize = size, color = look.text, letterSpacing = 1.sp))
        if (pinned) {
            Spacer(Modifier.size(10.dp))
            Box(Modifier.size(6.dp).background(look.accent, CircleShape))
        }
    }
}

@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit, onGo: () -> Unit) {
    val look = LocalLook.current
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(fontFamily = Mono, fontSize = 15.sp, color = look.text),
        cursorBrush = SolidColor(look.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().height(56.dp).border(1.dp, look.text, RoundedCornerShape(look.radius)).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).background(look.accent, CircleShape))
                Spacer(Modifier.size(12.dp))
                Box {
                    if (query.isEmpty()) BasicText("ask or search…", style = TextStyle(fontFamily = Mono, fontSize = 15.sp, color = look.muted))
                    inner()
                }
            }
        },
    )
}

@Composable
internal fun Label(text: String, modifier: Modifier = Modifier, color: Color = LocalLook.current.muted) =
    BasicText(text, modifier, style = TextStyle(fontFamily = Mono, fontSize = 12.sp, color = color, letterSpacing = 2.sp))

@Composable
internal fun TapLabel(text: String, color: Color = LocalLook.current.muted, onTap: () -> Unit) =
    Label(text, Modifier.combinedClickable(onClick = onTap).padding(vertical = 14.dp), color = color)

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
