package dev.moku.mobile.ui.reader

import dev.moku.mobile.ui.components.pressable
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import dev.moku.mobile.R
import dev.moku.mobile.backend.BackendUnitContent
import dev.moku.mobile.playback.HeaderMediaSourceFactory
import dev.moku.mobile.ui.components.MokuChip
import dev.moku.mobile.ui.components.MokuSectionLabel
import dev.moku.mobile.ui.settings.ActionChip
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuText
import dev.moku.mobile.ui.theme.MokuTheme
import kotlinx.coroutines.delay

private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
private const val SEEK_MS = 10_000L

private data class TextTrack(val label: String, val group: Tracks.Group)

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun VideoPlayerScreen(
    nav: ReaderNav,
    video: BackendUnitContent.Video,
    startSeconds: Double?,
    offline: Boolean,
    onProgress: (positionSeconds: Double, durationSeconds: Double) -> Unit,
) {
    val context = LocalContext.current
    val colors = MokuTheme.colors
    val activity = remember(context) { context.findActivity() }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val saveProgress by rememberUpdatedState(onProgress)

    val player = remember {
        ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(SEEK_MS)
            .setSeekForwardIncrementMs(SEEK_MS)
            .build()
    }
    var quality by remember(video) { mutableIntStateOf(video.qualities.indexOfFirst { it.url == video.streamUrl }.coerceAtLeast(0)) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var controls by remember { mutableStateOf(true) }
    var poke by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var textTracks by remember { mutableStateOf(emptyList<TextTrack>()) }
    var subtitle by remember { mutableIntStateOf(0) }
    var seekFlash by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }

    ReaderSystemBars(hidden = true)

    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity?.requestedOrientation = previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                ended = state == Player.STATE_ENDED
                if (state == Player.STATE_READY) error = null
            }

            override fun onPlayerError(e: PlaybackException) {
                error = e.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
            }

            override fun onTracksChanged(tracks: Tracks) {
                textTracks = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }.mapIndexed { i, g ->
                    val format = g.getTrackFormat(0)
                    TextTrack(format.label ?: format.language ?: "Track ${i + 1}", g)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            if (player.duration > 0) saveProgress(player.currentPosition / 1000.0, player.duration / 1000.0)
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(quality, attempt) {
        val variant = video.qualities.getOrNull(quality)
        val resume = if (started) player.currentPosition else ((startSeconds ?: 0.0) * 1000).toLong()
        player.setMediaSource(
            HeaderMediaSourceFactory.create(variant?.url ?: video.streamUrl, variant?.headers ?: video.headers, video.subtitles),
            resume,
        )
        player.prepare()
        player.playWhenReady = true
        started = true
    }

    LaunchedEffect(textTracks, subtitle) {
        val params = player.trackSelectionParameters.buildUpon()
        val track = textTracks.getOrNull(subtitle)
        if (track == null) {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, 0))
        }
        player.trackSelectionParameters = params.build()
    }

    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }

    LaunchedEffect(player) {
        var tick = 0
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it > 0 } ?: 0L
            buffered = player.bufferedPosition
            if (++tick % 20 == 0 && playing && duration > 0) saveProgress(position / 1000.0, duration / 1000.0)
            delay(250)
        }
    }

    LaunchedEffect(controls, playing, poke, settings) {
        if (controls && playing && !settings) {
            delay(3500)
            controls = false
        }
    }

    LaunchedEffect(seekFlash) {
        if (seekFlash != null) {
            delay(650)
            seekFlash = null
        }
    }

    LaunchedEffect(ended) {
        if (ended && duration > 0) saveProgress(duration / 1000.0, duration / 1000.0)
        if (ended && nav.next != null) {
            controls = true
            for (s in 5 downTo 1) {
                countdown = s
                delay(1000)
            }
            countdown = null
            nav.onNext()
        } else {
            countdown = null
        }
    }

    fun seekBy(forward: Boolean) {
        if (forward) player.seekForward() else player.seekBack()
        seekFlash = forward to ((seekFlash?.takeIf { it.first == forward }?.second ?: 0) + 10)
        poke++
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controls = !controls; poke++ },
                    onDoubleTap = { seekBy(forward = it.x > size.width / 2f) },
                )
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    subtitleView?.setStyle(
                        CaptionStyleCompat(
                            android.graphics.Color.WHITE,
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                            android.graphics.Color.BLACK,
                            null,
                        ),
                    )
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        seekFlash?.let { (forward, seconds) ->
            Box(Modifier.fillMaxSize().padding(horizontal = 64.dp), contentAlignment = if (forward) Alignment.CenterEnd else Alignment.CenterStart) {
                Text(if (forward) "+$seconds s" else "−$seconds s", style = MokuText.value, color = Color.White, modifier = Pill())
            }
        }

        if (buffering && error == null && !controls) {
            CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(36.dp).align(Alignment.Center))
        }

        AnimatedVisibility(controls || error != null, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    Modifier.fillMaxWidth().padding(MokuSpacing.Sp3).align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
                ) {
                    ChromeButton(R.drawable.ph_arrow_left_light, "Back", onClick = nav.onBack)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(nav.unitLabel, style = MokuText.body, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(nav.title.takeIf { it.isNotBlank() }, video.qualities.getOrNull(quality)?.label, "Offline".takeIf { offline }).joinToString(" · "),
                            style = MokuText.meta,
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (textTracks.isNotEmpty()) {
                        ChromeButton(
                            if (subtitle >= 0) R.drawable.ph_subtitles_light else R.drawable.ph_subtitles_slash_light,
                            "Subtitles",
                            active = subtitle >= 0,
                        ) { subtitle = if (subtitle >= 0) -1 else 0; poke++ }
                    }
                    ChromeButton(R.drawable.ph_device_rotate_light, "Rotate") {
                        activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        poke++
                    }
                    ChromeButton(R.drawable.ph_sliders_horizontal_light, "Playback settings") { settings = true }
                }

                Row(
                    Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp6),
                ) {
                    if (error != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                            Icon(painterResource(R.drawable.ph_warning_circle_light), contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                            Text("Playback failed", style = MokuText.heading, color = Color.White)
                            Text(error.orEmpty(), style = MokuText.meta, color = Color.White.copy(alpha = 0.6f))
                            Row(Modifier.padding(top = MokuSpacing.Sp2), horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                                ActionChip("Retry", icon = R.drawable.ph_arrow_clockwise_light, accent = true) { error = null; attempt++ }
                                if (video.qualities.size > 1) ActionChip("Try another source") { error = null; quality = (quality + 1) % video.qualities.size }
                            }
                        }
                    } else {
                        RoundControl(R.drawable.ph_arrow_counter_clockwise_light, "Back 10 seconds", 52) { seekBy(false) }
                        if (buffering) {
                            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(36.dp))
                            }
                        } else {
                            RoundControl(
                                if (playing) R.drawable.ph_pause_fill else R.drawable.ph_play_fill,
                                if (playing) "Pause" else "Play",
                                72,
                                accent = true,
                            ) {
                                if (ended) player.seekTo(0)
                                if (playing) player.pause() else player.play()
                                poke++
                            }
                        }
                        RoundControl(R.drawable.ph_arrow_clockwise_light, "Forward 10 seconds", 52) { seekBy(true) }
                    }
                }

                Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = MokuSpacing.Sp4, vertical = MokuSpacing.Sp3)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                        if (countdown != null) {
                            ActionChip("Cancel") { countdown = null; ended = false }
                        }
                        Spacer(Modifier.weight(1f))
                        if (nav.prev != null) ActionChip(nav.short(nav.prev) ?: "Previous", icon = R.drawable.ph_skip_back_fill, onClick = nav.onPrev)
                        if (nav.next != null) {
                            ActionChip(
                                countdown?.let { "Next in $it" } ?: (nav.short(nav.next) ?: "Next"),
                                icon = R.drawable.ph_skip_forward_fill,
                                accent = true,
                                onClick = nav.onNext,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3)) {
                        val shownPosition = scrub?.let { (it * duration).toLong() } ?: position
                        Text(formatTime(shownPosition), style = MokuText.sub, color = Color.White, modifier = Modifier.widthIn(min = 44.dp))
                        ThinSlider(
                            fraction = scrub ?: if (duration > 0) position.toFloat() / duration else 0f,
                            buffered = if (duration > 0) buffered.toFloat() / duration else 0f,
                            modifier = Modifier.weight(1f),
                        ) { f, commit ->
                            poke++
                            if (commit) {
                                player.seekTo((f * duration).toLong())
                                scrub = null
                            } else {
                                scrub = f
                            }
                        }
                        Text(formatTime(duration), style = MokuText.sub, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.widthIn(min = 44.dp))
                    }
                }
            }
        }
    }

    if (settings) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { settings = false }, sheetState = sheetState, containerColor = colors.bgSurface) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SheetColumn("Playback") {
                    MokuSectionLabel("Speed")
                    Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                        SPEEDS.forEach { s -> MokuChip("${s}x".replace(".0x", "x"), selected = speed == s) { speed = s } }
                    }
                    if (video.qualities.size > 1) {
                        MokuSectionLabel("Source")
                        Column(verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                            video.qualities.forEachIndexed { i, q ->
                                OptionRow(q.label.ifBlank { "Source ${i + 1}" }, selected = i == quality) { quality = i; settings = false }
                            }
                        }
                    }
                    if (textTracks.isNotEmpty()) {
                        MokuSectionLabel("Subtitles")
                        Column(verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                            OptionRow("Off", selected = subtitle < 0) { subtitle = -1 }
                            textTracks.forEachIndexed { i, t -> OptionRow(t.label, selected = subtitle == i) { subtitle = i } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundControl(@DrawableRes icon: Int, label: String, size: Int, accent: Boolean = false, onClick: () -> Unit) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Xxl + 4.dp)
    Box(
        Modifier
            .size(size.dp)
            .pressable(shape, onClick = onClick)
            .background(if (accent) colors.accent else Color.White.copy(alpha = 0.12f))
            .border(1.dp, if (accent) colors.accentBright.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.16f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = if (accent) colors.accentFg else Color.White, modifier = Modifier.size((size * 0.42f).dp))
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Lg)
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .pressable(shape, onClick = onClick)
            .background(if (selected) colors.accentMuted else colors.bgRaised)
            .border(1.dp, if (selected) colors.accentDim else colors.borderDim, shape)
            .padding(horizontal = MokuSpacing.Sp3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MokuText.body, color = if (selected) colors.accentFg else colors.textSecondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(painterResource(R.drawable.ph_check_bold), contentDescription = null, tint = colors.accentFg, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun Pill(): Modifier =
    Modifier.clip(RoundedCornerShape(MokuRadius.Xl)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = MokuSpacing.Sp4, vertical = MokuSpacing.Sp2)

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
