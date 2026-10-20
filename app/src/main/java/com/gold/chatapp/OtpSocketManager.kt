package com.gold.chatapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject

object OtpSocketManager {

    private const val PRODUCTION_SERVER_URL = "https://chatapp-otp-server.onrender.com"

    private var socket: Socket? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun requestOtp(
        context: Context,
        target: String,
        type: String,
        callback: (success: Boolean, message: String, debugOtp: String?) -> Unit,
    ) {
        val normalizedTarget = target.trim().lowercase()

        connect(context) { connected ->
            if (!connected || socket == null) {
                mainHandler.post {
                    callback(
                        false,
                        "Unable to connect to server. Please check your internet connection.",
                        null
                    )
                }
                return@connect
            }

            val data = JSONObject().apply {
                put("target", normalizedTarget)
                put("type", type)
            }

            socket?.off("otp_sent")
            socket?.off("otp_error")

            var responded = false

            socket?.once("otp_sent") { args ->
                if (!responded) {
                    responded = true
                    val response = args.firstOrNull() as? JSONObject
                    val success = response?.optBoolean("success", false) ?: false
                    val message = response?.optString("message", "Verification code sent")
                        ?: "Verification code sent"
                    val debugOtp = response?.optString("debugOtp")
                        .takeIf { !it.isNullOrEmpty() }

                    mainHandler.post {
                        callback(success, message, debugOtp)
                    }
                }
            }

            socket?.once("otp_error") { args ->
                if (!responded) {
                    responded = true
                    val response = args.firstOrNull() as? JSONObject
                    val message = response?.optString("message")
                        ?: "Failed to send verification code"

                    mainHandler.post {
                        callback(false, message, null)
                    }
                }
            }

            socket?.emit("request_otp", data)

            // 10-second timeout safeguard
            mainHandler.postDelayed({
                if (!responded) {
                    responded = true
                    mainHandler.post {
                        callback(false, "Server request timed out. Please try again.", null)
                    }
                }
            }, 10000)
        }
    }

    fun verifyOtp(
        context: Context,
        target: String,
        otp: String,
        callback: (success: Boolean, message: String) -> Unit,
    ) {
        val normalizedTarget = target.trim().lowercase()

        connect(context) { connected ->
            if (!connected || socket == null) {
                mainHandler.post {
                    callback(
                        false,
                        "Unable to connect to server. Please check your internet connection."
                    )
                }
                return@connect
            }

            val data = JSONObject().apply {
                put("target", normalizedTarget)
                put("otp", otp.trim())
            }

            socket?.off("otp_verified")
            var responded = false

            socket?.once("otp_verified") { args ->
                if (!responded) {
                    responded = true
                    val response = args.firstOrNull() as? JSONObject
                    val success = response?.optBoolean("success", false) ?: false
                    val message = response?.optString("message", "Verification failed")
                        ?: "Verification failed"

                    mainHandler.post {
                        callback(success, message)
                    }
                }
            }

            socket?.emit("verify_otp", data)

            // 10-second timeout safeguard
            mainHandler.postDelayed({
                if (!responded) {
                    responded = true
                    mainHandler.post {
                        callback(false, "Verification request timed out. Please try again.")
                    }
                }
            }, 10000)
        }
    }

    fun resendOtp(
        context: Context,
        target: String,
        type: String,
        callback: (success: Boolean, message: String, debugOtp: String?) -> Unit,
    ) {
        requestOtp(context, target, type, callback)
    }

    private fun connect(
        @Suppress("UNUSED_PARAMETER") context: Context,
        onConnected: (Boolean) -> Unit,
    ) {
        if (socket?.connected() == true) {
            onConnected(true)
            return
        }

        try {
            val options = IO.Options().apply {
                transports = arrayOf("websocket", "polling")
                secure = true
                reconnection = true
                reconnectionAttempts = 3
                reconnectionDelay = 1000
                timeout = 8000
            }

            val candidateSocket = IO.socket(PRODUCTION_SERVER_URL, options)
            var resolved = false

            val timeoutRunnable = Runnable {
                if (!resolved) {
                    resolved = true
                    try {
                        candidateSocket.disconnect()
                        candidateSocket.off()
                    } catch (_: Exception) {}
                    onConnected(false)
                }
            }

            mainHandler.postDelayed(timeoutRunnable, 9000)

            candidateSocket.on(Socket.EVENT_CONNECT) {
                if (!resolved) {
                    resolved = true
                    mainHandler.removeCallbacks(timeoutRunnable)
                    socket = candidateSocket
                    onConnected(true)
                }
            }

            candidateSocket.on(Socket.EVENT_CONNECT_ERROR) { _ ->
                if (!resolved) {
                    resolved = true
                    mainHandler.removeCallbacks(timeoutRunnable)
                    try {
                        candidateSocket.disconnect()
                        candidateSocket.off()
                    } catch (_: Exception) {}
                    onConnected(false)
                }
            }

            candidateSocket.connect()
        } catch (_: Exception) {
            onConnected(false)
        }
    }
}
