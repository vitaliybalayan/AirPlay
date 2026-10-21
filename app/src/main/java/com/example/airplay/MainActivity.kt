package com.example.airplay

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.net.wifi.WifiManager
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
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

import com.fang.myapplication.RaopServer

class MainActivity : ComponentActivity() {
    private companion object {
        const val TAG = "AirPlayMain"
    }

    private lateinit var airPlayPublisher: AirPlayPublisher
    private var raopServer: RaopServer? = null
    private lateinit var multicastLock: WifiManager.MulticastLock
    private val videoWidthState = androidx.compose.runtime.mutableStateOf(0)
    private val videoHeightState = androidx.compose.runtime.mutableStateOf(0)
    private val isVideoPlayingState = androidx.compose.runtime.mutableStateOf(false)
    private val nowPlayingState = androidx.compose.runtime.mutableStateOf<NowPlayingUi?>(null)

    private fun addLog(msg: String) {
        Log.i(TAG, msg)
    }

    private fun getWifiIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (nif in interfaces) {
                if (nif.isLoopback || !nif.isUp) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return "${addr.hostAddress} (${nif.name})"
                    }
                }
            }
        } catch (e: Exception) {
            return "ERROR: ${e.message}"
        }
        return "NOT FOUND"
    }

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: starting AirPlay receiver")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("AirPlayReceiverLock")
        multicastLock.setReferenceCounted(true)
        multicastLock.acquire()
        addLog("Multicast lock acquired")

        airPlayPublisher = AirPlayPublisher(this) { msg -> addLog(msg) }

        val deviceName = "Android TV"
        val macAddress = getMacAddress()
        val wifiIp = getWifiIpAddress()

        addLog("App started. Device: $deviceName")
        addLog("MAC: $macAddress")
        addLog("WiFi IP: $wifiIp")

        setContent {
            AirPlayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    AirPlayScreen(
                        deviceName = deviceName,
                        videoWidth = videoWidthState.value,
                        videoHeight = videoHeightState.value,
                        isVideoPlaying = isVideoPlayingState.value,
                        nowPlaying = nowPlayingState.value,
                        audioClockMs = { raopServer?.lastAudioPacketRealtimeMs ?: 0L }
                    ) { surfaceView ->
                        if (raopServer == null) {
                            try {
                                addLog("Creating RaopServer (C++ engine)...")
                                raopServer = RaopServer(surfaceView)
                                Log.i(TAG, "RaopServer created")

                                raopServer?.setLogListener(RaopServer.LogListener { msg ->
                                    addLog(msg)
                                })
                                raopServer?.setVideoSizeListener(object : RaopServer.VideoSizeListener {
                                    override fun onVideoSizeChanged(width: Int, height: Int) {
                                        Log.i(TAG, "Video size changed: ${width}x${height}")
                                        runOnUiThread {
                                            videoWidthState.value = width
                                            videoHeightState.value = height
                                            isVideoPlayingState.value = width > 0 && height > 0
                                        }
                                    }
                                })

                                raopServer?.setNowPlayingListener(RaopServer.NowPlayingListener { np ->
                                    // Decode off the UI thread; covers can be a few hundred KB.
                                    val previous = nowPlayingState.value
                                    val cover = when {
                                        np.coverArt == null -> null
                                        previous?.coverSource === np.coverArt -> previous.cover
                                        else -> BitmapFactory.decodeByteArray(np.coverArt, 0, np.coverArt.size)?.asImageBitmap()
                                    }
                                    val ui = if (np.active) NowPlayingUi(
                                        title = np.title,
                                        artist = np.artist,
                                        album = np.album,
                                        cover = cover,
                                        coverSource = np.coverArt,
                                        durationSec = np.durationSec,
                                        positionSec = np.positionSec,
                                        positionAtMs = np.positionAtMs
                                    ) else null
                                    runOnUiThread { nowPlayingState.value = ui }
                                })

                                addLog("Starting C++ RAOP server...")
                                raopServer?.startServer(deviceName, macAddress)
                                val raopPort = raopServer?.port ?: 0
                                addLog("RAOP C++ port: $raopPort")

                                val pk = raopServer?.publicKeyHex ?: ""
                                addLog("PK: ${pk.take(16)}...")

                                if (raopPort > 0) {
                                    addLog("Publishing mDNS (NsdManager)...")
                                    airPlayPublisher.startPublishing(deviceName, macAddress, raopPort, raopPort, pk)
                                    addLog("=== READY! Waiting for iPhone ===")
                                    addLog("Try: from iPhone Settings > Wi-Fi check same network as $wifiIp")
                                } else {
                                    Log.e(TAG, "RAOP server returned port 0")
                                    addLog("ERROR: raopPort=0!")
                                }
                            } catch (e: UnsatisfiedLinkError) {
                                Log.e(TAG, "Native library error", e)
                                addLog("NATIVE LIB ERROR: ${e.message}")
                                e.printStackTrace()
                            } catch (e: Exception) {
                                Log.e(TAG, "Startup crash", e)
                                addLog("CRASH: ${e.javaClass.simpleName}: ${e.message}")
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: stopping AirPlay receiver")
        airPlayPublisher.stopPublishing()
        raopServer?.stopServer()
        isVideoPlayingState.value = false
        videoWidthState.value = 0
        videoHeightState.value = 0
        if (multicastLock.isHeld) {
            multicastLock.release()
            Log.i(TAG, "Multicast lock released")
        }
    }

    private fun getMacAddress(): String {
        try {
            val all = NetworkInterface.getNetworkInterfaces()
            for (nif in all) {
                if (!nif.name.equals("wlan0", ignoreCase = true) && !nif.name.equals("eth0", ignoreCase = true)) continue
                val macBytes = nif.hardwareAddress ?: continue
                // Android 6+ hides the real MAC behind 02:00:00:00:00:00; that is not unique per TV.
                if (macBytes.size != 6 || macBytes.all { it == 0.toByte() } ||
                    (macBytes[0] == 0x02.toByte() && macBytes.drop(1).all { it == 0.toByte() })) continue
                val res1 = StringBuilder()
                for (b in macBytes) {
                    res1.append(String.format("%02X:", b))
                }
                if (res1.isNotEmpty()) {
                    res1.deleteCharAt(res1.length - 1)
                }
                return res1.toString()
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }

        val androidId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        val hash = androidId?.hashCode() ?: 123456
        val b1 = (hash shr 24 and 0xFF) and 0xFE
        val b2 = (hash shr 16 and 0xFF)
        val b3 = (hash shr 8 and 0xFF)
        val b4 = (hash and 0xFF)

        return String.format("%02X:%02X:%02X:%02X:55:66", b1, b2, b3, b4)
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
    onSurfaceReady: (SurfaceView) -> Unit
) {
    val isRussian = Locale.getDefault().language == "ru"
    val copy = if (isRussian) {
        IdleCopy(
            title = "AirPlay",
            subtitle = "Готов к трансляции",
            status = "Ожидание устройства",
            stepOne = "Откройте Пункт управления на iPhone, iPad или Mac.",
            stepTwo = "Нажмите «Повтор экрана» или AirPlay.",
            stepThree = "Выберите «$deviceName»."
        )
    } else {
        IdleCopy(
            title = "AirPlay",
            subtitle = "Ready to stream",
            status = "Waiting for device",
            stepOne = "Open Control Center on your iPhone, iPad, or Mac.",
            stepTwo = "Tap Screen Mirroring or AirPlay.",
            stepThree = "Choose \"$deviceName\"."
        )
    }

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
                isRussian = isRussian,
                howToVisible = showHowTo,
                onShowHowTo = { showHowTo = true }
            )
        }

        if (!isVideoPlaying && nowPlaying == null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 64.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = copy.title,
                    color = Color.White,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = copy.subtitle,
                    color = Color(0xFFB9C7D6),
                    fontSize = 24.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(28.dp))
                StatusLine(text = copy.status)
                Spacer(modifier = Modifier.height(44.dp))
                InstructionLine(number = "1", text = copy.stepOne)
                Spacer(modifier = Modifier.height(14.dp))
                InstructionLine(number = "2", text = copy.stepTwo)
                Spacer(modifier = Modifier.height(14.dp))
                InstructionLine(number = "3", text = copy.stepThree)
            }

            Text(
                text = "© vitaliybalayan",
                color = Color(0xFF46515F),
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp),
                textAlign = TextAlign.Center
            )
        }

        if (showHowTo) {
            MirroringHowToOverlay(isRussian = isRussian, onClose = { showHowTo = false })
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
    val position = (nowPlaying.positionSec + elapsed).coerceAtMost(nowPlaying.durationSec)

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
            targetState = lyricsLayout,
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
        AirPlayButton(
            onClick = onShowHowTo,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Sits below the screen's content padding, leaving a clear gap above it.
                .offset(y = 44.dp)
                .focusRequester(howToFocus)
        ) {
            Text(
                text = if (isRussian) "Как смотреть видео на ТВ" else "How to watch video on the TV",
                fontSize = 18.sp
            )
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
    AnimatedContent(
        targetState = nowPlaying.title to subtitle,
        transitionSpec = {
            (slideInVertically(tween(450)) { it / 3 } + fadeIn(tween(450))) togetherWith
                (slideOutVertically(tween(300)) { -it / 3 } + fadeOut(tween(300)))
        },
        label = "track-info"
    ) { (title, sub) ->
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

private data class IdleCopy(
    val title: String,
    val subtitle: String,
    val status: String,
    val stepOne: String,
    val stepTwo: String,
    val stepThree: String
)

@Composable
private fun StatusLine(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(Color(0xFF37D67A))
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text.uppercase(Locale.getDefault()),
            color = Color(0xFF7EE2A8),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun InstructionLine(number: String, text: String) {
    Row(
        modifier = Modifier.widthIn(max = 760.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = number,
            color = Color(0xFF7AA7FF),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(34.dp),
            textAlign = TextAlign.Center
        )
        Text(
            text = text,
            color = Color(0xFFE7EDF5),
            fontSize = 21.sp,
            lineHeight = 29.sp
        )
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
