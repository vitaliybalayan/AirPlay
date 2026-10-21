package com.example.airplay

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.Button
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.airplay.ui.theme.AirPlayTheme
import java.util.Locale

import com.fang.myapplication.RaopServer

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "AirPlayMain"
        const val EXTRA_AUTO_LAUNCHED = "auto_launched"
        // After the sender disconnects, an auto-opened screen closes and returns to what was on the TV.
        private const val AUTO_CLOSE_DELAY_MS = 2500L
        private const val AUTO_CLOSE_IDLE_TIMEOUT_MS = 20_000L

        /** Read by the service to decide whether it needs to bring the UI up. */
        @Volatile
        var isInForeground = false
            private set
    }

    private val videoWidthState = androidx.compose.runtime.mutableStateOf(0)
    private val videoHeightState = androidx.compose.runtime.mutableStateOf(0)
    private val isVideoPlayingState = androidx.compose.runtime.mutableStateOf(false)
    private val nowPlayingState = androidx.compose.runtime.mutableStateOf<NowPlayingUi?>(null)
    private val overlayGrantedState = androidx.compose.runtime.mutableStateOf(true)
    private val showSplashState = androidx.compose.runtime.mutableStateOf(false)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var surfaceView: SurfaceView? = null
    private var autoLaunched = false
    private val autoClose = Runnable {
        if (autoLaunched && !isVideoPlayingState.value && nowPlayingState.value == null) {
            Log.i(TAG, "Sender gone, closing auto-opened receiver screen")
            finish()
        }
    }

    private val server: RaopServer
        get() = AirPlayRuntime.server(this)

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AirPlayService.start(this)
        handleLaunchIntent(intent)
        // Intro only on a cold, manual start: when opened for mirroring the video must appear at once.
        showSplashState.value = savedInstanceState == null && !autoLaunched

        setContent {
            AirPlayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    AirPlayScreen(
                        deviceName = AirPlayRuntime.DEVICE_NAME,
                        videoWidth = videoWidthState.value,
                        videoHeight = videoHeightState.value,
                        isVideoPlaying = isVideoPlayingState.value,
                        nowPlaying = nowPlayingState.value,
                        audioClockMs = { AirPlayRuntime.currentServer()?.lastAudioPacketRealtimeMs ?: 0L },
                        playbackPositionSec = { audiblePositionSec() },
                        autostartEnabled = overlayGrantedState.value,
                        onEnableAutostart = { openOverlaySettings() },
                        showSplash = showSplashState.value,
                        onSplashFinished = { showSplashState.value = false }
                    ) { view ->
                        surfaceView = view
                        server.attachSurfaceView(view)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val server = server
        server.setVideoSizeListener(object : RaopServer.VideoSizeListener {
            override fun onVideoSizeChanged(width: Int, height: Int) {
                runOnUiThread { applyVideoSize(width, height) }
            }
        })
        server.setNowPlayingListener(RaopServer.NowPlayingListener { np ->
            val ui = toUi(np, nowPlayingState.value)
            runOnUiThread { applyNowPlaying(ui) }
        })
        // Catch up on whatever started while this screen was not around.
        applyVideoSize(server.videoWidth, server.videoHeight)
        applyNowPlaying(toUi(server.nowPlaying, nowPlayingState.value))
        surfaceView?.let { server.attachSurfaceView(it) }
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        overlayGrantedState.value = AirPlayRuntime.canShowOverOtherApps(this)
    }

    override fun onPause() {
        isInForeground = false
        super.onPause()
    }

    override fun onStop() {
        AirPlayRuntime.currentServer()?.let { server ->
            server.setVideoSizeListener(null)
            server.setNowPlayingListener(null)
            surfaceView?.let { server.detachSurfaceView(it) }
        }
        mainHandler.removeCallbacks(autoClose)
        super.onStop()
    }

    override fun onDestroy() {
        // The receiver keeps running in AirPlayService; only this screen goes away.
        Log.i(TAG, "onDestroy")
        super.onDestroy()
    }

    private fun handleLaunchIntent(intent: Intent?) {
        autoLaunched = intent?.getBooleanExtra(EXTRA_AUTO_LAUNCHED, false) == true
        if (autoLaunched) {
            // Nothing arrived after all (sender gave up): don't leave the screen hanging.
            mainHandler.removeCallbacks(autoClose)
            mainHandler.postDelayed(autoClose, AUTO_CLOSE_IDLE_TIMEOUT_MS)
        }
    }

    private fun applyVideoSize(width: Int, height: Int) {
        videoWidthState.value = width
        videoHeightState.value = height
        isVideoPlayingState.value = width > 0 && height > 0
        scheduleAutoCloseCheck()
    }

    private fun applyNowPlaying(ui: NowPlayingUi?) {
        nowPlayingState.value = ui
        scheduleAutoCloseCheck()
    }

    private fun scheduleAutoCloseCheck() {
        if (!autoLaunched) return
        mainHandler.removeCallbacks(autoClose)
        if (!isVideoPlayingState.value && nowPlayingState.value == null) {
            mainHandler.postDelayed(autoClose, AUTO_CLOSE_DELAY_MS)
        }
    }

    private fun toUi(np: RaopServer.NowPlaying, previous: NowPlayingUi?): NowPlayingUi? {
        if (!np.active) return null
        // Covers can be a few hundred KB; decode each image only once.
        val cover = when {
            np.coverArt == null -> null
            previous?.coverSource === np.coverArt -> previous.cover
            else -> BitmapFactory.decodeByteArray(np.coverArt, 0, np.coverArt.size)?.asImageBitmap()
        }
        return NowPlayingUi(
            title = np.title,
            artist = np.artist,
            album = np.album,
            cover = cover,
            coverSource = np.coverArt,
            durationSec = np.durationSec,
            positionSec = np.positionSec,
            positionAtMs = np.positionAtMs
        )
    }

    /** Seconds into the track of what is audible now, from the RTP clock; null if unknown. */
    private fun audiblePositionSec(): Double? {
        val server = AirPlayRuntime.currentServer() ?: return null
        val start = server.trackStartRtp
        val playing = server.playingRtpTimestamp
        if (start < 0 || playing < 0) return null
        return ((playing - start) and 0xFFFFFFFFL) / 44100.0
    }

    /** Opens the system "display over other apps" toggle for this app, falling back to app settings. */
    private fun openOverlaySettings() {
        val pkg = android.net.Uri.parse("package:$packageName")
        val intents = listOf(
            Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg),
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
            Intent(android.provider.Settings.ACTION_SETTINGS)
        )
        for (candidate in intents) {
            try {
                startActivity(candidate)
                return
            } catch (e: android.content.ActivityNotFoundException) {
                Log.w(TAG, "No settings screen for ${candidate.action}")
            }
        }
    }
}

