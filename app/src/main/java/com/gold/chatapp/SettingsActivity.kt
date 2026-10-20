package com.gold.chatapp

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.google.android.material.card.MaterialCardView
import com.google.firebase.firestore.FirebaseFirestore

class SettingsActivity : AppCompatActivity() {

    private lateinit var profileHeader: MaterialCardView
    private lateinit var profileImage: ImageView
    private lateinit var userNameText: TextView
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.settings_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        profileHeader = findViewById(R.id.profile_header_card)
        profileImage = findViewById(R.id.header_profile_image)
        userNameText = findViewById(R.id.header_user_name)

        profileHeader.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        loadProfileHeader()
    }

    private fun loadProfileHeader() {
        val prefs = getSharedPreferences("chat_app_prefs", MODE_PRIVATE)
        val myPhone = prefs.getString("user_phone", "") ?: ""
        val b2Token = prefs.getString("b2_auth_token", "") ?: ""

        db.collection("users").document(myPhone).addSnapshotListener { snapshot, _ ->
            if (snapshot != null && snapshot.exists()) {
                val name = snapshot.getString("name") ?: "User Profile"
                val avatarUrl = snapshot.getString("profileImageUrl") ?: ""
                
                userNameText.text = name

                if (avatarUrl.isNotEmpty()) {
                    val glideLoad: Any = if (b2Token.isNotEmpty() && avatarUrl.contains("backblazeb2.com")) {
                        GlideUrl(avatarUrl, LazyHeaders.Builder().addHeader("Authorization", b2Token).build())
                    } else {
                        avatarUrl
                    }

                    Glide.with(this)
                        .load(glideLoad)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .circleCrop()
                        .placeholder(R.drawable.ic_default_user)
                        .into(profileImage)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadProfileHeader()
    }
}
