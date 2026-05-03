package com.example.airplay

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
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

private const val VIDEO_DIM_ALPHA = 0.18f

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
                        isVideoPlaying = isVideoPlayingState.value
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

                                addLog("Starting C++ RAOP server...")
                                raopServer?.startServer()
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

        if (isVideoPlaying) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = VIDEO_DIM_ALPHA))
            )
        }

        if (!isVideoPlaying) {
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