@Composable
fun AirPlayScreen(
    deviceName: String,
    videoWidth: Int,
    videoHeight: Int,
    isVideoPlaying: Boolean,
    nowPlaying: NowPlayingUi?,
    audioClockMs: () -> Long,
    playbackPositionSec: () -> Double?,
    autostartEnabled: Boolean,
    onEnableAutostart: () -> Unit,
    showSplash: Boolean,
    onSplashFinished: () -> Unit,
    onSurfaceReady: (SurfaceView) -> Unit
) {
    val isRussian = Locale.getDefault().language == "ru"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06080B))
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.Center),
            factory = { context ->
                AspectRatioSurfaceView(context).apply {
                    setZOrderMediaOverlay(false)
                    onSurfaceReady(this)
                }
            },
            update = { view ->
                view.setVideoSize(videoWidth, videoHeight)
            }
        )

        var showHowTo by remember { mutableStateOf(false) }
        LaunchedEffect(isVideoPlaying, nowPlaying == null) {
            // Mirroring started or the audio session ended: the walkthrough has served its purpose.
            if (isVideoPlaying || nowPlaying == null) showHowTo = false
        }

        if (!isVideoPlaying && nowPlaying != null) {
            NowPlayingScreen(
                nowPlaying = nowPlaying,
                audioClockMs = audioClockMs,
                playbackPositionSec = playbackPositionSec,
                isRussian = isRussian,
                howToVisible = showHowTo,
                onShowHowTo = { showHowTo = true }
            )
        }

        if (!isVideoPlaying && nowPlaying == null) {
            IdleHomeScreen(
                deviceName = deviceName,
                isRussian = isRussian,
                autostartEnabled = autostartEnabled,
                onEnableAutostart = onEnableAutostart,
                animateIn = true
            )
        }

        if (showHowTo) {
            MirroringHowToOverlay(isRussian = isRussian, onClose = { showHowTo = false })
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = showSplash && !isVideoPlaying,
            enter = fadeIn(tween(0)),
            exit = fadeOut(tween(600))
        ) {
            SplashIntro(isRussian = isRussian, onFinished = onSplashFinished)
        }
    }
}

