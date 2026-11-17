package app.tabula

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import app.tabula.core.DeviceKey
import app.tabula.core.Gateway
import app.tabula.core.HfDecision
import app.tabula.core.HfModelId
import app.tabula.core.HfPolicy
import app.tabula.core.KnownHosts
import app.tabula.core.Pairing
import app.tabula.core.ServerAddress
import app.tabula.core.ServerCheck
import app.tabula.core.ServerKind
import app.tabula.core.ServerScripts
import app.tabula.core.SetupProgress
import app.tabula.core.SetupState
import app.tabula.core.TargetPolicy
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.jcraft.jsch.Session
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
private const val COMPOSE = "~/.tabula/gateway/compose.yaml"

private enum class Step { SERVER, TRUST, SCRIPT, RUNNING, MODELS, JOINED }

/**
 * Pro: set up Ollama (in Docker, behind a key gateway) or vLLM on the user's own Linux machine over SSH,
 * install Hugging Face models onto it, and share access with other devices by QR code.
 */
@Composable
internal fun ProScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val look = LocalLook.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("pro", Context.MODE_PRIVATE) }
    val secrets = remember { Secrets(context) }
    val known = remember { KnownHosts.decode(prefs.getString("known_hosts", "") ?: "") }

    var addressText by remember { mutableStateOf(prefs.getString("address", "") ?: "") }
    val saved = remember { ServerAddress.parse(addressText) }
    var host by remember { mutableStateOf(saved?.host ?: "") }
    var user by remember { mutableStateOf(saved?.user ?: "") }
    var password by remember { mutableStateOf("") }
    var permission by remember { mutableStateOf(false) }
    val found = remember { mutableStateListOf<FoundServer>() }
    var kind by remember { mutableStateOf(runCatching { ServerKind.valueOf(prefs.getString("kind", "OLLAMA")!!) }.getOrDefault(ServerKind.OLLAMA)) }
    var step by remember {
        mutableStateOf(
            when {
                prefs.getBoolean("joined", false) -> Step.JOINED
                prefs.getBoolean("installing", false) -> Step.RUNNING
                prefs.getBoolean("ready", false) -> Step.MODELS
                else -> Step.SERVER
            },
        )
    }
    var fingerprint by remember { mutableStateOf("") }
    val checkLines = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val log = remember { mutableStateListOf<String>() }
    var showDetails by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<Session?>(null) }

    var hfToken by remember { mutableStateOf(secrets.get("hf-token")?.decodeToString() ?: "") }
    var query by remember { mutableStateOf("") }
    val hits = remember { mutableStateListOf<HfApi.Hit>() }
    var picked by remember { mutableStateOf<Pair<HfModelId, HfDecision>?>(null) }
    val models = remember { mutableStateListOf<String>() }
    var model by remember { mutableStateOf(prefs.getString("model", null)) }
    val devices = remember { mutableStateListOf<DeviceKey>().apply { addAll(DeviceKey.decodeAll(secrets.get("gw-devices")?.decodeToString() ?: "")) } }
    var newDevice by remember { mutableStateOf("") }
    var qr by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true; message = null
        scope.launch {
            try { block() } catch (e: Exception) { message = e.message ?: e.javaClass.simpleName } finally { busy = false }
        }
    }

    var pendingLan by remember { mutableStateOf<(() -> Unit)?>(null) }
    val askLan = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingLan?.invoke()
        else message = "Tabula needs the Nearby devices permission to reach your computer on this network. You can turn it on in Settings > Apps > Tabula > Permissions."
        pendingLan = null
    }

    /** Android 17+ asks the user before an app can reach devices on the home network. */
    fun withLan(action: () -> Unit) {
        val needed = Build.VERSION.SDK_INT >= 37 && context.checkSelfPermission(LOCAL_NETWORK) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needed) { pendingLan = action; askLan.launch(LOCAL_NETWORK) } else action()
    }

    fun address() = ServerAddress.of(host, saved?.takeIf { it.host == host }?.port ?: 22, user)

    suspend fun keySession(): Session {
        val addr = ServerAddress.parse(addressText) ?: throw IllegalStateException("No server saved")
        val key = secrets.get("ssh-key") ?: throw IllegalStateException("No phone key saved. Set the server up again.")
        return withContext(Dispatchers.IO) { Ssh(known).connect(addr, privateKey = key) }
    }

    suspend fun <T> withKeySession(block: (Session) -> T): T {
        val s = keySession()
        try { return withContext(Dispatchers.IO) { block(s) } } finally { s.disconnect() }
    }

    fun gateway(): GatewayClient {
        val gwHost = prefs.getString("gw_host", null) ?: throw IllegalStateException("Gateway not ready yet")
        val ca = prefs.getString("gw_ca", null) ?: throw IllegalStateException("Gateway certificate missing")
        val key = secrets.get("gw-key")?.decodeToString() ?: throw IllegalStateException("No key for this device")
        return GatewayClient.fromPairing(Pairing(gwHost, Gateway.PORT, key, ca))
    }

    /** After setup: read the gateway's address and its CA certificate over SSH (a channel we already trust). */
    suspend fun fetchGateway() {
        val out = withKeySession { s ->
            StringBuilder().also { Ssh.readText(s, "cat ~/.tabula/gateway/.env; docker compose -f $COMPOSE exec -T gateway cat /data/caddy/pki/authorities/local/root.crt", it) }.toString()
        }
        val gwHost = out.lines().firstOrNull { it.startsWith("TABULA_HOST=") }?.removePrefix("TABULA_HOST=")?.trim()
            ?: throw IllegalStateException("Gateway address not found")
        val pem = out.substring(out.indexOf("-----BEGIN CERTIFICATE-----"))
        val ca = GatewayClient.parsePem(pem)
        prefs.edit { putString("gw_host", gwHost); putString("gw_ca", GatewayClient.caBase64(ca)) }
    }

    suspend fun refreshModels() {
        models.clear()
        if (kind == ServerKind.OLLAMA) models.addAll(withContext(Dispatchers.IO) { gateway().models() })
    }

    /** Writes the device keys to the server and reloads the gateway. Keys go over SSH stdin, never on a command line. */
    suspend fun pushKeys(list: List<DeviceKey>) {
        withKeySession { s ->
            val ssh = Ssh(known)
            ssh.run(s, "umask 077 && cat > ~/.tabula/gateway/keys", DeviceKey.encodeAll(list).toByteArray())
            ssh.run(s, "umask 077 && cat > ~/.tabula/gateway/Caddyfile", Gateway.caddyfile(list).toByteArray())
            ssh.run(s, "docker compose -f $COMPOSE exec -T gateway caddy reload --config /etc/caddy/Caddyfile")
        }
        secrets.put("gw-devices", DeviceKey.encodeAll(list).toByteArray())
        devices.clear(); devices.addAll(list)
    }

    /** Connect with the password, check the host key, install this phone's own SSH key, then look at what's there. */
    fun firstConnect() = work {
        val addr = address() ?: throw IllegalArgumentException("Pick a server and enter your username on it")
        // Resolve once, check it is on a private network, and connect to exactly that address.
        val ip = withContext(Dispatchers.IO) { InetAddress.getByName(addr.host).hostAddress ?: "" }
        if (!TargetPolicy.allowed(ip)) throw IllegalArgumentException("Tabula only sets up computers on your own home or office network.")
        val target = addr.copy(host = ip)
        val guard = loginGuard(prefs)
        if (!guard.mayTry(ip, System.currentTimeMillis())) {
            throw IllegalStateException("Too many wrong passwords. Try again in ${guard.secondsLocked(ip, System.currentTimeMillis()) / 60 + 1} min.")
        }
        addressText = "${target.user}@${target.host}:${target.port}"
        prefs.edit { putString("address", addressText); putString("kind", kind.name) }
        val ssh = Ssh(known)
        try {
            val s = withContext(Dispatchers.IO) {
                try {
                    ssh.connect(target, password = password.toCharArray())
                } catch (e: com.jcraft.jsch.JSchException) {
                    if (e.message?.contains("Auth", ignoreCase = true) == true) {
                        recordFailure(prefs, ip)
                        throw IllegalStateException("Wrong username or password.")
                    }
                    throw e
                }
            }
            clearFailures(prefs, ip)
            val (prv, pub) = withContext(Dispatchers.IO) { ssh.newKeyPair() }
            val report = withContext(Dispatchers.IO) {
                ssh.run(s, "umask 077; mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys", (pub + "\n").toByteArray())
                val out = StringBuilder()
                ssh.run(s, "bash -s", ServerCheck.SCRIPT.toByteArray()) { out.appendLine(it) }
                ServerCheck.parse(out.toString())
            }
            secrets.put("ssh-key", prv)
            checkLines.clear(); checkLines.addAll(report.summary())
            session = s
            step = Step.SCRIPT
        } catch (e: UnknownHostKey) {
            fingerprint = e.fingerprint; step = Step.TRUST
        }
    }

    /** Follows the setup log on the server, reconnecting with the phone's key if the connection drops. */
    suspend fun follow() {
        var ks: Session? = null
        try {
            while (true) {
                val text = try {
                    if (ks?.isConnected != true) ks = keySession()
                    withContext(Dispatchers.IO) { StringBuilder().also { Ssh.readText(ks!!, ServerScripts.progress(), it) }.toString() }
                } catch (_: Exception) { ks = null; null }
                if (text != null) {
                    log.clear(); log.addAll(text.lines().filter { it.isNotBlank() })
                    when (SetupState.of(text)) {
                        SetupState.DONE -> {
                            prefs.edit { putBoolean("ready", true); putBoolean("installing", false) }
                            ks?.disconnect(); ks = null
                            if (kind == ServerKind.OLLAMA) { fetchGateway(); runCatching { refreshModels() } }
                            step = Step.MODELS
                            return
                        }
                        SetupState.FAILED -> { prefs.edit { putBoolean("installing", false) }; message = "Setup stopped with an error. Open the details to see why."; return }
                        SetupState.RUNNING -> Unit
                    }
                }
                delay(3_000)
            }
        } finally { ks?.disconnect() }
    }

    /** Uploads keys and the shown script, then starts it on the server (it keeps going if the phone disconnects). */
    fun runScript() = work {
        val s = session ?: throw IllegalStateException("Not connected")
        step = Step.RUNNING; log.clear()
        prefs.edit { putLong("install_started", System.currentTimeMillis()) }
        val ssh = Ssh(known)
        withContext(Dispatchers.IO) {
            ssh.run(s, "mkdir -p ~/.tabula/gateway && chmod 700 ~/.tabula ~/.tabula/gateway && rm -f ~/.tabula/install.log")
            if (kind == ServerKind.OLLAMA) {
                val existing = StringBuilder().also { Ssh.readText(s, "cat ~/.tabula/gateway/keys 2>/dev/null || true", it) }
                val mine = DeviceKey.generate(Build.MODEL)
                val list = DeviceKey.decodeAll(existing.toString()) + mine
                ssh.run(s, "umask 077 && cat > ~/.tabula/gateway/keys", DeviceKey.encodeAll(list).toByteArray())
                ssh.run(s, "umask 077 && cat > ~/.tabula/gateway/Caddyfile", Gateway.caddyfile(list).toByteArray())
                secrets.put("gw-key", mine.key.toByteArray())
                secrets.put("gw-devices", DeviceKey.encodeAll(list).toByteArray())
                withContext(Dispatchers.Main) { devices.clear(); devices.addAll(list) }
            }
            ssh.run(s, "umask 077 && cat > ~/.tabula/install.sh", ServerScripts.install(kind).toByteArray())
            ssh.run(s, ServerScripts.launchDetached(), (password + "\n").toByteArray())
        }
        password = ""
        s.disconnect(); session = null
        prefs.edit { putBoolean("installing", true) }
        follow()
    }

    /** Another device joins by scanning a QR code shown on the owner's phone. */
    fun join() = work {
        val text = scanQr(context) ?: return@work
        val p = Pairing.decode(text) ?: throw IllegalArgumentException("That QR code isn't a Tabula local AI code.")
        val client = GatewayClient.fromPairing(p)
        val names = withContext(Dispatchers.IO) { client.models() }
        secrets.put("gw-key", p.key.toByteArray())
        prefs.edit { putString("gw_host", p.host); putString("gw_ca", p.caBase64); putBoolean("joined", true); putString("kind", ServerKind.OLLAMA.name) }
        kind = ServerKind.OLLAMA
        models.clear(); models.addAll(names)
        step = Step.JOINED
    }

    fun ask(target: String) = work {
        message = "Model says: " + withContext(Dispatchers.IO) { gateway().ask(target, "Reply in five words: are you running?") }
    }

    // Keep the screen on while setup runs; reopening Pro picks up an install that is still going.
    val view = LocalView.current
    DisposableEffect(step) {
        view.keepScreenOn = step == Step.RUNNING
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(Unit) {
        when (step) {
            Step.RUNNING -> if (!busy) withLan { work { follow() } }
            Step.MODELS, Step.JOINED -> if (kind == ServerKind.OLLAMA) withLan { work { refreshModels() } }
            else -> Unit
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Spacer(Modifier.height(16.dp))
            TapLabel("← SETTINGS", onTap = onClose)
            Title("LOCAL AI")
            Label("PRO · YOUR OWN MODEL SERVER ON YOUR NETWORK")
        }

        when (step) {
            Step.SERVER -> item {
                Section("SET UP A SERVER")
                Button(if (busy) "LOOKING…" else "FIND SERVERS ON THIS WIFI", enabled = !busy) {
                    withLan {
                        work {
                            val list = LanFinder.find(context)
                            found.clear(); found.addAll(list)
                            if (list.isEmpty()) message = "No SSH servers found. Is SSH on? (sudo apt install openssh-server)"
                        }
                    }
                }
                found.forEach { f ->
                    val on = f.host == host
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(onClick = { host = f.host }).padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        BasicText((if (on) "● " else "") + f.host, style = TextStyle(fontFamily = Mono, fontSize = 14.sp, color = if (on) look.accent else look.text))
                        Label(listOfNotNull(f.banner.system, f.banner.software).joinToString(" · ").uppercase())
                    }
                }
                Spacer(Modifier.height(8.dp))
                Field(host, "server address, e.g. 192.168.1.20") { host = it }
                Spacer(Modifier.height(8.dp))
                Field(user, "username on the server") { user = it }
                Spacer(Modifier.height(8.dp))
                Field(password, "password", secret = true) { password = it }
                Row(
                    Modifier.fillMaxWidth().combinedClickable(onClick = { permission = !permission }).padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.padding(end = 12.dp).border(1.dp, look.text, RoundedCornerShape(2.dp)).padding(3.dp)) {
                        Box(Modifier.size(12.dp).background(if (permission) look.accent else Color.Transparent))
                    }
                    BasicText(
                        "This is my computer, or I have permission to install software on it.",
                        style = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 18.sp, color = look.text),
                    )
                }
                Note("The password is used once to add this phone's own key and to run setup with sudo. It is never saved.")
                Section("INSTALL")
                Pick(ServerKind.entries, kind) { kind = it }
                Note(
                    if (kind == ServerKind.OLLAMA) "Ollama runs in Docker behind an HTTPS gateway. Every device needs its own key, shared by QR code."
                    else "vLLM: faster on an NVIDIA GPU, runs safetensors models.",
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    if (busy) "CONNECTING…" else "CONNECT",
                    enabled = !busy && permission && password.isNotEmpty() && host.isNotBlank() && user.isNotBlank(),
                ) { withLan { firstConnect() } }
                Section("ALREADY HAVE A TABULA SERVER?")
                Button(if (busy) "…" else "JOIN WITH A QR CODE", enabled = !busy) { withLan { join() } }
                Note("Scan the code from the phone that set the server up (Pro > Devices > Add a device).")
            }

            Step.TRUST -> item {
                Section("NEW SERVER")
                Note("Check this fingerprint matches your server. On the server run: ssh-keygen -lf /etc/ssh/ssh_host_ecdsa_key.pub")
                SelectionContainer { BasicText(fingerprint, style = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = look.text)) }
                Spacer(Modifier.height(16.dp))
                Button("TRUST AND CONNECT") {
                    address()?.let { a ->
                        scope.launch {
                            val ip = withContext(Dispatchers.IO) { InetAddress.getByName(a.host).hostAddress ?: a.host }
                            known.trust(Ssh.hostId(a.copy(host = ip)), fingerprint)
                            prefs.edit { putString("known_hosts", known.encode()) }
                            firstConnect()
                        }
                    }
                }
                TapLabel("CANCEL") { step = Step.SERVER }
            }

            Step.SCRIPT -> item {
                Section("WHAT'S ON ${ServerAddress.parse(addressText)?.host ?: "YOUR SERVER"}")
                checkLines.forEach { line ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        BasicText("—  ", style = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = look.accent))
                        BasicText(line, style = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp, color = look.text))
                    }
                }
                Section("THIS SCRIPT WILL RUN THERE")
                Code(ServerScripts.install(kind))
                Note(
                    if (kind == ServerKind.OLLAMA) "Your device keys are written to a separate file only you can read. They never appear in the script."
                    else "The model server listens only on the server itself. This phone reaches it through the encrypted SSH connection.",
                )
                Spacer(Modifier.height(16.dp))
                Button(if (busy) "STARTING…" else "RUN SETUP", enabled = !busy) { withLan { runScript() } }
                TapLabel("CANCEL") { session?.disconnect(); session = null; password = ""; step = Step.SERVER }
            }

            Step.RUNNING -> item {
                SetupProgressView(SetupProgress.of(log.joinToString("\n")), prefs.getLong("install_started", System.currentTimeMillis()), failed = message != null)
                TapLabel(if (showDetails) "HIDE DETAILS" else "SHOW DETAILS") { showDetails = !showDetails }
                if (showDetails) Code(log.filterNot { it.startsWith("TABULA_") }.joinToString("\n").ifEmpty { "…" })
                if (!busy) TapLabel("BACK TO SERVER SETUP") { step = Step.SERVER }
            }

            Step.MODELS, Step.JOINED -> {
                item {
                    val where = prefs.getString("gw_host", null) ?: ServerAddress.parse(addressText)?.host ?: ""
                    Section(if (step == Step.JOINED) "JOINED $where" else "SERVER $where · $kind")
                    if (kind == ServerKind.OLLAMA) {
                        Section("YOUR MODELS")
                        if (models.isEmpty()) Note("No models yet. Find one below.")
                        models.forEach { m ->
                            val on = m == model
                            Row(
                                Modifier.fillMaxWidth().combinedClickable(onClick = { model = m; prefs.edit { putString("model", m) } }).padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                BasicText((if (on) "● " else "") + m, style = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = if (on) look.accent else look.text))
                            }
                        }
                        model?.let { m ->
                            Spacer(Modifier.height(8.dp))
                            Button(if (busy) "ASKING…" else "ASK ${m.substringAfterLast('/').uppercase().take(24)} A TEST QUESTION", enabled = !busy) { withLan { ask(m) } }
                        }
                    }
                }

                if (step == Step.MODELS && kind == ServerKind.OLLAMA) item {
                    Section("DEVICES WITH ACCESS")
                    devices.forEach { d ->
                        val mine = d.key == secrets.get("gw-key")?.decodeToString()
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            BasicText(d.name + if (mine) " (this phone)" else "", Modifier.weight(1f), style = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = look.text))
                            if (!mine) {
                                TapLabel("QR") { qr = d.name to pairingText(prefs, d.key) }
                                Spacer(Modifier.size(16.dp))
                                TapLabel("REVOKE", color = look.accent) { withLan { work { pushKeys(devices.filter { it.key != d.key }); qr = null } } }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Field(newDevice, "new device name, e.g. Laptop") { newDevice = it }
                    Spacer(Modifier.height(8.dp))
                    Button(if (busy) "ADDING…" else "ADD A DEVICE", enabled = !busy && newDevice.isNotBlank()) {
                        withLan {
                            work {
                                val d = DeviceKey.generate(newDevice)
                                pushKeys(devices + d)
                                qr = d.name to pairingText(prefs, d.key); newDevice = ""
                            }
                        }
                    }
                    qr?.let { (name, text) ->
                        Section("SCAN ON ${name.uppercase()}")
                        QrCode(text)
                        Note("Anyone who scans this can use your local AI. Revoke it here any time. In Tabula on the other phone: Pro > Join with a QR code.")
                        TapLabel("HIDE QR") { qr = null }
                    }
                }

                if (step == Step.MODELS) item {
                    Section("HUGGING FACE TOKEN (OPTIONAL, FOR GATED MODELS)")
                    Field(hfToken, "hf_…", secret = true) { hfToken = it }
                    Note("Stored encrypted on this phone. Hugging Face requests are plain API calls, never AI.")
                    TapLabel("SAVE TOKEN") { if (hfToken.isBlank()) secrets.remove("hf-token") else secrets.put("hf-token", hfToken.trim().toByteArray()) }
                    Section("FIND A MODEL ON HUGGING FACE")
                    Field(query, if (kind == ServerKind.OLLAMA) "e.g. llama gguf" else "e.g. qwen3") { query = it }
                    Spacer(Modifier.height(8.dp))
                    Button(if (busy) "SEARCHING…" else "SEARCH", enabled = !busy && query.isNotBlank()) {
                        work {
                            val list = withContext(Dispatchers.IO) { HfApi(hfToken.ifBlank { null }).search(query, kind) }
                            hits.clear(); hits.addAll(list); picked = null
                        }
                    }
                }
                if (step == Step.MODELS) picked?.let { (id, decision) ->
                    item {
                        Section(id.value.uppercase())
                        when (decision) {
                            is HfDecision.Allowed -> {
                                Note("Licence: ${decision.licence}. By installing you agree to the model's licence. It downloads straight to your server.")
                                Button(if (busy) "INSTALLING…" else "INSTALL ON SERVER", enabled = !busy) {
                                    withLan {
                                        work {
                                            log.clear()
                                            val cmd = ServerScripts.installModel(kind, id)
                                            val token = hfToken.trim()
                                            val code = withKeySession { s ->
                                                // The token goes over stdin, never on the command line.
                                                if (token.isNotEmpty()) Ssh(known).run(s, "read -r HF_TOKEN; export HF_TOKEN; $cmd", (token + "\n").toByteArray()) { scope.launch { log.add(it) } }
                                                else Ssh(known).run(s, cmd) { scope.launch { log.add(it) } }
                                            }
                                            if (code == 0) {
                                                model = if (kind == ServerKind.OLLAMA) "hf.co/${id.value}" else id.value
                                                prefs.edit { putString("model", model) }
                                                runCatching { refreshModels() }
                                                message = if (kind == ServerKind.VLLM) "Installed. vLLM takes a minute or two to load it." else "Installed."
                                            } else message = "Install failed (exit $code). See the log."
                                        }
                                    }
                                }
                            }
                            is HfDecision.AcceptTermsOnHuggingFace -> {
                                Note("This model is gated. Accept its terms on Hugging Face with your own account, then search again.")
                                Button("OPEN ON HUGGING FACE") { context.startActivity(Intent(Intent.ACTION_VIEW, decision.url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                            is HfDecision.ReviewModelCard -> {
                                Note("No licence is listed. Read the model card before using it.")
                                Button("OPEN MODEL CARD") { context.startActivity(Intent(Intent.ACTION_VIEW, decision.url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                            is HfDecision.Blocked -> Note(decision.reason)
                        }
                        if (log.isNotEmpty()) Code(log.takeLast(12).joinToString("\n"))
                    }
                }
                if (step == Step.MODELS) items(hits, key = { it.id }) { hit ->
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(onClick = {
                            work {
                                val id = HfModelId.parse(hit.id)
                                val api = HfApi(hfToken.ifBlank { null })
                                picked = id to withContext(Dispatchers.IO) { api.details(id).let { HfPolicy.decide(it, kind, accessGranted = api.hasAccess(it)) } }
                            }
                        }).padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        BasicText(hit.id, Modifier.weight(1f), style = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = look.text))
                        Label("${hit.downloads / 1000}K")
                    }
                }
                if (kind == ServerKind.VLLM && step == Step.MODELS) item {
                    model?.let { m ->
                        Section("TRY IT")
                        Button(if (busy) "ASKING…" else "ASK THE MODEL A TEST QUESTION", enabled = !busy) {
                            withLan { work { message = "Model says: " + withKeySession { s -> testVllm(s, m) } } }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(24.dp))
                    TapLabel(if (step == Step.JOINED) "LEAVE THIS SERVER" else "SET UP A DIFFERENT SERVER", color = look.faint) {
                        prefs.edit { putBoolean("ready", false); putBoolean("joined", false); remove("model"); remove("gw_host"); remove("gw_ca") }
                        secrets.remove("ssh-key"); secrets.remove("gw-key"); secrets.remove("gw-devices")
                        devices.clear(); models.clear(); model = null; step = Step.SERVER
                    }
                }
            }
        }

        message?.let { item { Spacer(Modifier.height(12.dp)); SelectionContainer { BasicText(it, style = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp, color = look.accent)) } } }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

/** The setup loading screen: the current stage in words, a progress bar, elapsed time and a gentle pulse. */
@Composable
private fun SetupProgressView(progress: SetupProgress, startedAt: Long, failed: Boolean) {
    val look = LocalLook.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    val fraction by animateFloatAsState(
        targetValue = if (progress.total == 0) 0f else (progress.step.coerceAtLeast(0) / progress.total.toFloat()).coerceIn(0.03f, 1f),
        animationSpec = tween(800),
        label = "setup",
    )
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse",
    )
    val secs = ((now - startedAt) / 1000).coerceAtLeast(0)

    Spacer(Modifier.height(32.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).alpha(if (failed) 1f else pulse).background(look.accent, CircleShape))
        Spacer(Modifier.size(12.dp))
        Label(if (failed) "STOPPED" else "STEP ${progress.step.coerceAtLeast(1)} OF ${progress.total}")
    }
    BasicText(
        progress.label,
        Modifier.padding(vertical = 16.dp),
        style = TextStyle(fontFamily = Grotesk, fontSize = 30.sp, lineHeight = 34.sp, color = look.text, letterSpacing = (-0.5).sp),
    )
    Box(Modifier.fillMaxWidth().height(6.dp).background(look.faint.copy(alpha = 0.35f), RoundedCornerShape(3.dp))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(look.accent, RoundedCornerShape(3.dp)))
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Label("%d:%02d".format(secs / 60, secs % 60))
        Label("KEEPS GOING IF YOU LEAVE")
    }
    Spacer(Modifier.height(16.dp))
}

/** A QR code on a white square (QR codes need dark-on-light to scan reliably). */
@Composable
private fun QrCode(text: String) {
    val matrix = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2)) }
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White).padding(8.dp)) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

