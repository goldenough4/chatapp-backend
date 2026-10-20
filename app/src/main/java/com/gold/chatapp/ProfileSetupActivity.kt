package com.gold.chatapp

import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class ProfileSetupActivity : AppCompatActivity() {

    private lateinit var profileImage: ImageView
    private lateinit var nameInput: TextInputEditText
    private lateinit var continueButton: MaterialButton
    private var selectedImageUri: Uri? = null
    private var progressDialog: ProgressDialog? = null

    private val db = FirebaseFirestore.getInstance()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val b2KeyId = "005cb4d249b27e40000000002"
    private val b2AppKey = "K005bZcXIuOMKUjwp2gj6WXuGM7dxdI"
    private val b2BucketId = "6cdbc4edb244590b92c70e14"
    private val b2BucketName = "chatapp-voice"

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
            try {
                com.bumptech.glide.Glide.with(this)
                    .load(uri)
                    .circleCrop()
                    .placeholder(R.drawable.ic_default_user)
                    .error(R.drawable.ic_default_user)
                    .into(profileImage)
            } catch (e: Exception) {
                Log.e("PROFILE_SETUP", "Failed to load image: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_setup)

        StorageHelper.initChatAppFolders(this)

        profileImage = findViewById(R.id.profile_picture)
        nameInput = findViewById(R.id.name_input)
        continueButton = findViewById(R.id.profile_continue_button)

        val prefs = getSharedPreferences("chat_app_prefs", MODE_PRIVATE)
        val myPhone = prefs.getString("user_phone", "") ?: ""

        val phoneDisplay = findViewById<android.widget.EditText>(R.id.phone_display)
        phoneDisplay.setText(myPhone)

        // Dynamic Profile Fetching & Pre-population
        db.collection("users").document(myPhone).get().addOnSuccessListener { snapshot ->
            if (snapshot != null && snapshot.exists()) {
                val currentName = snapshot.getString("name") ?: ""
                val currentAvatar = snapshot.getString("profileImageUrl") ?: ""
                if (currentName.isNotEmpty()) {
                    nameInput.setText(currentName)
                }
                if (currentAvatar.isNotEmpty()) {
                    // Check if a valid B2 auth token exists to load image securely
                    val b2Token = prefs.getString("b2_auth_token", "") ?: ""
                    if (b2Token.isNotEmpty()) {
                        com.bumptech.glide.Glide.with(this)
                            .load(com.bumptech.glide.load.model.GlideUrl(currentAvatar, com.bumptech.glide.load.model.LazyHeaders.Builder().addHeader("Authorization", b2Token).build()))
                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                            .circleCrop()
                            .placeholder(R.drawable.ic_default_user)
                            .into(profileImage)
                    } else {
                        com.bumptech.glide.Glide.with(this)
                            .load(currentAvatar)
                            .circleCrop()
                            .placeholder(R.drawable.ic_default_user)
                            .into(profileImage)
                    }
                }
            }
        }

        profileImage.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        continueButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Please enter your name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            showProgress(true)
            try {
                if (selectedImageUri != null) {
                    uploadToBackblaze(myPhone, name, selectedImageUri!!)
                } else {
                    // Retain existing photo if no new one was picked
                    db.collection("users").document(myPhone).get().addOnSuccessListener { sn ->
                        val existingUrl = sn.getString("profileImageUrl") ?: ""
                        saveProfileToFirestore(myPhone, name, existingUrl)
                    }.addOnFailureListener {
                        saveProfileToFirestore(myPhone, name, "")
                    }
                }
            } catch (e: Exception) {
                showProgress(false)
                Toast.makeText(this, "Operation failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showProgress(show: Boolean) {
        if (show) {
            @Suppress("DEPRECATION")
            progressDialog = ProgressDialog(this).apply {
                setMessage("Setting up your profile...")
                setCancelable(false)
                show()
            }
        } else {
            progressDialog?.dismiss()
        }
    }

    private fun uploadToBackblaze(phone: String, name: String, uri: Uri) {
        val cleanPhone = phone.replace("+", "").replace(" ", "")
        val fileName = "profile_${cleanPhone}.jpg"

        val auth = Base64.encodeToString("$b2KeyId:$b2AppKey".toByteArray(), Base64.NO_WRAP)
        val authRequest = Request.Builder()
            .url("https://api.backblazeb2.com/b2api/v2/b2_authorize_account")
            .addHeader("Authorization", "Basic $auth")
            .build()

        client.newCall(authRequest).enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        showProgress(false)
                        Toast.makeText(this@ProfileSetupActivity, "Auth Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    Log.e("B2_UPLOAD", "Auth Failed: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    val authBody = response.body?.string()
                    if (!response.isSuccessful || authBody == null) {
                        runOnUiThread {
                            showProgress(false)
                            Toast.makeText(this@ProfileSetupActivity, "Auth Server Error", Toast.LENGTH_SHORT).show()
                        }
                        return
                    }

                    try {
                        val authJson = JSONObject(authBody)
                        val apiUrl = authJson.getString("apiUrl")
                        val authToken = authJson.getString("authorizationToken")
                        val downloadUrl = authJson.getString("downloadUrl")

                        val getUploadUrlRequest = Request.Builder()
                            .url("$apiUrl/b2api/v2/b2_get_upload_url")
                            .addHeader("Authorization", authToken)
                            .post(
                                JSONObject().put("bucketId", b2BucketId).toString()
                                    .toRequestBody("application/json".toMediaType()),
                            )
                            .build()

                        client.newCall(getUploadUrlRequest).enqueue(
                            object : Callback {
                                override fun onResponse(call: Call, response: Response) {
                                    val responseBody = response.body?.string()
                                    if (!response.isSuccessful || responseBody == null) {
                                        runOnUiThread {
                                            showProgress(false)
                                            Toast.makeText(this@ProfileSetupActivity, "Failed to get upload URL", Toast.LENGTH_SHORT).show()
                                        }
                                        return
                                    }

                                    try {
                                        val uploadJson = JSONObject(responseBody)
                                        val uploadUrl = uploadJson.getString("uploadUrl")
                                        val uploadAuthToken = uploadJson.getString("authorizationToken")

                                        // Professional: Compress image before upload to prevent timeout
                                        val compressedFile = compressImage(uri) ?: uriToFile(uri)

                                        val uploadRequest = Request.Builder()
                                            .url(uploadUrl)
                                            .addHeader("Authorization", uploadAuthToken)
                                            .addHeader("X-Bz-File-Name", fileName)
                                            .addHeader("Content-Type", "image/jpeg")
                                            .addHeader("X-Bz-Content-Sha1", "do_not_verify")
                                            .post(compressedFile.asRequestBody("image/jpeg".toMediaType()))
                                            .build()

                                        client.newCall(uploadRequest).enqueue(
                                            object : Callback {
                                                override fun onResponse(call: Call, response: Response) {
                                                    if (response.isSuccessful) {
                                                        val finalUrl = "$downloadUrl/file/$b2BucketName/$fileName"
                                                        runOnUiThread {
                                                            saveProfileToFirestore(phone, name, finalUrl)
                                                        }
                                                    } else {
                                                        runOnUiThread {
                                                            showProgress(false)
                                                            Toast.makeText(
                                                                this@ProfileSetupActivity,
                                                                "Upload Failed: ${response.code}",
                                                                Toast.LENGTH_SHORT,
                                                            ).show()
                                                        }
                                                    }
                                                }

                                                override fun onFailure(call: Call, e: IOException) {
                                                    runOnUiThread {
                                                        showProgress(false)
                                                        Toast.makeText(this@ProfileSetupActivity, "Upload Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                        )
                                    } catch (_: Exception) {
                                        runOnUiThread {
                                            showProgress(false)
                                            Toast.makeText(this@ProfileSetupActivity, "Parse Error during upload", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }

                                override fun onFailure(call: Call, e: IOException) {
                                    runOnUiThread {
                                        showProgress(false)
                                        Toast.makeText(this@ProfileSetupActivity, "Network Error", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        )
                    } catch (_: Exception) {
                        runOnUiThread {
                            showProgress(false)
                            Toast.makeText(this@ProfileSetupActivity, "Auth Parse Error", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
        )
    }

    private fun saveProfileToFirestore(phone: String, name: String, imageUrl: String) {
        val updates = hashMapOf<String, Any>(
            "name" to name,
            "profileImageUrl" to imageUrl,
        )

        db.collection("users").document(phone)
            .set(updates, SetOptions.merge())
            .addOnSuccessListener {
                showProgress(false)
                val prefs = getSharedPreferences("chat_app_prefs", MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("profile_done", true)
                    .putString("profileImageUrl", imageUrl)
                    .putString("user_name", name)
                    .apply()

                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            .addOnFailureListener {
                showProgress(false)
                Toast.makeText(this, "Failed to save profile", Toast.LENGTH_SHORT).show()
            }
    }

    private fun uriToFile(uri: Uri): File {
        val profileFolder = StorageHelper.getMediaFolder(this, StorageHelper.MEDIA_PROFILE_PHOTOS)
        val file = File(profileFolder, "profile_${System.currentTimeMillis()}.jpg")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Log.e("STORAGE", "Profile save failed: ${e.message}")
            return File(cacheDir, "temp_profile.jpg").apply {
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(this).use { output -> input.copyTo(output) }
                }
            }
        }
        return file
    }

    private fun compressImage(uri: Uri): File? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (originalBitmap == null) return null

            // Resize to max 1024px while maintaining aspect ratio
            val maxSize = 1024
            var width = originalBitmap.width
            var height = originalBitmap.height

            if (width > maxSize || height > maxSize) {
                val ratio = width.toFloat() / height.toFloat()
                if (width > height) {
                    width = maxSize
                    height = (maxSize / ratio).toInt()
                } else {
                    height = maxSize
                    width = (maxSize * ratio).toInt()
                }
            }

            val resizedBitmap = Bitmap.createScaledBitmap(originalBitmap, width, height, true)
            val outputStream = ByteArrayOutputStream()
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)

            val profileFolder = StorageHelper.getMediaFolder(this, StorageHelper.MEDIA_PROFILE_PHOTOS)
            val compressedFile = File(profileFolder, "profile_comp_${System.currentTimeMillis()}.jpg")
            
            FileOutputStream(compressedFile).use { fos ->
                fos.write(outputStream.toByteArray())
            }
            
            compressedFile
        } catch (e: Exception) {
            Log.e("COMPRESS", "Image compression failed: ${e.message}")
            null
        }
    }
}
