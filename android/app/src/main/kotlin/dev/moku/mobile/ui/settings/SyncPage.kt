package dev.moku.mobile.ui.settings

import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.moku.mobile.R
import dev.moku.mobile.backend.MediaContentType
import dev.moku.mobile.core.Core
import dev.moku.mobile.core.SyncNsd
import dev.moku.mobile.remote.str
import dev.moku.mobile.remote.strOrNull
import dev.moku.mobile.repository.LocalRepositoryManager
import dev.moku.mobile.sync.ServerSync
import dev.moku.mobile.ui.components.MokuToast
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuText
import dev.moku.mobile.ui.theme.MokuTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private data class Peer(val node: String, val name: String, val online: Boolean, val lastSeenAt: Long?)
private data class Nearby(val node: String, val name: String, val address: String, val paired: Boolean)
private data class Missing(val packageName: String, val name: String, val lang: String, val contentType: String, val repository: String, val titles: Int, val examples: List<String>)
private data class Status(val name: String?, val peers: List<Peer>, val pendingTitles: Int, val missing: List<Missing>)
private data class Preview(val node: String, val name: String, val newTitles: Int, val sentTitles: Int, val readChapters: Int, val mergingFolders: List<String>)
private data class Target(val name: String?, val addresses: List<String>)

private const val STATUS = """node name pendingTitles
    peers { node name online lastSeenAt }
    missingExtensions { packageName name lang contentType repository titles examples }"""

private fun JsonObject.toStatus() = Status(
    name = strOrNull("name"),
    peers = this["peers"]!!.jsonArray.map { it.jsonObject }.map {
        Peer(it.str("node"), it.str("name"), it.str("online") == "true", ServerSync.millis(it.strOrNull("lastSeenAt")))
    },
    pendingTitles = str("pendingTitles").toInt(),
    missing = this["missingExtensions"]!!.jsonArray.map { it.jsonObject }.map {
        Missing(it.str("packageName"), it.str("name"), it.str("lang"), it.str("contentType"), it.str("repository"), it.str("titles").toInt(),
            (it["examples"] as JsonArray).map { e -> e.jsonPrimitive.content })
    },
)

private fun plural(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

// A firewall drops the connection silently, so a timeout is the only sign of it; name the port to open.
private fun pairFailure(error: Throwable?, name: String?, address: String?): String {
    val message = error?.message.orEmpty()
    val unreachable = listOf("deadline exceeded", "i/o timeout", "connection refused", "no route to host", "network is unreachable")
        .any { message.contains(it, ignoreCase = true) }
    if (message.isNotEmpty() && !unreachable) return message
    val who = name ?: "that device"
    val port = address?.let { Uri.parse(it).port }?.takeIf { it > 0 } ?: 6008
    return "Couldn't reach $who. Check it's on the same Wi-Fi and that its firewall lets in port $port."
}

// Changing text mid-typing makes the keyboard drop characters, so what's typed is kept as is and only drawn
// like the other device shows it: capitals, with a space after the first four.
private object CodeFormat : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text.uppercase()
        if (s.length <= 4 || !s.all { it.isLetterOrDigit() }) return TransformedText(AnnotatedString(s), OffsetMapping.Identity)
        val offsets = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = if (offset > 4) offset + 1 else offset
            override fun transformedToOriginal(offset: Int) = (if (offset > 4) offset - 1 else offset).coerceAtMost(s.length)
        }
        return TransformedText(AnnotatedString(s.take(4) + " " + s.drop(4)), offsets)
    }
}

/** Moku desktop's QR code: `moku://pair?node=…&name=…&code=…&addr=…` with one `addr` per network address. */
private fun parsePairLink(text: String): Pair<Target, String>? {
    val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return null
    if (uri.scheme != "moku" || uri.host != "pair") return null
    val code = uri.getQueryParameter("code") ?: return null
    val addresses = uri.getQueryParameters("addr").filter { it.isNotBlank() }
    if (addresses.isEmpty()) return null
    return Target(uri.getQueryParameter("name"), addresses) to code
}

