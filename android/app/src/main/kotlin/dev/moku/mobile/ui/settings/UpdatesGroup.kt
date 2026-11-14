package dev.moku.mobile.ui.settings

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.moku.mobile.BuildConfig
import dev.moku.mobile.R
import dev.moku.mobile.ui.components.MokuToast
import dev.moku.mobile.update.AppUpdater
import dev.moku.mobile.update.UpdateState
import kotlinx.coroutines.launch

/** The "Update to …" row that sits at the top of Settings while a newer release is out. */
@Composable
internal fun UpdateAvailableRow(onOpen: () -> Unit) {
    val release by AppUpdater.available.collectAsState()
    val r = release ?: return
    SettingsGroup {
        SettingsRow("Update to Moku ${r.version}", icon = R.drawable.ph_download_simple_bold, subtitle = "You have ${BuildConfig.VERSION_NAME}", chevron = true, onClick = onOpen)
    }
}

internal fun LazyListScope.updatesGroup() {
    item { UpdatesGroup() }
}

@Composable
private fun UpdatesGroup() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val release by AppUpdater.available.collectAsState()
    val state by AppUpdater.state.collectAsState()
    // Re-read the "checked … ago" stamp after a manual check.
    var checks by remember { mutableIntStateOf(0) }
    val checkedAt = remember(checks, state) { AppUpdater.lastCheckedAt(context) }

    if (!AppUpdater.enabled) {
        SettingsGroup("Updates", footer = "Debug builds install as a separate app and don't update themselves.") {
            SettingsRow("Updates are off", icon = R.drawable.ph_arrows_clockwise_light, subtitle = "Moku Debug ${BuildConfig.VERSION_NAME}")
        }
        return
    }

    val r = release
    SettingsGroup(
        "Updates",
        footer = when {
            r != null && r.notes.isNotBlank() -> r.notes.take(600)
            else -> "New versions come from GitHub releases. Updating keeps your library, downloads, server and settings."
        },
    ) {
        if (r == null) {
            SettingsRow(
                "Check for updates",
                icon = R.drawable.ph_arrows_clockwise_light,
                subtitle = when (val s = state) {
                    UpdateState.Checking -> "Checking…"
                    is UpdateState.Failed -> s.message
                    else -> checkedAt?.let { "Up to date · checked " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) }
                        ?: "Moku ${BuildConfig.VERSION_NAME}"
                },
            ) {
                if (state == UpdateState.Checking) return@SettingsRow
                scope.launch {
                    runCatching { AppUpdater.check(context) }
                        .onSuccess { if (it == null) MokuToast.show("Moku is up to date") }
                    checks++
                }
            }
            return@SettingsGroup
        }

        val busy = state is UpdateState.Downloading || state is UpdateState.Installing
        SettingsRow(
            "Install Moku ${r.version}",
            icon = R.drawable.ph_download_simple_bold,
            subtitle = when (val s = state) {
                is UpdateState.Downloading -> "Downloading… ${(s.fraction * 100).toInt()}%"
                is UpdateState.Installing -> "Waiting for Android to install it"
                is UpdateState.Failed -> s.message
                else -> "You have ${BuildConfig.VERSION_NAME}" + if (r.apkSize > 0) " · ${r.apkSize / (1024 * 1024)} MB download" else ""
            },
        ) {
            if (busy) return@SettingsRow
            if (!AppUpdater.canInstall(context)) {
                MokuToast.show("Allow Moku to install updates, then tap Install again", long = true)
                AppUpdater.openInstallPermission(context)
                return@SettingsRow
            }
            scope.launch { AppUpdater.install(context, r) }
        }
        if (r.pageUrl.isNotBlank()) {
            GroupDivider()
            SettingsRow("Release notes", icon = R.drawable.ph_arrow_square_out_light, chevron = true) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.pageUrl)))
            }
        }
    }
}
