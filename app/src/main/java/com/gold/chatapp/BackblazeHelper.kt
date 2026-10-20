package com.gold.chatapp

import android.util.Base64
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException

object BackblazeHelper {

    private const val KEY_ID = "005cb4d249b27e40000000001"
    private const val APP_KEY = "K00551/SdZG+kiz0QkFr4ymj1vb7Wz0"

    private val client = OkHttpClient()

    /**
     * Uploads any media file with real-time progress tracking
     */
    fun uploadFile(
        file: File,
        bucketId: String,
        mimeType: String, // e.g., "audio/m4a", "image/jpeg", "video/mp4"
        onProgress: (Int) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val auth = Base64.encodeToString("$KEY_ID:$APP_KEY".toByteArray(), Base64.NO_WRAP)

        // STEP 1: Authorize Account
        val authRequest = Request.Builder()
            .url("https://api.backblazeb2.com/b2api/v2/b2_authorize_account")
            .addHeader("Authorization", "Basic $auth")
            .build()

        client.newCall(authRequest).enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    onError("Auth failed: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            onError("Auth error: ${response.code}")
                            return
                        }

                        val bodyStr = response.body?.string() ?: ""
                        val authJson = JSONObject(bodyStr)
                        val apiUrl = authJson.getString("apiUrl")
                        val authToken = authJson.getString("authorizationToken")
                        val downloadUrl = authJson.getString("downloadUrl")

                        // STEP 2: Get Upload URL
                        getUploadUrl(
                            apiUrl,
                            authToken,
                            bucketId,
                            file,
                            mimeType,
                            downloadUrl,
                            onProgress,
                            onSuccess,
                            onError,
                        )
                    }
                }
            },
        )
    }

    private fun getUploadUrl(
        apiUrl: String,
        authToken: String,
        bucketId: String,
        file: File,
        mimeType: String,
        downloadUrl: String,
        onProgress: (Int) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val json = JSONObject().put("bucketId", bucketId)
        val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("$apiUrl/b2api/v2/b2_get_upload_url")
            .addHeader("Authorization", authToken)
            .post(body)
            .build()

        client.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    onError("Get upload URL failed: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            onError("Upload URL error: ${response.code}")
                            return
                        }

                        val bodyStr = response.body?.string() ?: ""
                        val uploadJson = JSONObject(bodyStr)
                        val uploadUrl = uploadJson.getString("uploadUrl")
                        val uploadAuthToken = uploadJson.getString("authorizationToken")

                        // STEP 3: Final Upload with Progress
                        performActualUpload(
                            uploadUrl,
                            uploadAuthToken,
                            file,
                            mimeType,
                            downloadUrl,
                            onProgress,
                            onSuccess,
                            onError,
                        )
                    }
                }
            },
        )
    }

    private fun performActualUpload(
        uploadUrl: String,
        uploadAuthToken: String,
        file: File,
        mimeType: String,
        downloadUrl: String,
        onProgress: (Int) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val baseBody = file.asRequestBody(mimeType.toMediaTypeOrNull())

        // Wrap the body in our ProgressRequestBody
        val progressBody =
            ProgressRequestBody(
                baseBody,
                object : ProgressRequestBody.UploadProgressListener {
                    override fun onProgress(bytesWritten: Long, contentLength: Long, elapsedMs: Long) {
                        if (contentLength > 0) {
                            val percentage = ((bytesWritten * 100) / contentLength).toInt()
                            onProgress(percentage)
                        }
                    }
                },
            )

        val request = Request.Builder()
            .url(uploadUrl)
            .addHeader("Authorization", uploadAuthToken)
            .addHeader("X-Bz-File-Name", file.name)
            .addHeader("Content-Type", mimeType)
            .addHeader("X-Bz-Content-Sha1", "do_not_verify")
            .post(progressBody)
            .build()

        client.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    onError("Final upload failed: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (response.isSuccessful) {
                            val bodyStr = response.body?.string() ?: ""
                            val respJson = JSONObject(bodyStr)
                            val fileName = respJson.getString("fileName")
                            // Construct the clean URL for your private bucket
                            val finalUrl = "$downloadUrl/file/chatapp-voice/$fileName"
                            onSuccess(finalUrl)
                        } else {
                            onError("Final upload code: ${response.code}")
                        }
                    }
                }
            },
        )
    }
}
