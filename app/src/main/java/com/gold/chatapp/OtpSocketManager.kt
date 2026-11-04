package com.gold.chatapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object OtpSocketManager {

    private const val TAG = "OTP_SOCKET_ERROR"
    private const val PRODUCTION_SERVER_URL = "https://chatapp-backend-dwhh6.onrender.com"

    private var socket: Socket? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun requestOtp(
        context: Context,
        target: String,
        type: String,
        callback: (success: Boolean, message: String, debugOtp: String?) -> Unit,
    ) {
        val normalizedTarget = target.trim().lowercase()

        connect(context) { connected ->
            if (connected && socket != null) {
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

                mainHandler.postDelayed({
                    if (!responded) {
                        responded = true
                        requestOtpViaRest(normalizedTarget, type, callback)
                    }
                }, 45000)
            } else {
                Log.w(
                    TAG,
                    "Socket connect failed. Falling back to OkHttp REST API for request_otp."
                )
                requestOtpViaRest(normalizedTarget, type, callback)
            }
        }
    }

    private fun requestOtpViaRest(
        target: String,
        type: String,
        callback: (success: Boolean, message: String, debugOtp: String?) -> Unit,
    ) {
        val json = JSONObject().apply {
            put("target", target)
            put("type", type)
        }
        val requestBody = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$PRODUCTION_SERVER_URL/api/request-otp")
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "OkHttp request_otp failure: ${e.message}", e)
                mainHandler.post {
                    callback(false, "Network error: ${e.message}", null)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val bodyString = response.body?.string() ?: "{}"
                    val trimmed = bodyString.trim()
                    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                        Log.e(TAG, "Non-JSON response (${response.code}): $trimmed")
                        val msg = if (response.code in 500..599) {
                            "Server is waking up (502/503). Please wait 30s and try again."
                        } else {
                            "Server error (${response.code}). Please try again."
                        }
                        mainHandler.post { callback(false, msg, null) }
                        return
                    }

                    val jsonResponse = JSONObject(bodyString)
                    val success = jsonResponse.optBoolean("success", false)
                    val message = jsonResponse.optString("message", "Response received")
                    val debugOtp = jsonResponse.optString("debugOtp").takeIf { !it.isNullOrEmpty() }

                    mainHandler.post {
                        callback(success, message, debugOtp)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "OkHttp request_otp parse error: ${e.message}", e)
                    mainHandler.post {
                        callback(false, "Server response parse error. Please try again.", null)
                    }
                }
            }
        })
    }

    fun verifyOtp(
        context: Context,
        target: String,
        otp: String,
        callback: (success: Boolean, message: String) -> Unit,
    ) {
        val normalizedTarget = target.trim().lowercase()

        connect(context) { connected ->
            if (connected && socket != null) {
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

                mainHandler.postDelayed({
                    if (!responded) {
                        responded = true
                        verifyOtpViaRest(normalizedTarget, otp, callback)
                    }
                }, 45000)
            } else {
                Log.w(
                    TAG,
                    "Socket connect failed. Falling back to OkHttp REST API for verify_otp."
                )
                verifyOtpViaRest(normalizedTarget, otp, callback)
            }
        }
    }

    private fun verifyOtpViaRest(
        target: String,
        otp: String,
        callback: (success: Boolean, message: String) -> Unit,
    ) {
        val json = JSONObject().apply {
            put("target", target)
            put("otp", otp.trim())
        }
        val requestBody = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$PRODUCTION_SERVER_URL/api/verify-otp")
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "OkHttp verify_otp failure: ${e.message}", e)
                mainHandler.post {
                    callback(false, "Network error: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val bodyString = response.body?.string() ?: "{}"
                    val trimmed = bodyString.trim()
                    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                        Log.e(TAG, "Non-JSON response (${response.code}): $trimmed")
                        val msg = if (response.code in 500..599) {
                            "Server is waking up (502/503). Please wait 30s and try again."
                        } else {
                            "Server error (${response.code}). Please try again."
                        }
                        mainHandler.post { callback(false, msg) }
                        return
                    }

                    val jsonResponse = JSONObject(bodyString)
                    val success = jsonResponse.optBoolean("success", false)
                    val message = jsonResponse.optString("message", "Verification failed")

                    mainHandler.post {
                        callback(success, message)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "OkHttp verify_otp parse error: ${e.message}", e)
                    mainHandler.post {
                        callback(false, "Server response parse error. Please try again.")
                    }
                }
            }
        })
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
                reconnectionAttempts = 5
                reconnectionDelay = 1000
                timeout = 60000
            }

            val candidateSocket = IO.socket(PRODUCTION_SERVER_URL, options)
            var resolved = false

            val timeoutRunnable = Runnable {
                if (!resolved) {
                    resolved = true
                    Log.w(TAG, "Socket connect timeout for $PRODUCTION_SERVER_URL")
                    try {
                        candidateSocket.disconnect()
                        candidateSocket.off()
                    } catch (_: Exception) {}
                    onConnected(false)
                }
            }

            mainHandler.postDelayed(timeoutRunnable, 65000)

            candidateSocket.on(Socket.EVENT_CONNECT) {
                if (!resolved) {
                    resolved = true
                    mainHandler.removeCallbacks(timeoutRunnable)
                    socket = candidateSocket
                    Log.d(TAG, "Socket connected successfully to $PRODUCTION_SERVER_URL")
                    onConnected(true)
                }
            }

            candidateSocket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                if (!resolved) {
                    resolved = true
                    mainHandler.removeCallbacks(timeoutRunnable)
                    val errorObj = args.firstOrNull()
                    if (errorObj is Throwable) {
                        Log.e(TAG, "Socket connect error exception: ${errorObj.message}", errorObj)
                    } else {
                        Log.e(TAG, "Socket connect error: $errorObj")
                    }
                    try {
                        candidateSocket.disconnect()
                        candidateSocket.off()
                    } catch (_: Exception) {}
                    onConnected(false)
                }
            }

            candidateSocket.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Socket connect exception: ${e.message}", e)
            onConnected(false)
        }
    }
}
