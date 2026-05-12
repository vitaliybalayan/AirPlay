package com.example.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class AirPlayPublisher(private val context: Context, private val onLog: (String) -> Unit) {
    private companion object {
        const val TAG = "AirPlayPublisher"
        const val AIRPLAY_FEATURES = "0x5A7FFFC0,0x0"
    }

    private var nsdManager: NsdManager? = null
    private var airPlayRegistrationListener: NsdManager.RegistrationListener? = null
    private var raopRegistrationListener: NsdManager.RegistrationListener? = null

    private fun log(message: String) {
        Log.i(TAG, message)
        onLog("[Publisher] $message")
    }

    fun startPublishing(deviceName: String, macAddress: String, airplayPort: Int, raopPort: Int, pk: String) {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        // 1. AirPlay Service — точне совпадает с C++ info ответом
        val airplayServiceInfo = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = "_airplay._tcp"
            port = airplayPort
            setAttribute("deviceid", macAddress)
            setAttribute("features", AIRPLAY_FEATURES)
            setAttribute("model", "AppleTV2,1")
            setAttribute("srcvers", "220.68")
            setAttribute("vv", "2")
            setAttribute("flags", "0x4")
            setAttribute("rhd", "5.6.0.0")
            setAttribute("pw", "false")
            // Динамический ключ из C++ движка!
            setAttribute("pk", pk)
            setAttribute("pi", "2e388006-13ba-4041-9a67-25dd4a43d536")
        }

        // 2. RAOP Service — точно совпадает с оригиналом
        val raopServiceInfo = NsdServiceInfo().apply {
            serviceName = "${macAddress.replace(":", "")}@$deviceName"
            serviceType = "_raop._tcp"
            port = raopPort
            setAttribute("ch", "2")
            setAttribute("cn", "0,1,2,3")
            setAttribute("da", "true")
            setAttribute("et", "0,3,5")
            setAttribute("vv", "2")
            setAttribute("ft", AIRPLAY_FEATURES)
            setAttribute("am", "AppleTV2,1")
            setAttribute("md", "0,1,2")
            setAttribute("rhd", "5.6.0.0")
            setAttribute("pw", "false")
            setAttribute("sr", "44100")
            setAttribute("ss", "16")
            setAttribute("sv", "false")
            setAttribute("tp", "UDP")
            setAttribute("txtvers", "1")
            setAttribute("sf", "0x4")
            setAttribute("vs", "220.68")
            setAttribute("vn", "65537")
            setAttribute("pk", pk)
        }

        airPlayRegistrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                log("AirPlay Service registered: ${info.serviceName} port=$airplayPort")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "AirPlay Registration FAILED: service=${info.serviceName} errorCode=$errorCode")
                log("AirPlay Registration FAILED: $errorCode")
            }
            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                log("AirPlay Service unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "AirPlay Unregistration failed: service=${info.serviceName} errorCode=$errorCode")
                log("AirPlay Unregistration failed: $errorCode")
            }
        }

        raopRegistrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                log("RAOP Service registered: ${info.serviceName} port=$raopPort")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "RAOP Registration FAILED: service=${info.serviceName} errorCode=$errorCode")
                log("RAOP Registration FAILED: $errorCode")
            }
            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                log("RAOP Service unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "RAOP Unregistration failed: service=${info.serviceName} errorCode=$errorCode")
                log("RAOP Unregistration failed: $errorCode")
            }
        }

        try {
            log("Registering Airplay ($deviceName) mac=$macAddress airplay=$airplayPort raop=$raopPort")
            nsdManager?.registerService(airplayServiceInfo, NsdManager.PROTOCOL_DNS_SD, airPlayRegistrationListener)
            log("Registering RAOP")
            nsdManager?.registerService(raopServiceInfo, NsdManager.PROTOCOL_DNS_SD, raopRegistrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Exception in publish", e)
            log("Exception in publish: ${e.message}")
            e.printStackTrace()
        }
    }

    fun stopPublishing() {
        try {
            log("Stopping mDNS publishing")
            airPlayRegistrationListener?.let { nsdManager?.unregisterService(it) }
            raopRegistrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Exception while stopping mDNS publishing", e)
            e.printStackTrace()
        }
    }
}
