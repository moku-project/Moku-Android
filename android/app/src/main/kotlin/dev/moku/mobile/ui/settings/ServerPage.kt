package dev.moku.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import dev.moku.mobile.R
import dev.moku.mobile.library.LocalLibraryRepository
import dev.moku.mobile.remote.AuthRequiredException
import dev.moku.mobile.remote.GraphQLException
import dev.moku.mobile.remote.ServerSession
import dev.moku.mobile.remote.TsunaguServer
import dev.moku.mobile.remote.strOrNull
import dev.moku.mobile.sync.ServerOrigin
import dev.moku.mobile.sync.ServerSync
import dev.moku.mobile.ui.components.MokuToast
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuText
import dev.moku.mobile.ui.theme.MokuTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import java.io.IOException

private enum class Credential(val placeholder: String, val hint: String) {
    PASSWORD("Server password", "This server is protected with a password."),
    TOKEN("Access token", "This server only accepts requests with its access token, set by TSUNAGU_TOKEN."),
}

private fun normalize(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    return if (trimmed.contains("://")) trimmed else "http://$trimmed"
}

/** Signs in to [baseUrl], returning the server to save, or the credential still needed to get there. */
private suspend fun signIn(baseUrl: String, credential: Credential?, secret: String): Pair<TsunaguServer?, Credential?> {
    val client = ServerSession.client
    val passwordRequired = client.passwordRequired(baseUrl)
    val token = when {
        passwordRequired && credential != Credential.PASSWORD -> return null to Credential.PASSWORD
        credential == Credential.PASSWORD -> client.login(baseUrl, secret).token
        credential == Credential.TOKEN -> secret.trim()
        else -> null
    }
    val about = try {
        client.query(TsunaguServer(baseUrl, token, null, ""), "query About { about { name instanceId } }")["about"]?.jsonObject
    } catch (e: AuthRequiredException) {
        if (token == null) return null to Credential.TOKEN
        throw IOException(if (credential == Credential.TOKEN) "That token wasn't accepted" else "The server turned the session down")
    } catch (e: GraphQLException) {
        if (e.code == "GRAPHQL_VALIDATION_FAILED") throw IOException("This Tsunagu is too old for Moku Mobile. Update it, then connect again.")
        throw e
    }
    val name = about?.strOrNull("name")?.takeIf { it.isNotBlank() } ?: "Tsunagu"
    val instanceId = about?.strOrNull("instanceId") ?: throw IOException("The server didn't say who it is")
    return TsunaguServer(baseUrl, token, name, instanceId) to null
}