data class NowPlayingUi(
    val title: String?,
    val artist: String?,
    val album: String?,
    val cover: ImageBitmap?,
    val coverSource: ByteArray?,
    val durationSec: Double,
    val positionSec: Double,
    val positionAtMs: Long
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NowPlayingScreen(
    nowPlaying: NowPlayingUi,
    audioClockMs: () -> Long,
    playbackPositionSec: () -> Double?,
    isRussian: Boolean,
    howToVisible: Boolean,
    onShowHowTo: () -> Unit
) {
    val howToFocus = remember { FocusRequester() }
    LaunchedEffect(howToVisible) {
        // The remote needs something focused; give it back to the button when the walkthrough closes.
        if (!howToVisible) howToFocus.requestFocus()
    }
    // Progress is extrapolated between the sender's occasional updates.
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(nowPlaying) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(200)
        }
    }
    // Stop advancing shortly after audio stops arriving (pause), instead of running ahead.
    val lastAudio = audioClockMs()
    val playedUntil = if (lastAudio > 0) minOf(now, lastAudio + 500) else now
    val elapsed = if (nowPlaying.positionAtMs > 0) {
        ((playedUntil - nowPlaying.positionAtMs) / 1000.0).coerceAtLeast(0.0)
    } else 0.0
    val estimated = (nowPlaying.positionSec + elapsed).coerceAtMost(nowPlaying.durationSec)
    // Prefer the position of the audio actually playing; the sender's progress runs ahead of it
    // by a buffering delay that varies per session.
    val audible = playbackPositionSec()?.takeIf { now >= 0 && it <= nowPlaying.durationSec + 2 }
    val position = audible?.coerceAtMost(nowPlaying.durationSec) ?: estimated

    val prefs = androidx.compose.ui.platform.LocalContext.current
        .getSharedPreferences("now_playing", android.content.Context.MODE_PRIVATE)
    // Remembered across tracks and launches: someone who hid lyrics keeps them hidden.
    var lyricsHidden by remember { mutableStateOf(prefs.getBoolean("lyrics_hidden", false)) }
    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    var lyricsStatus by remember { mutableStateOf(LyricsStatus.NONE) }
    var retryCount by remember { mutableIntStateOf(0) }
    // Stays true while the next track's lyrics load, so the layout doesn't jump back and forth.
    var lyricsLayout by remember { mutableStateOf(false) }
    LaunchedEffect(nowPlaying.title, nowPlaying.artist, retryCount) {
        lyrics = null
        val title = nowPlaying.title
        val artist = nowPlaying.artist
        if (!title.isNullOrBlank() && !artist.isNullOrBlank()) {
            lyricsStatus = LyricsStatus.LOADING
            // The sender repeats metadata in bursts; wait for it to settle before asking LRCLIB.
            delay(400)
            val found = LyricsClient.fetch(artist, title, nowPlaying.durationSec, force = retryCount > 0)
            lyrics = found
            lyricsStatus = if (found != null) LyricsStatus.FOUND else LyricsStatus.NOT_FOUND
            lyricsLayout = found != null
        } else {
            lyricsStatus = LyricsStatus.NONE
            lyricsLayout = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 64.dp)) {
        AnimatedContent(
            targetState = lyricsLayout && !lyricsHidden,
            transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(400)) },
            label = "now-playing-layout",
            modifier = Modifier.align(Alignment.Center)
        ) { withLyrics ->
            if (withLyrics) {
                // Lyrics take the right side; cover and track info shrink into a column on the left.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.width(260.dp)) {
                        CoverArt(nowPlaying.cover, size = 200.dp)
                        Spacer(modifier = Modifier.height(20.dp))
                        TrackInfo(nowPlaying, isRussian, position, compact = true)
                        if (lyricsStatus == LyricsStatus.FOUND) {
                            // Kept apart from the lyrics so it never reads as one of the lines.
                            Spacer(modifier = Modifier.height(18.dp))
                            Text(
                                text = if (isRussian) {
                                    "Текст: LRCLIB. Может отличаться от исполнения или содержать ошибки"
                                } else {
                                    "Lyrics: LRCLIB. May differ from the recording or contain mistakes"
                                },
                                color = Color(0xFF5F6B7A),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(56.dp))
                    Box(modifier = Modifier.weight(1f).height(380.dp)) {
                        LyricsPanel(
                            lyrics = lyrics,
                            positionSec = position,
                            durationSec = nowPlaying.durationSec,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (lyricsStatus == LyricsStatus.LOADING) {
                            LyricsSearching(isRussian, modifier = Modifier.align(Alignment.CenterStart))
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoverArt(nowPlaying.cover, size = 320.dp)
                    Spacer(modifier = Modifier.width(56.dp))
                    Column(modifier = Modifier.widthIn(max = 720.dp)) {
                        TrackInfo(nowPlaying, isRussian, position, compact = false)
                        Spacer(modifier = Modifier.height(24.dp))
                        // Fixed-height slot so the layout doesn't shift between states.
                        Box(modifier = Modifier.height(48.dp), contentAlignment = Alignment.CenterStart) {
                            when (lyricsStatus) {
                                LyricsStatus.LOADING -> LyricsSearching(isRussian)
                                LyricsStatus.NOT_FOUND -> AirPlayButton(onClick = { retryCount++ }) {
                                    Text(
                                        text = if (isRussian) "Текст не найден · Повторить поиск" else "No lyrics found · Try again",
                                        fontSize = 15.sp
                                    )
                                }
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Sits below the screen's content padding, leaving a clear gap above it.
                .offset(y = 44.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AirPlayButton(
                onClick = onShowHowTo,
                modifier = Modifier.focusRequester(howToFocus)
            ) {
                Text(
                    text = if (isRussian) "Как смотреть видео на ТВ" else "How to watch video on the TV",
                    fontSize = 18.sp
                )
            }
            if (lyricsStatus == LyricsStatus.FOUND) {
                AirPlayButton(onClick = {
                    lyricsHidden = !lyricsHidden
                    prefs.edit().putBoolean("lyrics_hidden", lyricsHidden).apply()
                }) {
                    Text(
                        text = when {
                            lyricsHidden && isRussian -> "Показать текст"
                            lyricsHidden -> "Show lyrics"
                            isRussian -> "Скрыть текст"
                            else -> "Hide lyrics"
                        },
                        fontSize = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun CoverArt(cover: ImageBitmap?, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1A2028)),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(targetState = cover, animationSpec = tween(600), label = "cover") { image ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(text = "\u266A", color = Color(0xFF46515F), fontSize = (size.value * 0.37f).sp)
                }
            }
        }
    }
}

@Composable
private fun TrackInfo(nowPlaying: NowPlayingUi, isRussian: Boolean, position: Double, compact: Boolean) {
    Text(
        text = if (isRussian) "СЕЙЧАС ИГРАЕТ ЧЕРЕЗ AIRPLAY" else "PLAYING VIA AIRPLAY",
        color = Color(0xFF7EE2A8),
        fontSize = if (compact) 11.sp else 14.sp,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(if (compact) 8.dp else 16.dp))
    val subtitle = listOfNotNull(nowPlaying.artist, nowPlaying.album)
        .filter { it.isNotBlank() }
        .joinToString(" — ")
    // New track: the old title slides up and out, the new one comes in from below.
    // Keyed on title+artist only: the album arrives a moment later and must not replay the animation.
    AnimatedContent(
        targetState = nowPlaying.title to nowPlaying.artist,
        transitionSpec = {
            (slideInVertically(tween(450)) { it / 3 } + fadeIn(tween(450))) togetherWith
                (slideOutVertically(tween(300)) { -it / 3 } + fadeOut(tween(300)))
        },
        label = "track-info"
    ) { (title, _) ->
        val sub = subtitle
        Column {
            Text(
                text = title ?: if (isRussian) "Звук с iPhone" else "Audio from iPhone",
                color = Color.White,
                fontSize = if (compact) 22.sp else 44.sp,
                lineHeight = if (compact) 26.sp else 50.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (sub.isNotEmpty()) {
                Spacer(modifier = Modifier.height(if (compact) 4.dp else 10.dp))
                Text(
                    text = sub,
                    color = Color(0xFFB9C7D6),
                    fontSize = if (compact) 15.sp else 24.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    if (nowPlaying.durationSec > 0) {
        Spacer(modifier = Modifier.height(if (compact) 16.dp else 32.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 4.dp else 6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF2A333F))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((position / nowPlaying.durationSec).toFloat().coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(Color(0xFFE7EDF5))
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            val timeSize = if (compact) 13.sp else 16.sp
            Text(formatTime(position), color = Color(0xFF8A96A6), fontSize = timeSize)
            Spacer(modifier = Modifier.weight(1f))
            Text(formatTime(nowPlaying.durationSec), color = Color(0xFF8A96A6), fontSize = timeSize)
        }
    }
}


@Composable
private fun LyricsSearching(isRussian: Boolean, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        LoadingDots(color = Color(0xFF7AA7FF))
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = if (isRussian) "Ищем текст песни…" else "Looking for lyrics…",
            color = Color(0xFF8A96A6),
            fontSize = 16.sp
        )
    }
}

/**
 * TV button with an unmistakable focus state: white 3dp outline plus a lighter fill.
 * The stock tv-material3 look was too subtle on this TV.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AirPlayButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = androidx.tv.material3.ButtonDefaults.shape(shape = shape),
        colors = androidx.tv.material3.ButtonDefaults.colors(
            containerColor = Color(0xFF1F2630),
            contentColor = Color(0xFFB9C7D6),
            focusedContainerColor = Color(0xFF2E3A4A),
            focusedContentColor = Color.White,
            pressedContainerColor = Color(0xFF3A4860),
            pressedContentColor = Color.White
        ),
        border = androidx.tv.material3.ButtonDefaults.border(
            border = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E3A4A)),
                shape = shape
            ),
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, Color.White),
                shape = shape
            ),
            pressedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, Color(0xFF7AA7FF)),
                shape = shape
            )
        ),
        content = content
    )
}

private fun formatTime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    return if (total >= 3600) {
        String.format(Locale.US, "%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    } else {
        String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }
}



class AspectRatioSurfaceView(context: Context) : SurfaceView(context) {
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            return
        }
        if (videoWidth == width && videoHeight == height) {
            return
        }
        videoWidth = width
        videoHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        val parentHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (videoWidth <= 0 || videoHeight <= 0 || parentWidth == 0 || parentHeight == 0) {
            setMeasuredDimension(parentWidth, parentHeight)
            return
        }

        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val parentAspect = parentWidth.toFloat() / parentHeight.toFloat()
        val measuredWidth: Int
        val measuredHeight: Int

        if (videoAspect > parentAspect) {
            measuredWidth = parentWidth
            measuredHeight = (parentWidth / videoAspect).toInt()
        } else {
            measuredHeight = parentHeight
            measuredWidth = (parentHeight * videoAspect).toInt()
        }

        setMeasuredDimension(measuredWidth, measuredHeight)
    }
}