@Composable
internal fun SyncPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = MokuTheme.colors
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<Status?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var nameDraft by remember { mutableStateOf(Build.MODEL.orEmpty()) }
    var renaming by remember { mutableStateOf(false) }
    var nearby by remember { mutableStateOf<List<Nearby>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var target by remember { mutableStateOf<Target?>(null) }
    var codeInput by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Pair<Preview, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pairError by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf<Set<String>>(emptySet()) }

    suspend fun load() {
        runCatching { Core.query(context, "query Sync { syncStatus { $STATUS } }")["syncStatus"]!!.jsonObject.toStatus() }
            .onSuccess { status = it; loadError = null }
            .onFailure { loadError = it.message ?: "Moku's sync service isn't running" }
    }

    suspend fun scan() {
        if (scanning) return
        scanning = true
        runCatching {
            Core.query(context, "query Nearby { syncNearby { node name address paired } }")["syncNearby"]!!.jsonArray.map { it.jsonObject }
                .map { Nearby(it.str("node"), it.str("name"), it.str("address"), it.str("paired") == "true") }
        }.onSuccess { nearby = it }
        scanning = false
    }

    // Online dots and last-seen times only stay true while they're refreshed.
    LaunchedEffect(Unit) {
        while (true) {
            load()
            delay(4_000)
        }
    }
    val named = status?.name != null
    LaunchedEffect(named) { if (named) scan() }

    fun saveName() = scope.launch {
        val name = nameDraft.trim()
        if (name.isEmpty() || busy) return@launch
        busy = true
        runCatching {
            Core.query(context, "mutation Name(\$n: String!) { setSyncDeviceName(name: \$n) { $STATUS } }", buildJsonObject { put("n", name) })
        }.onSuccess { status = it["setSyncDeviceName"]!!.jsonObject.toStatus(); renaming = false; SyncNsd.advertise() }
            .onFailure { MokuToast.show(it.message ?: "Couldn't save the name") }
        busy = false
    }

    fun previewAt(t: Target, pairingCode: String) = scope.launch {
        if (busy) return@launch
        busy = true
        pairError = null
        val clean = pairingCode.filter { it.isLetterOrDigit() }.uppercase()
        // A desktop has several addresses; the first one this phone can reach is the one to pair with.
        var last: Throwable? = null
        for (address in t.addresses) {
            val result = runCatching {
                Core.query(
                    context,
                    "mutation Preview(\$a: String!, \$c: String!) { previewSyncPeer(address: \$a, code: \$c) { node name newTitles sentTitles readChapters mergingFolders } }",
                    buildJsonObject { put("a", address); put("c", clean) },
                )["previewSyncPeer"]!!.jsonObject
            }
            result.onSuccess {
                preview = Preview(it.str("node"), it.str("name"), it.str("newTitles").toInt(), it.str("sentTitles").toInt(),
                    it.str("readChapters").toInt(), (it["mergingFolders"] as JsonArray).map { f -> f.jsonPrimitive.content }) to address
                codeInput = clean
            }
            if (result.isSuccess) break
            last = result.exceptionOrNull()
        }
        if (preview == null) pairError = pairFailure(last, t.name, t.addresses.firstOrNull())
        busy = false
    }

    fun pair() = scope.launch {
        val (p, address) = preview ?: return@launch
        if (busy) return@launch
        busy = true
        pairError = null
        runCatching {
            Core.query(
                context,
                "mutation Pair(\$a: String!, \$c: String!, \$n: ID!) { connectSyncPeer(address: \$a, code: \$c, node: \$n) { name } }",
                buildJsonObject { put("a", address); put("c", codeInput); put("n", p.node) },
            )
        }.onSuccess {
            MokuToast.show("Paired with ${p.name}. Your libraries will merge over the next few seconds.")
            target = null
            preview = null
            load()
            scan()
        }.onFailure { pairError = pairFailure(it, p.name, address) }
        busy = false
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val link = result.contents?.let(::parsePairLink)
        if (result.contents != null && link == null) MokuToast.show("That isn't a Moku pairing code")
        link?.let { (t, c) ->
            target = t
            preview = null
            previewAt(t, c)
        }
    }

    fun showCode() = scope.launch {
        runCatching {
            Core.query(context, "mutation Code { createSyncPairingCode { code expiresAt } }")["createSyncPairingCode"]!!.jsonObject
        }.onSuccess { code = it.str("code") to (ServerSync.millis(it.strOrNull("expiresAt")) ?: 0L) }
            .onFailure { MokuToast.show(it.message ?: "Couldn't make a pairing code") }
    }

    fun remove(node: String) = scope.launch {
        runCatching { Core.query(context, "mutation Unpair(\$n: ID!) { removeSyncPeer(node: \$n) { $STATUS } }", buildJsonObject { put("n", node) }) }
            .onSuccess { status = it["removeSyncPeer"]!!.jsonObject.toStatus() }
            .onFailure { MokuToast.show(it.message ?: "Couldn't remove the device") }
        confirmRemove = null
    }

    // Installs through the phone's own extension list, so it browses and reads the titles too; the core gets a copy.
    fun install(m: Missing) = scope.launch {
        installing = installing + m.packageName
        val type = MediaContentType.entries.firstOrNull { it.name == m.contentType.uppercase() } ?: MediaContentType.MANGA
        runCatching {
            val repos = LocalRepositoryManager(context)
            suspend fun find() = repos.getRepositories().firstNotNullOfOrNull { r -> repos.getAvailableExtensions(r.id).firstOrNull { it.packageName == m.packageName } }
            val available = find() ?: run {
                if (!m.repository.startsWith("http")) error("Add the repository ${m.name} comes from in Extensions first")
                repos.syncRepository(repos.addRepository(m.repository, null, type))
                find() ?: error("${m.name} isn't in that repository anymore")
            }
            repos.installExtension(available, type)
        }.onSuccess { MokuToast.show("${m.name} installed. Its titles will appear in your library shortly.") }
            .onFailure { MokuToast.show(it.message ?: "Couldn't install ${m.name}") }
        installing = installing - m.packageName
        load()
    }

    SettingsPage("Sync", onBack = onBack) {
        val s = status
        if (s == null) {
            item {
                SettingsGroup {
                    SettingsRow(loadError?.let { "Can't reach Moku's sync service" } ?: "Loading…", subtitle = loadError)
                }
            }
            return@SettingsPage
        }
        if (s.name == null || renaming) {
            item {
                SettingsGroup(
                    label = if (s.name == null) "Name this phone" else "Rename",
                    footer = "Your other devices will see this name.",
                ) {
                    Column(Modifier.fillMaxWidth().padding(MokuSpacing.Sp3), verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                        TextInput(nameDraft, { nameDraft = it.take(40) }, "e.g. Pixel 8", Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2, Alignment.End)) {
                            if (s.name != null) ActionChip("Cancel") { renaming = false }
                            ActionChip(if (s.name == null) "Continue" else "Save", accent = true) { saveName() }
                        }
                    }
                }
            }
            if (s.name == null) return@SettingsPage
        }

        item {
            SettingsGroup(
                footer = when {
                    s.pendingTitles > 0 -> "${plural(s.pendingTitles, "title", "titles")} still loading."
                    s.peers.any { it.online } -> "Up to date."
                    s.peers.isNotEmpty() -> "No devices connected. Changes go out when they are."
                    else -> null
                },
            ) {
                SettingsRow(s.name, icon = R.drawable.ph_device_mobile_light, subtitle = "Shown on your other devices", value = "Rename") {
                    nameDraft = s.name
                    renaming = true
                }
            }
        }

        item {
            SettingsGroup(label = "Paired devices") {
                if (s.peers.isEmpty()) SettingsRow("None yet", subtitle = "Pair one below; it keeps syncing on its own after that.")
                s.peers.forEachIndexed { i, p ->
                    if (i > 0) GroupDivider()
                    if (confirmRemove == p.node) {
                        SettingsRow("Remove ${p.name}", icon = R.drawable.ph_trash_light, danger = true) { remove(p.node) }
                        GroupDivider()
                        SettingsRow("Cancel", icon = R.drawable.ph_x_light) { confirmRemove = null }
                    } else {
                        SettingsRow(
                            p.name,
                            icon = if (p.online) R.drawable.ph_cloud_check_light else R.drawable.ph_cloud_slash_light,
                            subtitle = when {
                                p.online -> "Connected"
                                p.lastSeenAt != null -> "Last seen ${DateUtils.getRelativeTimeSpanString(p.lastSeenAt)}"
                                else -> "Hasn't connected yet"
                            },
                        ) { confirmRemove = p.node }
                    }
                }
            }
        }

        item {
            SettingsGroup(label = "Pair a device", footer = pairError) {
                val t = target
                if (t != null) {
                    Column(Modifier.fillMaxWidth().padding(MokuSpacing.Sp3), verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                        val p = preview?.first
                        Text("Pair with ${p?.name ?: t.name ?: t.addresses.first()}", style = MokuText.body, color = colors.textPrimary)
                        if (p == null) {
                            Text("Enter the code shown on ${t.name ?: "that device"} under Settings › Sync.", style = MokuText.meta, color = colors.textMuted)
                            TextInput(codeInput, { codeInput = it.take(11) }, "XXXX XXXX", Modifier.fillMaxWidth(), mono = true, keyboard = KeyboardType.Ascii, format = CodeFormat)
                        } else {
                            Text("Libraries merge. Nothing is removed.", style = MokuText.meta, color = colors.textMuted)
                            Text(
                                listOfNotNull(
                                    "${plural(p.newTitles, "title", "titles")} added here",
                                    "${plural(p.sentTitles, "title", "titles")} sent there",
                                    plural(p.readChapters, "read chapter", "read chapters"),
                                    p.mergingFolders.takeIf { it.isNotEmpty() }?.let { "Merging folders: ${it.joinToString()}" },
                                ).joinToString("\n"),
                                style = MokuText.sub,
                                color = colors.textPrimary,
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                            if (busy) Text("Working…", style = MokuText.meta, color = colors.textMuted)
                            ActionChip("Cancel") { target = null; preview = null; pairError = null }
                            if (p == null) {
                                ActionChip("Continue", accent = true) { if (codeInput.count { it.isLetterOrDigit() } == 8) previewAt(t, codeInput) }
                            } else {
                                ActionChip("Pair with ${p.name}", accent = true) { pair() }
                            }
                        }
                    }
                } else {
                    SettingsRow("Scan QR code", icon = R.drawable.ph_device_mobile_light, subtitle = "Shown on Moku desktop under Settings › Sync") {
                        scanner.launch(ScanOptions().setPrompt("Scan the code on your other device").setBeepEnabled(false).setOrientationLocked(false))
                    }
                    nearby.filter { !it.paired }.forEach { d ->
                        GroupDivider()
                        SettingsRow(d.name, icon = R.drawable.ph_plus_light, subtitle = "Nearby", chevron = true) {
                            target = Target(d.name, listOf(d.address))
                            codeInput = ""
                            preview = null
                            pairError = null
                        }
                    }
                    GroupDivider()
                    SettingsRow(if (scanning) "Looking…" else "Look for nearby devices", icon = R.drawable.ph_arrows_clockwise_light) {
                        scope.launch { scan() }
                    }
                    GroupDivider()
                    val shown = code
                    SettingsRow(
                        if (shown != null) shown.first.chunked(4).joinToString(" ") else "Show this phone's code",
                        icon = R.drawable.ph_checks_light,
                        subtitle = if (shown != null) "Enter it on the other device. Works once, for 10 minutes." else "To pair from the other device instead",
                    ) { showCode() }
                }
            }
        }

        if (s.missing.isNotEmpty()) {
            item {
                SettingsGroup(label = "Extensions to install", footer = "Synced titles from these extensions appear once they're installed.") {
                    s.missing.forEachIndexed { i, m ->
                        if (i > 0) GroupDivider()
                        val examples = m.examples.takeIf { it.isNotEmpty() }?.joinToString(prefix = ": ")?.let { if (m.titles > m.examples.size) "$it…" else it }.orEmpty()
                        SettingsRow(
                            m.name,
                            icon = R.drawable.ph_puzzle_piece_light,
                            subtitle = "${plural(m.titles, "title", "titles")}$examples",
                            trailing = {
                                ActionChip(if (m.packageName in installing) "Installing…" else "Install", accent = true) {
                                    if (m.packageName !in installing) install(m)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
