package dev.moku.mobile

import android.os.Bundle
import android.util.Log
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
import androidx.lifecycle.lifecycleScope
import dev.moku.mobile.core.TsunaguCore
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ThemeController.init(this)
        ContentTypeFilterController.init(this)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { TsunaguCore.start(this@MainActivity) }
                .onFailure { Log.e("MainActivity", "starting Tsunagu core failed", it) }
        }

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
    var extensionsOpen by remember { mutableStateOf(false) }
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
            // Each screen applies the status-bar inset itself so Home can draw under it.
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
