package dev.moku.mobile

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import dev.moku.mobile.backend.CoverHeaders
import dev.moku.mobile.core.CrashReports
import dev.moku.mobile.core.NotificationPermission
import dev.moku.mobile.ui.components.MokuToast
import dev.moku.mobile.core.TsunaguCore
import dev.moku.mobile.library.LocalLibraryRepository
import dev.moku.mobile.library.MediaCache
import dev.moku.mobile.tracking.TrackSync
import dev.moku.mobile.ui.components.ContentTypeFilterController
import dev.moku.mobile.ui.components.MokuBottomNav
import dev.moku.mobile.ui.components.LocalReselect
import dev.moku.mobile.ui.components.MokuDestination
import dev.moku.mobile.ui.components.MokuToastHost
import dev.moku.mobile.ui.components.MotionOverlay
import dev.moku.mobile.ui.components.OverlayStyle
import dev.moku.mobile.ui.components.PushSwitcher
import dev.moku.mobile.ui.components.SlideSwitcher
import dev.moku.mobile.ui.components.ServerUnreachableBanner
import dev.moku.mobile.ui.downloads.DownloadsScreen
import dev.moku.mobile.ui.extensions.ExtensionsScreen
import dev.moku.mobile.ui.home.HomeScreen
import dev.moku.mobile.ui.library.LibraryScreen
import dev.moku.mobile.ui.reader.MediaDetailScreen
import dev.moku.mobile.ui.reader.ReaderScreen
import dev.moku.mobile.ui.search.SearchScreen
import dev.moku.mobile.ui.settings.SettingsScreen
import dev.moku.mobile.ui.theme.MokuAppTheme
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuTheme
import dev.moku.mobile.ui.theme.ThemeController
import dev.moku.mobile.remote.ServerAuthInterceptor
import dev.moku.mobile.remote.ServerSession
import dev.moku.mobile.sync.ServerSync
import dev.moku.mobile.update.AppUpdater
import okhttp3.OkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    // The launch sync covers the first start; later returns to the app pull what other devices changed.
    @Volatile private var launched = false

    override fun onStart() {
        super.onStart()
        if (launched) lifecycleScope.launch { ServerSync.check(this@MainActivity) }
    }

    override fun onDestroy() {
        NotificationPermission.unregister()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NotificationPermission.register(this)
        if (savedInstanceState == null && CrashReports.isNew(this)) {
            MokuToast.show("Moku closed unexpectedly last time", action = "Share report") { CrashReports.share(this) }
        }
        ThemeController.init(this)
        ContentTypeFilterController.init(this)
        ServerSync.init(this)
        CoverHeaders.init(this)
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .respectCacheHeaders(false)
                .crossfade(180)
                .okHttpClient { OkHttpClient.Builder().addInterceptor(ServerAuthInterceptor).addInterceptor(CoverHeaders).build() }
                .diskCache { DiskCache.Builder().directory(cacheDir.resolve("image_cache")).maxSizeBytes(512L * 1024 * 1024).build() }
                .build(),
        )
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { TsunaguCore.start(this@MainActivity) }
                .onFailure { Log.e("MainActivity", "starting Tsunagu core failed", it) }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            delay(4_000)
            launched = true
            runCatching { LocalLibraryRepository(this@MainActivity).dropMissingDownloads() }
            // Runs even offline: a server that can't be reached has to say so, not quietly show stale titles.
            ServerSync.check(this@MainActivity)
            if (!isOnline()) return@launch
            runCatching { MediaCache(this@MainActivity).syncLibrary() }.onFailure { Log.w("MainActivity", "library metadata sync failed", it) }
            runCatching { TrackSync(this@MainActivity).syncLibrary() }.onFailure { Log.w("MainActivity", "tracker sync failed", it) }
            AppUpdater.checkQuietly(this@MainActivity)
        }

        setContent {
            MokuAppTheme {
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
    var reselect by remember { mutableIntStateOf(0) }
    val tabs = rememberSaveableStateHolder()
    val onOpenMedia: (String, String) -> Unit = { originId, mediaId -> detailTarget = originId to mediaId }
    val openExtensions = { destination = MokuDestination.Settings; extensionsOpen = true }

    BackHandler(enabled = destination != MokuDestination.Home) { destination = MokuDestination.Home; extensionsOpen = false }
    BackHandler(enabled = extensionsOpen) { extensionsOpen = false }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MokuTheme.colors.bgVoid,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                MokuBottomNav(
                    current = destination,
                    onSelect = { destination = it; extensionsOpen = false },
                    onReselect = { if (it == MokuDestination.Settings && extensionsOpen) extensionsOpen = false else reselect++ },
                )
            },
        ) { padding ->
            // Each screen applies the status-bar inset itself so Home can draw under it.
            CompositionLocalProvider(LocalReselect provides reselect) {
                SlideSwitcher(
                    target = destination,
                    order = { it.ordinal },
                    modifier = Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()),
                ) { tab ->
                    tabs.SaveableStateProvider(tab.name) {
                        when (tab) {
                            MokuDestination.Home -> HomeScreen(
                                onSearchClick = { destination = MokuDestination.Search },
                                onExtensionsClick = openExtensions,
                                onOpenMedia = onOpenMedia,
                            )
                            MokuDestination.Search -> SearchScreen(onOpenMedia = onOpenMedia, onOpenExtensions = openExtensions)
                            MokuDestination.Library -> LibraryScreen(onOpenMedia = onOpenMedia, onBrowse = { destination = MokuDestination.Search })
                            MokuDestination.Downloads -> DownloadsScreen(onOpenMedia = onOpenMedia)
                            MokuDestination.Settings -> PushSwitcher(extensionsOpen, depth = { if (it) 1 else 0 }) { open ->
                                if (open) {
                                    ExtensionsScreen(onBack = { extensionsOpen = false })
                                } else {
                                    SettingsScreen(onOpenExtensions = { extensionsOpen = true })
                                }
                            }
                        }
                    }
                }
            }
        }

        MotionOverlay(target = detailTarget, onBack = { detailTarget = null }) { (originId, mediaId) ->
            Box(Modifier.fillMaxSize().background(MokuTheme.colors.bgVoid)) {
                MediaDetailScreen(
                    originId = originId,
                    mediaId = mediaId,
                    onBack = { detailTarget = null },
                    onOpenUnit = { unit -> readerTarget = Triple(originId, mediaId, unit.id) },
                    active = readerTarget == null,
                )
            }
        }

        MotionOverlay(target = readerTarget, onBack = { readerTarget = null }, style = OverlayStyle.Zoom, predictiveBack = false) { (originId, mediaId, unitId) ->
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                ReaderScreen(originId = originId, mediaId = mediaId, unitId = unitId, onBack = { readerTarget = null })
            }
        }

        val context = LocalContext.current
        val reachable by ServerSession.reachable.collectAsState()
        val sync by ServerSync.status.collectAsState()
        val serverMode by ServerSession.serverMode.collectAsState()
        val showBanner = !reachable && serverMode && readerTarget == null
        LaunchedEffect(reachable) { if (!reachable) ServerSync.scheduleRetry(context) }
        val bottom = if (detailTarget == null && readerTarget == null) 84.dp else MokuSpacing.Sp4

        ServerUnreachableBanner(
            visible = showBanner,
            server = ServerSession.current?.name ?: ServerSession.current?.baseUrl.orEmpty(),
            retrying = sync.running,
            onRetry = { ServerSync.retryNow(context) },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = bottom),
        )

        MokuToastHost(
            bottomPadding = bottom + if (showBanner) 64.dp else 0.dp,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
