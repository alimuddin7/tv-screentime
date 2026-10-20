package com.tvscreentime.parent

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

// Same palette as the TV launcher: black, graphite cards, white type, one accent.
private val Bg = Color.Black
private val Card = Color(0xFF1C1C1E)
private val Card2 = Color(0xFF2C2C2E)
private val Dim = Color(0xFF8E8E93)
private val Accent = Color(0xFF0A84FF)
private val Red = Color(0xFFFF453A)

class MainActivity : ComponentActivity() {
    private val snackbar = SnackbarHostState()

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Tvs.init(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Bg, primary = Accent, error = Red)) {
                App(snackbar, lang = Lang.get(this), onLang = ::setLang, onScan = ::scan, toast = ::toast)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Tvs.connectAll()
        Tvs.startDiscovery()
    }

    override fun onStop() {
        Tvs.disconnectAll()
        Tvs.stopDiscovery()
        super.onStop()
    }

    private fun setLang(tag: String) {
        Lang.set(this, tag)
        Tvs.list.forEach { Tvs.command(it, "lang", pkg = tag) }
        recreate()
    }

    private fun toast(msg: String) { lifecycleScope.launch { snackbar.showSnackbar(msg) } }

    private fun scan() {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, opts).startScan()
            .addOnSuccessListener { code ->
                val raw = code.rawValue ?: return@addOnSuccessListener
                thread {
                    runCatching { Tvs.pair(raw, Build.MODEL) }
                        .onSuccess { toast(getString(R.string.connected, it.name)) }
                        .onFailure { toast(getString(R.string.failed, it.message)) }
                }
            }
            .addOnFailureListener { toast(getString(R.string.scanner_unavailable, it.message)) }
    }
}

@Composable
private fun App(snackbar: SnackbarHostState, lang: String, onLang: (String) -> Unit, onScan: () -> Unit, toast: (String) -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    val tv = Tvs.list.firstOrNull { it.id == selected }
    BackHandler(tv != null) { selected = null }

    Box(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()) {
        if (tv == null) Home(lang, onLang, onOpen = { selected = it.id }, onScan = onScan, toast = toast)
        else TvDetail(tv, onBack = { selected = null }, toast = toast)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Home(lang: String, onLang: (String) -> Unit, onOpen: (Tv) -> Unit, onScan: () -> Unit, toast: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_name), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.weight(1f))
                Segmented(listOf("in" to "ID", "en" to "EN"), lang, onLang)
            }
        }
        if (Tvs.list.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📺", fontSize = 56.sp)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.no_tv), fontSize = 24.sp, fontWeight = FontWeight.Light, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.no_tv_hint), color = Dim, fontSize = 15.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(28.dp))
                SoloPill(stringResource(R.string.scan_qr), onScan, bg = Accent)
            }
        }
        if (Tvs.list.size > 1) item {
            Panel {
                Text(stringResource(R.string.all_tvs), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Controls(locked = Tvs.list.all { Tvs.status[it.id]?.let(::isLocked) == true }) { cmd, min ->
                    Tvs.list.forEach { Tvs.command(it, cmd, min, onError = toast) }
                }
            }
        }
        items(Tvs.list, key = { it.id }) { tv -> TvCard(tv, onOpen, toast) }
        if (Tvs.list.isNotEmpty()) item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onClick = onScan) { Text("＋  " + stringResource(R.string.add_tv), color = Accent, fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun TvCard(tv: Tv, onOpen: (Tv) -> Unit, toast: (String) -> Unit) {
    val s = Tvs.status[tv.id]
    Panel {
        Row(Modifier.fillMaxWidth().clickable { onOpen(tv) }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(when {
                s == null -> Dim
                isLocked(s) -> Red
                else -> Color(0xFF30D158)
            }))
            Spacer(Modifier.width(8.dp))
            Text(tv.name, color = Dim, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.manage) + "  ›", color = Accent, fontSize = 15.sp)
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            val (big, small) = when {
                s == null -> stringResource(R.string.offline) to null
                isLocked(s) -> stringResource(R.string.locked) to null
                s.mode == "UNLOCKED" -> stringResource(R.string.free) to null
                else -> "${(s.remainingMs + 59_999) / 60_000}" to stringResource(R.string.min_left)
            }
            Text(big, color = if (s != null && isLocked(s)) Red else Color.White, fontSize = 48.sp, fontWeight = FontWeight.Light)
            if (small != null) Text("  $small", color = Dim, fontSize = 17.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
        if (s != null) Controls(locked = isLocked(s)) { cmd, min -> Tvs.command(tv, cmd, min, onError = toast) }
    }
}

/** Block/unblock, ±15 and set-timer buttons, shared by a single TV and "All TVs". */
@Composable
private fun Controls(locked: Boolean, send: (cmd: String, minutes: Int) -> Unit) {
    var askTimer by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("−15", { send("add", -15) })
            Pill("+15", { send("add", 15) })
            Pill(stringResource(R.string.timer), { askTimer = true })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(stringResource(R.string.lock), { send("block", 0) }, bg = Red)
            Pill(stringResource(if (locked) R.string.unlock else R.string.no_limit), { send("unblock", 0) }, bg = Accent)
        }
    }
    if (askTimer) TimerDialog(onDismiss = { askTimer = false }) { send("timer", it); askTimer = false }
}

