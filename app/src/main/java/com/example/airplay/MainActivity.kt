package com.example.airplay

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import com.fang.myapplication.RaopServer

class MainActivity : ComponentActivity() {

    private lateinit var airPlayPublisher: AirPlayPublisher
    private var raopServer: RaopServer? = null
    private lateinit var multicastLock: WifiManager.MulticastLock
    private val videoWidthState = androidx.compose.runtime.mutableStateOf(0)
    private val videoHeightState = androidx.compose.runtime.mutableStateOf(0)
    private val isVideoPlayingState = androidx.compose.runtime.mutableStateOf(false)

    private val logsList = mutableStateListOf<String>()

    private fun shouldKeepLog(msg: String): Boolean {
        val noisyFragments = listOf(
            "raop_rtp_thread_udp",
            "type_c 0x54",
            "Connection closed for socket",
            "eiv_len =",
            "ekey_len =",
            "fairplay_decrypt ret =",
            "> stream info:",
            "/feedback keepalive",
            "[Surface] surfaceChanged"
        )
        return noisyFragments.none { msg.contains(it) }
    }

    private fun addLog(msg: String) {
        if (!shouldKeepLog(msg)) return
        runOnUiThread {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            logsList.add(0, "[$time] $msg")
            while (logsList.size > 200) {
                logsList.removeAt(logsList.lastIndex)
            }
        }
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
                        wifiIp = wifiIp,
                        logsList = logsList,
                        videoWidth = videoWidthState.value,
                        videoHeight = videoHeightState.value,
                        isVideoPlaying = isVideoPlayingState.value
                    ) { surfaceView ->
                        if (raopServer == null) {
                            try {
                                addLog("Creating RaopServer (C++ engine)...")
                                raopServer = RaopServer(surfaceView)

                                raopServer?.setLogListener(RaopServer.LogListener { msg ->
                                    addLog(msg)
                                })
                                raopServer?.setVideoSizeListener(object : RaopServer.VideoSizeListener {
                                    override fun onVideoSizeChanged(width: Int, height: Int) {
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
                                    // Тест TCP подключения к localhost
                                    Thread {
                                        try {
                                            val testSock = Socket("127.0.0.1", raopPort)
                                            addLog("TCP localhost test: port $raopPort OK ✓")
                                            testSock.close()
                                        } catch (e: Exception) {
                                            addLog("TCP localhost test FAILED: ${e.message}")
                                        }
                                    }.start()
                                    
                                    // Открываем дополнительный простой TCP сервер 
                                    // для проверки доступности по сети
                                    Thread {
                                        try {
                                            val testServer = ServerSocket(7000)
                                            addLog("Test server on port 7000 listening...")
                                            val client = testServer.accept()
                                            addLog("*** GOT CONNECTION on port 7000 from ${client.inetAddress} ***")
                                            client.close()
                                            testServer.close()
                                        } catch (e: Exception) {
                                            addLog("Test server 7000: ${e.message}")
                                        }
                                    }.start()

                                    addLog("Publishing mDNS (NsdManager)...")
                                    airPlayPublisher.startPublishing(deviceName, macAddress, raopPort, raopPort, pk)
                                    addLog("=== READY! Waiting for iPhone ===")
                                    addLog("Try: from iPhone Settings > Wi-Fi check same network as $wifiIp")
                                } else {
                                    addLog("ERROR: raopPort=0!")
                                }
                            } catch (e: UnsatisfiedLinkError) {
                                addLog("NATIVE LIB ERROR: ${e.message}")
                                e.printStackTrace()
                            } catch (e: Exception) {
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
        airPlayPublisher.stopPublishing()
        raopServer?.stopServer()
        isVideoPlayingState.value = false
        videoWidthState.value = 0
        videoHeightState.value = 0
        if (multicastLock.isHeld) {
            multicastLock.release()
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
    wifiIp: String,
    logsList: List<String>,
    videoWidth: Int,
    videoHeight: Int,
    isVideoPlaying: Boolean,
    onSurfaceReady: (SurfaceView) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
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

        if (!isVideoPlaying) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(32.dp)
            ) {
                Text(
                    text = "AirPlay Приемник",
                    color = Color.White,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "$deviceName | $wifiIp",
                    color = Color.Cyan,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .background(Color(0xCC111111))
                    .padding(16.dp)
            ) {
                LazyColumn {
                    items(logsList) { logItem ->
                        val color = when {
                            logItem.contains("ERR") || logItem.contains("ERROR") || logItem.contains("CRASH") || logItem.contains("FAILED") -> Color.Red
                            logItem.contains("WRN") || logItem.contains("WARNING") -> Color.Yellow
                            logItem.contains("READY") || logItem.contains("REACHABLE") || logItem.contains("OK ✓") || logItem.contains("GOT CONNECTION") -> Color.Green
                            logItem.contains("C++") -> Color(0xFF87CEEB)
                            logItem.contains("WiFi IP") -> Color(0xFFFFD700)
                            else -> Color.Green
                        }
                        Text(
                            text = logItem,
                            color = color,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
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
