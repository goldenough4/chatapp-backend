package com.gold.chatapp

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

object MediaDownloader {

    fun downloadMedia(
        context: Context,
        url: String,
        fileName: String,
        isVideo: Boolean,
        b2Token: String,
        onProgress: ((Int) -> Unit)? = null
    ) {
        // We use OkHttpClient to handle the private B2 headers
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", b2Token) // Crucial for your private bucket
            .build()

        Thread {
            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    return@Thread
                }

                val body = response.body
                val contentLength = body?.contentLength() ?: -1L
                val inputStream = body?.byteStream()

                // Determine folder path using StorageHelper for professional hierarchy with date subfolders
                val baseFolder = if (isVideo) {
                    StorageHelper.MEDIA_VIDEO
                } else {
                    StorageHelper.MEDIA_IMAGES
                }

                val targetFolder = StorageHelper.getMediaFolderWithDate(context, baseFolder)
                val targetFile = File(targetFolder, fileName)

                targetFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L
                    
                    if (inputStream != null) {
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (contentLength > 0) {
                                val progress = ((totalRead * 100) / contentLength).toInt()
                                onProgress?.invoke(progress)
                            }
                        }
                    }
                }

                // If on Android 10+, we still want to scan the file so it appears in Gallery
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(targetFile.absolutePath),
                    arrayOf(if (isVideo) "video/mp4" else "image/jpeg"),
                    null
                )

                response.close()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }.start()
    }
}