@Composable
private fun TimerDialog(onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    var custom by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text(stringResource(R.string.set_timer), color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60, 90).forEach { m -> Pill("$m", { onSet(m) }) }
                }
                Field(custom, { custom = it.filter(Char::isDigit).take(3) }, stringResource(R.string.other_minutes), number = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { custom.toIntOrNull()?.let(onSet) }, enabled = custom.isNotEmpty()) { Text(stringResource(R.string.set), color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Dim) } },
    )
}

@Composable
private fun TvDetail(tv: Tv, onBack: () -> Unit, toast: (String) -> Unit) {
    var name by remember(tv.id) { mutableStateOf(tv.name) }
    var apps by remember(tv.id) { mutableStateOf<List<Tvs.App>?>(null) }
    var devices by remember(tv.id) { mutableStateOf<List<Tvs.Device>?>(null) }
    var channels by remember(tv.id) { mutableStateOf<List<Tvs.Channel>?>(null) }
    var newChannel by remember { mutableStateOf("") }
    var toAll by remember { mutableStateOf(true) }
    var adding by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val offlineMsg = stringResource(R.string.tv_offline, "%s")

    fun reload() = thread {
        runCatching { Triple(Tvs.apps(tv), Tvs.devices(tv), Tvs.channels(tv)) }
            .onSuccess { (a, d, c) -> scope.launch { apps = a; devices = d; channels = c } }
            .onFailure { toast(offlineMsg.format(it.message)) }
    }
    LaunchedEffect(tv.id) { reload() }
    val allowed = Tvs.status[tv.id]?.allowed
    fun targets() = if (toAll) Tvs.list.toList() else listOf(tv)

    LazyColumn(contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 40.dp)) {
        item {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                Text("‹  " + stringResource(R.string.back), color = Accent, fontSize = 17.sp)
            }
            Text(tv.name, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(name, { name = it }, stringResource(R.string.tv_name), Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Pill(stringResource(R.string.save), { Tvs.rename(tv, name) }, enabled = name.trim() != tv.name, weight = false)
            }

            Section(stringResource(R.string.tv_screen))
            Panel(padding = 0.dp) {
                Line(stringResource(R.string.show_tools)) {
                    Switch(Tvs.status[tv.id]?.tools ?: true,
                        { Tvs.command(tv, "tools", pkg = if (it) "on" else "off", onError = toast) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF30D158)))
                }
            }

            Section(stringResource(R.string.channels))
        }
        item {
            Panel(padding = 0.dp) {
                channels.orEmpty().forEach { ch ->
                    Line(ch.title) {
                        TextButton(onClick = {
                            thread { targets().forEach { t -> runCatching { Tvs.removeChannel(t, ch.id) } }; reload() }
                        }) { Text(stringResource(R.string.remove), color = Red) }
                    }
                }
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Field(newChannel, { newChannel = it }, stringResource(R.string.channel_hint), Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    Pill(if (adding) "…" else stringResource(R.string.add), enabled = newChannel.isNotBlank() && !adding, weight = false, onClick = {
                        adding = true
                        val q = newChannel
                        thread {
                            targets().forEach { t -> runCatching { Tvs.addChannel(t, q) }.onFailure { toast("${t.name}: ${it.message}") } }
                            scope.launch { adding = false; newChannel = "" }
                            reload()
                        }
                    })
                }
            }
            if (Tvs.list.size > 1) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(toAll, { toAll = it }, colors = CheckboxDefaults.colors(checkedColor = Accent))
                Text(stringResource(R.string.apply_all), color = Dim)
            }
            Section(stringResource(R.string.apps))
        }
        item {
            Panel(padding = 0.dp) {
                if (apps == null) Line(stringResource(R.string.loading)) {}
                apps.orEmpty().forEach { app ->
                    Line(app.label) {
                        Switch(allowed?.contains(app.pkg) ?: app.allowed,
                            { Tvs.command(tv, if (it) "allow" else "deny", pkg = app.pkg, onError = toast) },
                            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF30D158)))
                    }
                }
            }
            Section(stringResource(R.string.devices))
        }
        item {
            Panel(padding = 0.dp) {
                devices.orEmpty().forEach { d ->
                    Line(d.name + if (d.self) "  ·  " + stringResource(R.string.this_phone) else "") {
                        if (!d.self) TextButton(onClick = {
                            thread { runCatching { Tvs.revoke(tv, d.id) }; reload() }
                        }) { Text(stringResource(R.string.revoke), color = Red) }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onClick = { confirmRemove = true }) { Text(stringResource(R.string.remove_tv), color = Red) }
            }
        }
    }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        containerColor = Card,
        title = { Text(stringResource(R.string.remove_tv_q, tv.name), color = Color.White) },
        text = { Text(stringResource(R.string.remove_tv_hint), color = Dim) },
        confirmButton = { TextButton(onClick = { Tvs.remove(tv); onBack() }) { Text(stringResource(R.string.remove), color = Red) } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel), color = Dim) } },
    )
}

