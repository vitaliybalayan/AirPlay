package com.example.airplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import com.fang.myapplication.RaopServer
import java.net.NetworkInterface

/** Process-wide AirPlay engine, shared by the background service and the UI. */
object AirPlayRuntime {
    const val DEVICE_NAME = "Android TV"

    @Volatile
    private var server: RaopServer? = null

    fun server(context: Context): RaopServer =
        server ?: synchronized(this) {
            server ?: RaopServer(context.applicationContext).also { server = it }
        }

    fun currentServer(): RaopServer? = server

    fun release() {
        synchronized(this) {
            server?.stopServer()
            server = null
        }
    }

    fun deviceId(context: Context): String {
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.name.equals("wlan0", ignoreCase = true) && !nif.name.equals("eth0", ignoreCase = true)) continue
                val macBytes = nif.hardwareAddress ?: continue
                // Android 6+ hides the real MAC behind 02:00:00:00:00:00; that is not unique per TV.
                if (macBytes.size != 6 || macBytes.all { it == 0.toByte() } ||
                    (macBytes[0] == 0x02.toByte() && macBytes.drop(1).all { it == 0.toByte() })) continue
                return macBytes.joinToString(":") { String.format("%02X", it) }
            }
        } catch (e: Exception) {
            Log.w("AirPlayRuntime", "MAC lookup failed", e)
        }
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val hash = androidId?.hashCode() ?: 123456
        return String.format(
            "%02X:%02X:%02X:%02X:55:66",
            (hash shr 24 and 0xFF) and 0xFE, hash shr 16 and 0xFF, hash shr 8 and 0xFF, hash and 0xFF
        )
    }

    /** Needed to open the receiver UI on top of other apps when an iPhone connects. */
    fun canShowOverOtherApps(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
}

/**
 * Keeps the AirPlay receiver running without the UI: started at boot and from the app,
 * it owns the RAOP server and mDNS advertising, and brings the UI up when a sender
 * starts mirroring or playing audio.
 */
class AirPlayService : Service() {
    companion object {
        private const val TAG = "AirPlayService"
        private const val CHANNEL_ID = "airplay_receiver"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, AirPlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private lateinit var publisher: AirPlayPublisher
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()

        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("AirPlayReceiverLock").apply {
            setReferenceCounted(false)
            acquire()
        }

        val deviceId = AirPlayRuntime.deviceId(this)
        val server = AirPlayRuntime.server(this)
        server.setScreenRequestListener { reason -> showReceiverUi(reason) }
        server.startServer(AirPlayRuntime.DEVICE_NAME, deviceId)
        val port = server.port
        Log.i(TAG, "AirPlay server started on port $port, deviceId=$deviceId")

        publisher = AirPlayPublisher(this) { msg -> Log.i(TAG, msg) }
        if (port > 0) {
            publisher.startPublishing(AirPlayRuntime.DEVICE_NAME, deviceId, port, port, server.publicKeyHex)
        } else {
            Log.e(TAG, "RAOP server returned port 0, not advertising")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Stopping AirPlay receiver")
        publisher.stopPublishing()
        AirPlayRuntime.release()
        multicastLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun showReceiverUi(reason: String) {
        if (MainActivity.isInForeground) {
            return
        }
        if (!AirPlayRuntime.canShowOverOtherApps(this)) {
            Log.w(TAG, "Cannot open UI for $reason: 'display over other apps' is not granted")
            return
        }
        Log.i(TAG, "Opening receiver UI for $reason")
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(MainActivity.EXTRA_AUTO_LAUNCHED, true)
        startActivity(intent)
    }

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AirPlay", NotificationManager.IMPORTANCE_MIN)
            )
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(if (java.util.Locale.getDefault().language == "ru") "Готов к трансляции" else "Ready to stream")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}

/** Starts the receiver after the TV boots, so iPhones can find it without opening the app. */
class AirPlayBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i("AirPlayBootReceiver", "Boot completed, starting AirPlay service")
            AirPlayService.start(context)
        }
    }
}