private fun pairingText(prefs: SharedPreferences, key: String): String =
    Pairing(prefs.getString("gw_host", "")!!, Gateway.PORT, key, prefs.getString("gw_ca", "")!!).encode()

/** Opens Google's code scanner. Tabula itself never gets camera access. */
private suspend fun scanQr(context: Context): String? = suspendCancellableCoroutine { cont ->
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { cont.resume(it.rawValue) }
        .addOnCanceledListener { cont.resume(null) }
        .addOnFailureListener { cont.resume(null) }
}

/** vLLM is reached through an SSH tunnel to the server's own localhost. */
private fun testVllm(s: Session, model: String): String {
    val local = s.setPortForwardingL(0, "127.0.0.1", ServerScripts.port(ServerKind.VLLM))
    try {
        val key = StringBuilder().also { Ssh.readText(s, "cat ~/.tabula/api-key", it) }.toString().trim()
        val c = URL("http://127.0.0.1:$local/v1/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 120_000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer $key")
        val body = JSONObject().put("model", model).put("max_tokens", 30)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply in five words: are you running?")))
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode !in 200..299) return "server replied ${c.responseCode} (vLLM may still be loading)"
        return JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim()
    } finally {
        s.delPortForwardingL(local)
    }
}

@Composable
private fun Field(value: String, hint: String, secret: Boolean = false, onChange: (String) -> Unit) {
    val look = LocalLook.current
    val context = LocalContext.current
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(fontFamily = Mono, fontSize = 15.sp, color = look.text),
        cursorBrush = SolidColor(look.accent),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri, autoCorrectEnabled = false),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().height(52.dp).border(1.dp, look.faint, RoundedCornerShape(look.radius / 2)).padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) BasicText(hint, style = TextStyle(fontFamily = Mono, fontSize = 15.sp, color = look.faint))
                    inner()
                }
                BasicText(
                    "PASTE",
                    Modifier.combinedClickable(onClick = { clipboardText(context)?.let { onChange(it.trim()) } }).padding(horizontal = 14.dp, vertical = 16.dp),
                    style = TextStyle(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.sp, color = look.muted),
                )
            }
        },
    )
}

