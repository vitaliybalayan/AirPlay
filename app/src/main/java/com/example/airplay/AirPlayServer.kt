package com.example.airplay

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class AirPlayServer(private val onLog: (String) -> Unit) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val port = 7000

    private fun log(message: String) {
        Log.d("AirPlayServer", message)
        onLog("[Server] $message")
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        thread {
            try {
                serverSocket = ServerSocket(port)
                log("Server started on port $port")
                while (isRunning) {
                    val client = serverSocket?.accept()
                    client?.let {
                        log("Client connected: ${it.inetAddress.hostAddress}")
                        thread { handleClient(it) }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    log("Exception: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val outputStream = client.getOutputStream()
            
            val requestLine = reader.readLine()
            log("Received request: $requestLine")
            
            if (requestLine != null) {
                // Базовый ответ RTSP для поддержания запросов
                val response = "RTSP/1.0 200 OK\r\n" +
                        "CSeq: 1\r\n" +
                        "Public: ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER\r\n" +
                        "\r\n"
                log("Sending RTSP 200 OK")
                outputStream.write(response.toByteArray())
                outputStream.flush()
            }
            client.close()
        } catch (e: Exception) {
            log("Client error: ${e.message}")
            e.printStackTrace()
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
