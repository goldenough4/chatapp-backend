package com.gold.chatapp

import android.app.ProgressDialog
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ProfileActivity : AppCompatActivity() {

    private lateinit var profileImage: ImageView
    private lateinit var nameEdit: EditText
    private lateinit var aboutEdit: EditText
    private lateinit var usernameEdit: EditText
    private lateinit var phoneDisplay: EditText
    private lateinit var linksEdit: EditText

    private lateinit var changePhotoButton: FloatingActionButton

    private var selectedImageUri: Uri? = null
    private val db = FirebaseFirestore.getInstance()
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).build()

    private val b2KeyId = "005cb4d249b27e40000000002"
    private val b2AppKey = "K005bZcXIuOMKUjwp2gj6WXuGM7dxdI"
    private val b2BucketId = "6cdbc4edb244590b92c70e14"
    private val b2BucketName = "chatapp-voice"

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
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
                Log.e("PROFILE_ACTIVITY", "Failed to load image: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.profile_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        profileImage = findViewById(R.id.profile_large_image)
        nameEdit = findViewById(R.id.profile_name_edit)
        aboutEdit = findViewById(R.id.profile_about_edit)
        usernameEdit = findViewById(R.id.profile_username_edit)
        phoneDisplay = findViewById(R.id.profile_phone_display)
        linksEdit = findViewById(R.id.profile_links_edit)
        changePhotoButton = findViewById(R.id.btn_change_photo)

        val prefs = getSharedPreferences("chat_app_prefs", MODE_PRIVATE)
        val myPhone = prefs.getString("user_phone", "") ?: ""
        phoneDisplay.setText(myPhone)

        changePhotoButton.setOnClickListener { pickImageLauncher.launch("image/*") }

        loadExistingProfile(myPhone)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.profile_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == R.id.action_save) {
            saveProfile()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadExistingProfile(phone: String) {
        val prefs = getSharedPreferences("chat_app_prefs", MODE_PRIVATE)
        val b2Token = prefs.getString("b2_auth_token", "") ?: ""

        db.collection("users").document(phone).get().addOnSuccessListener { sn ->
            if (sn != null && sn.exists()) {
                nameEdit.setText(sn.getString("name") ?: "")
                aboutEdit.setText(sn.getString("about") ?: "")
                usernameEdit.setText(sn.getString("username") ?: "")
                linksEdit.setText(sn.getString("links") ?: "")

                val avatar = sn.getString("profileImageUrl") ?: ""
                if (avatar.isNotEmpty()) {
                    val glideLoad: Any = if (b2Token.isNotEmpty() && avatar.contains("backblazeb2.com")) {
                        GlideUrl(avatar, LazyHeaders.Builder().addHeader("Authorization", b2Token).build())
                    } else avatar

                    Glide.with(this).load(glideLoad).diskCacheStrategy(DiskCacheStrategy.ALL).circleCrop().placeholder(R.drawable.ic_default_user).into(profileImage)
                }
            }
        }
    }

    private fun saveProfile() {
        val prefs = getSharedPreferences("chat_app_prefs", MODE_PRIVATE)
        val myPhone = prefs.getString("user_phone", "") ?: ""
        val name = nameEdit.text.toString().trim()
        val about = aboutEdit.text.toString().trim()
        val username = usernameEdit.text.toString().trim()
        val links = linksEdit.text.toString().trim()

        if (name.isEmpty()) {
            Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        @Suppress("DEPRECATION")
        val pd = ProgressDialog(this).apply { setMessage("Syncing profile..."); setCancelable(false); show() }

        if (selectedImageUri != null) {
            uploadImageToB2(myPhone) { url ->
                val updates = mapOf("name" to name, "about" to about, "username" to username, "links" to links, "profileImageUrl" to url)
                db.collection("users").document(myPhone).set(updates, SetOptions.merge()).addOnSuccessListener {
                    pd.dismiss()
                    prefs.edit().putString("profileImageUrl", url).putString("user_name", name).apply()
                    Toast.makeText(this, "Profile Synced", Toast.LENGTH_SHORT).show()
                    finish()
                }.addOnFailureListener { pd.dismiss(); Toast.makeText(this, "Sync Failed", Toast.LENGTH_SHORT).show() }
            }
        } else {
            val updates = mapOf("name" to name, "about" to about, "username" to username, "links" to links)
            db.collection("users").document(myPhone).set(updates, SetOptions.merge()).addOnSuccessListener {
                pd.dismiss()
                prefs.edit().putString("user_name", name).apply()
                Toast.makeText(this, "Profile Synced", Toast.LENGTH_SHORT).show()
                finish()
            }.addOnFailureListener { pd.dismiss(); Toast.makeText(this, "Sync Failed", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun uploadImageToB2(phone: String, onUrlReady: (String) -> Unit) {
        val fileName = "profile_${phone.replace("+", "")}.jpg"
        val auth = Base64.encodeToString("$b2KeyId:$b2AppKey".toByteArray(), Base64.NO_WRAP)
        
        val authReq = Request.Builder().url("https://api.backblazeb2.com/b2api/v2/b2_authorize_account").addHeader("Authorization", "Basic $auth").build()
        client.newCall(authReq).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { runOnUiThread { Toast.makeText(this@ProfileActivity, "Auth Fail", Toast.LENGTH_SHORT).show() } }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                val authJson = JSONObject(body)
                val apiUrl = authJson.getString("apiUrl")
                val token = authJson.getString("authorizationToken")
                val dlUrl = authJson.getString("downloadUrl")

                val getUrlReq = Request.Builder().url("$apiUrl/b2api/v2/b2_get_upload_url").addHeader("Authorization", token).post(JSONObject().put("bucketId", b2BucketId).toString().toRequestBody("application/json".toMediaType())).build()
                client.newCall(getUrlReq).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {}
                    override fun onResponse(call: Call, response: Response) {
                        val upBody = response.body?.string() ?: return
                        val upJson = JSONObject(upBody)
                        val upUrl = upJson.getString("uploadUrl")
                        val upToken = upJson.getString("authorizationToken")

                        val uri = selectedImageUri ?: return
                        val inputStream = contentResolver.openInputStream(uri)
                        val bytes = inputStream?.readBytes() ?: return
                        val upReq = Request.Builder().url(upUrl).addHeader("Authorization", upToken).addHeader("X-Bz-File-Name", fileName).addHeader("Content-Type", "image/jpeg").addHeader("X-Bz-Content-Sha1", "do_not_verify").post(bytes.toRequestBody("image/jpeg".toMediaType())).build()
                        client.newCall(upReq).enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {}
                            override fun onResponse(call: Call, response: Response) {
                                if (response.isSuccessful) {
                                    runOnUiThread { onUrlReady("$dlUrl/file/$b2BucketName/$fileName") }
                                }
                            }
                        })
                    }
                })
            }
        })
    }
}
