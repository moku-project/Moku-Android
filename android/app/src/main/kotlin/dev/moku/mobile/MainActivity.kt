package dev.moku.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.moku.mobile.ui.components.ContentTypeFilterController
import dev.moku.mobile.ui.components.MokuBottomNav
import dev.moku.mobile.ui.components.MokuDestination
import dev.moku.mobile.ui.downloads.DownloadsScreen
import dev.moku.mobile.ui.extensions.ExtensionsScreen
import dev.moku.mobile.ui.home.HomeScreen
import dev.moku.mobile.ui.library.LibraryScreen
import dev.moku.mobile.ui.reader.MediaDetailScreen
import dev.moku.mobile.ui.reader.ReaderScreen
import dev.moku.mobile.ui.search.SearchScreen
import dev.moku.mobile.ui.settings.SettingsScreen
import dev.moku.mobile.ui.theme.MokuAppTheme
import dev.moku.mobile.ui.theme.MokuTheme
import dev.moku.mobile.ui.theme.ThemeController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ThemeController.init(this)
        ContentTypeFilterController.init(this)

        setContent {
            MokuAppTheme(themeId = ThemeController.current) {
                Surface(modifier = Modifier.fillMaxSize(), color = MokuTheme.colors.bgVoid) {
                    MokuApp()
                }
            }
        }
    }
}

@Composable
private fun MokuApp() {
    var destination by remember { mutableStateOf(MokuDestination.Home) }
    // Extensions management lives under Settings (drill-down), not its own bottom-nav tab —
    // it's a management surface, not something reached daily like Search/Library/Downloads.
    var extensionsOpen by remember { mutableStateOf(false) }
    // Detail/Reader are full-screen overlays outside the tab system entirely (no bottom nav,
    // reachable from any tab that lists media) rather than another MokuDestination case.
    var detailTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var readerTarget by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    val onOpenMedia: (String, String) -> Unit = { originId, mediaId -> detailTarget = originId to mediaId }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MokuTheme.colors.bgVoid,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                MokuBottomNav(
                    current = destination,
                    onSelect = { destination = it; extensionsOpen = false },
                )
            },
        ) { padding ->
            // Only the bottom inset (nav bar, from the bottom bar's own measurement) is applied
            // here — the top/status-bar inset is left to each screen so Home's hero art can bleed
            // full-bleed under the status bar instead of every screen getting a blanket top pad.
            Box(modifier = Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                when (destination) {
                    MokuDestination.Home -> HomeScreen(
                        onSearchClick = { destination = MokuDestination.Search },
                        onExtensionsClick = { destination = MokuDestination.Settings; extensionsOpen = true },
                        onOpenMedia = onOpenMedia,
                    )
                    MokuDestination.Search -> SearchScreen(onOpenMedia = onOpenMedia)
                    MokuDestination.Library -> LibraryScreen(onOpenMedia = onOpenMedia)
                    MokuDestination.Downloads -> DownloadsScreen()
                    MokuDestination.Settings -> {
                        if (extensionsOpen) {
                            ExtensionsScreen(onBack = { extensionsOpen = false })
                        } else {
                            SettingsScreen(onOpenExtensions = { extensionsOpen = true })
                        }
                    }
                }
            }
        }

        detailTarget?.let { (originId, mediaId) ->
            Box(Modifier.fillMaxSize().background(MokuTheme.colors.bgVoid)) {
                MediaDetailScreen(
                    originId = originId,
                    mediaId = mediaId,
                    onBack = { detailTarget = null },
                    onOpenUnit = { unit -> readerTarget = Triple(originId, mediaId, unit.id) },
                )
            }
        }

        readerTarget?.let { (originId, mediaId, unitId) ->
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                ReaderScreen(originId = originId, mediaId = mediaId, unitId = unitId, onBack = { readerTarget = null })
            }
        }
    }
}