@Composable
private fun Button(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val look = LocalLook.current
    Box(
        Modifier.fillMaxWidth().height(52.dp)
            .background(if (enabled) look.text else look.faint, RoundedCornerShape(look.radius))
            .then(if (enabled) Modifier.combinedClickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp, color = look.bg))
    }
}

@Composable
private fun <E : Enum<E>> Pick(options: List<E>, selected: E, onPick: (E) -> Unit) {
    val look = LocalLook.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier.weight(1f).height(48.dp)
                    .background(if (on) look.text else Color.Transparent, RoundedCornerShape(look.radius / 4))
                    .border(1.dp, if (on) look.text else look.faint, RoundedCornerShape(look.radius / 4))
                    .combinedClickable(onClick = { onPick(o) }),
                contentAlignment = Alignment.Center,
            ) { BasicText(o.name, style = TextStyle(fontFamily = Mono, fontSize = 12.sp, letterSpacing = 1.sp, color = if (on) look.bg else look.muted)) }
        }
    }
}

@Composable
private fun Note(text: String) {
    BasicText(text, Modifier.padding(vertical = 8.dp), style = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 18.sp, color = LocalLook.current.muted))
}

@Composable
private fun Code(text: String) {
    val look = LocalLook.current
    Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).border(1.dp, look.faint, RoundedCornerShape(4.dp)).padding(12.dp)) {
        SelectionContainer {
            BasicText(text, style = TextStyle(fontFamily = Mono, fontSize = 10.sp, lineHeight = 14.sp, color = look.text))
        }
    }
}

/** Current clipboard text, read only when the user taps PASTE. */
private fun clipboardText(context: Context): String? {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

/** Failed logins survive app restarts, so the 3-tries lock can't be reset by reopening Tabula. */
private fun loginGuard(prefs: SharedPreferences): app.tabula.core.LoginGuard {
    val guard = app.tabula.core.LoginGuard()
    prefs.all.filterKeys { it.startsWith("fail:") }.forEach { (k, v) ->
        (v as? String)?.split(',')?.mapNotNull { it.toLongOrNull() }?.forEach { guard.failed(k.removePrefix("fail:"), it) }
    }
    return guard
}

private fun recordFailure(prefs: SharedPreferences, host: String) {
    val previous = prefs.getString("fail:$host", "")!!.split(',').filter { it.isNotBlank() }
    prefs.edit { putString("fail:$host", (previous + System.currentTimeMillis().toString()).takeLast(3).joinToString(",")) }
}

private fun clearFailures(prefs: SharedPreferences, host: String) = prefs.edit { remove("fail:$host") }