// --- Small building blocks ---

@Composable
private fun Panel(padding: androidx.compose.ui.unit.Dp = 18.dp, content: @Composable () -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(padding)) { content() }

@Composable
private fun Line(title: String, trailing: @Composable () -> Unit) =
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        trailing()
    }

@Composable
private fun Section(title: String) =
    Text(title.uppercase(), color = Dim, fontSize = 13.sp, letterSpacing = 0.5.sp, modifier = Modifier.padding(start = 18.dp, top = 28.dp, bottom = 8.dp))

@Composable
private fun RowScope.Pill(label: String, onClick: () -> Unit, bg: Color = Card2, enabled: Boolean = true, weight: Boolean = true) =
    Box(
        (if (weight) Modifier.weight(1f) else Modifier).height(46.dp).clip(RoundedCornerShape(50))
            .background(if (enabled) bg else Card2.copy(alpha = 0.5f)).clickable(enabled, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) Color.White else Dim, fontSize = 16.sp, fontWeight = FontWeight.Medium) }

@Composable
private fun SoloPill(label: String, onClick: () -> Unit, bg: Color = Card2) = Row { Pill(label, onClick, bg, weight = false) }

@Composable
private fun Segmented(options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) =
    Row(Modifier.clip(RoundedCornerShape(50)).background(Card).padding(3.dp)) {
        options.forEach { (key, label) ->
            Box(Modifier.clip(RoundedCornerShape(50)).background(if (key == value) Card2 else Color.Transparent)
                .clickable { if (key != value) onChange(key) }.padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text(label, color = if (key == value) Color.White else Dim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, number: Boolean = false) =
    OutlinedTextField(
        value, onChange, modifier, label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(14.dp),
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent, unfocusedBorderColor = Card2, focusedLabelColor = Accent, unfocusedLabelColor = Dim,
            focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Accent,
        ),
    )

private fun isLocked(s: Status) = s.mode == "BLOCKED" || (s.mode == "TIMER" && s.remainingMs <= 0)
