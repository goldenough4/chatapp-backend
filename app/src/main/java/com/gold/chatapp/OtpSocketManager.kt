package com.gold.chatapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object OtpSocketManager {

    private const val TAG = "OTP_REST_CLIENT"
    private const val PRODUCTION_SERVER_URL = "http://192.168.0.154:3000"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun requestOtp(
        @Suppress("UNUSED_PARAMETER") context: Context,
        target: String,
        type: String,
        callback: (success: Boolean, message: String, debugOtp: String?) -> Unit,
    ) {
        val normalizedTarget = target.trim().lowercase()
        requestOtpViaRest(normalizedTarget, type, callback)
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
                    val bodyString = response.body?.string()?.trim() ?: ""
                    var jsonResponse: JSONObject? = null

                    if (bodyString.startsWith("{") || bodyString.startsWith("[")) {
                        try {
                            jsonResponse = JSONObject(bodyString)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse REST body as JSON: ${e.message}")
                        }
                    }

                    if (!response.isSuccessful) {
                        Log.e(TAG, "HTTP Error (${response.code}): $bodyString")
                        val serverMsg = jsonResponse?.optString("message")
                        val msg = when {
                            !serverMsg.isNullOrEmpty() -> serverMsg
                            response.code == 404 -> "Service endpoint not found (404)."
                            response.code == 429 -> "Too many requests. Please wait."
                            response.code in 500..599 -> "Server error (${response.code}): $serverMsg"
                            else -> "Server error (${response.code}). Please try again."
                        }
                        mainHandler.post { callback(false, msg, null) }
                        return
                    }

                    val success = jsonResponse?.optBoolean("success", true) ?: true
                    val message = jsonResponse?.optString("message", "Verification code sent")
                        ?: "Verification code sent"
                    val debugOtp = jsonResponse?.optString("debugOtp").takeIf { !it.isNullOrEmpty() }

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
        @Suppress("UNUSED_PARAMETER") context: Context,
        target: String,
        otp: String,
        callback: (success: Boolean, message: String) -> Unit,
    ) {
        val normalizedTarget = target.trim().lowercase()
        verifyOtpViaRest(normalizedTarget, otp, callback)
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
                    val bodyString = response.body?.string()?.trim() ?: ""
                    var jsonResponse: JSONObject? = null

                    if (bodyString.startsWith("{") || bodyString.startsWith("[")) {
                        try {
                            jsonResponse = JSONObject(bodyString)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse REST body as JSON: ${e.message}")
                        }
                    }

                    if (!response.isSuccessful) {
                        Log.e(TAG, "HTTP Error (${response.code}): $bodyString")
                        val serverMsg = jsonResponse?.optString("message")
                        val msg = when {
                            !serverMsg.isNullOrEmpty() -> serverMsg
                            response.code == 404 -> "Service endpoint not found (404)."
                            response.code == 429 -> "Too many requests. Please wait."
                            response.code in 500..599 -> "Server error (${response.code}): $serverMsg"
                            else -> "Server error (${response.code}). Please try again."
                        }
                        mainHandler.post { callback(false, msg) }
                        return
                    }

                    val success = jsonResponse?.optBoolean("success", false) ?: false
                    val message = jsonResponse?.optString("message", "Verification failed")
                        ?: "Verification failed"

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
        @Suppress("UNUSED_PARAMETER") context: Context,
        target: String,
        type: String,
        callback: (success: Boolean, message: String, debugOtp: String?) -> Unit,
    ) {
        requestOtp(context, target, type, callback)
    }
}