@Composable
internal fun ServerPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = MokuTheme.colors
    val scope = rememberCoroutineScope()
    val status by ServerSync.status.collectAsState()
    val serverMode by ServerSession.serverMode.collectAsState()
    val reachable by ServerSession.reachable.collectAsState()
    var server by remember { mutableStateOf(ServerSession.current) }
    var url by remember { mutableStateOf(server?.baseUrl.orEmpty()) }
    var secret by remember { mutableStateOf("") }
    var credential by remember { mutableStateOf<Credential?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var downloadCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(server, confirmDisconnect) {
        val instance = server?.instanceId ?: return@LaunchedEffect
        downloadCount = LocalLibraryRepository(context).getDownloads().count { ServerOrigin.parse(it.originId)?.instanceId == instance }
    }

    fun connect(target: String) = scope.launch {
        if (busy || target.isBlank()) return@launch
        busy = true
        error = null
        try {
            val (signedIn, needed) = signIn(normalize(target), credential, secret)
            if (signedIn == null) {
                credential = needed
                return@launch
            }
            ServerSession.save(context, signedIn)
            ServerSync.check(context)
            MokuToast.show("Connected to ${signedIn.name ?: "server"}")
            server = signedIn
            url = signedIn.baseUrl
            secret = ""
            credential = null
        } catch (e: Exception) {
            error = e.message ?: "Couldn't connect"
        } finally {
            busy = false
        }
    }

    fun disconnect(keepDownloads: Boolean) = scope.launch {
        ServerSync.disconnect(context, keepDownloads)
        server = null
        url = ""
        confirmDisconnect = false
        MokuToast.show(if (keepDownloads) "Disconnected. Its downloads are still on this phone." else "Disconnected")
    }

    SettingsPage("Server", onBack = onBack) {
        val current = server
        if (current == null || status.needsSignIn) {
            item {
                SettingsGroup(
                    label = if (current == null) "Connect" else "Sign in again",
                    footer = credential?.hint
                        ?: "Connect to Tsunagu on your computer to use its library, folders, progress and sources here. Download chapters to your phone to read them away from the server.",
                ) {
                    Column(Modifier.fillMaxWidth().padding(MokuSpacing.Sp3), verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                        if (current == null) {
                            TextInput(url, { url = it; credential = null; error = null }, "http://192.168.1.10:6007", Modifier.fillMaxWidth(), mono = true, keyboard = KeyboardType.Uri)
                        }
                        credential?.let { ask ->
                            TextInput(secret, { secret = it; error = null }, ask.placeholder, Modifier.fillMaxWidth(), password = true)
                        }
                        error?.let { Text(it, style = MokuText.meta, color = Danger) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            if (busy) Text("Connecting…", style = MokuText.meta, color = colors.textMuted, modifier = Modifier.padding(end = MokuSpacing.Sp3))
                            ActionChip(if (current == null) "Connect" else "Sign in", icon = R.drawable.ph_cloud_check_light, accent = true) {
                                connect(current?.baseUrl ?: url)
                            }
                        }
                    }
                }
            }
        }
        if (current != null) {
            item {
                SettingsGroup(
                    footer = if (serverMode) {
                        "Library, folders, progress and Discover come from ${current.name ?: "the server"}. Turning this off keeps you signed in and keeps its downloads."
                    } else {
                        "Showing this phone's own library. ${current.name ?: "The server"}'s downloads stay on this phone and come back with server mode."
                    },
                ) {
                    SettingsToggleRow(
                        "Server mode",
                        checked = serverMode,
                        onCheckedChange = { ServerSession.setServerMode(context, it) },
                        icon = R.drawable.ph_hard_drives_light,
                    )
                }
            }
            item {
                SettingsGroup(footer = "Progress read on this phone is saved here first and sent as soon as the server can be reached.") {
                    SettingsRow(
                        current.name ?: "Tsunagu",
                        icon = if (reachable) R.drawable.ph_cloud_check_light else R.drawable.ph_cloud_slash_light,
                        subtitle = current.baseUrl,
                    )
                    GroupDivider()
                    SettingsRow(
                        if (reachable) "Connected" else "Can't connect",
                        icon = R.drawable.ph_arrows_clockwise_light,
                        subtitle = when {
                            status.running -> "Checking…"
                            !reachable -> "Tap to try again"
                            else -> "Tap to check"
                        },
                        value = status.pending.takeIf { it > 0 }?.let { "$it waiting" },
                    ) {
                        scope.launch {
                            ServerSync.check(context)
                                .onSuccess { MokuToast.show("${current.name ?: "Server"} is reachable") }
                                .onFailure { MokuToast.show(ServerSync.status.value.error ?: "Couldn't reach the server") }
                        }
                    }
                }
            }
            item {
                SettingsGroup(
                    footer = when {
                        !confirmDisconnect -> null
                        status.pending > 0 -> "${status.pending} progress updates haven't reached the server. Keeping downloads keeps them too, and they're sent if you sign in to this server again."
                        else -> "Nothing on the server changes either way."
                    },
                ) {
                    if (!confirmDisconnect) {
                        SettingsRow("Disconnect", icon = R.drawable.ph_x_light, danger = true) { confirmDisconnect = true }
                    } else {
                        SettingsRow(
                            "Disconnect, keep downloads",
                            icon = R.drawable.ph_download_simple_bold,
                            subtitle = if (downloadCount > 0) "$downloadCount stay on this phone for when you sign in again" else "There are none right now",
                        ) { disconnect(keepDownloads = true) }
                        GroupDivider()
                        SettingsRow(
                            "Disconnect and delete downloads",
                            icon = R.drawable.ph_trash_light,
                            danger = true,
                            subtitle = if (downloadCount > 0) "Removes $downloadCount from this phone" else null,
                        ) { disconnect(keepDownloads = false) }
                        GroupDivider()
                        SettingsRow("Cancel", icon = R.drawable.ph_x_light) { confirmDisconnect = false }
                    }
                }
            }
        }
    }
}
