package com.gold.chatapp

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.google.firebase.firestore.FirebaseFirestore

class ContactInfoActivity : AppCompatActivity() {

    private lateinit var profileImage: ImageView
    private lateinit var nameText: TextView
    private lateinit var phoneText: TextView
    private lateinit var statusText: TextView

    private val db = FirebaseFirestore.getInstance()
    private var contactId: String = ""
    private var contactName: String = ""
    private var contactImage: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_info)

        contactId = intent.getStringExtra("other_phone") ?: ""
        contactName = intent.getStringExtra("contact_name") ?: "Contact"
        contactImage = intent.getStringExtra("contact_image") ?: ""

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.info_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        profileImage = findViewById(R.id.info_profile_image)
        nameText = findViewById(R.id.info_contact_name)
        phoneText = findViewById(R.id.info_contact_phone)
        statusText = findViewById(R.id.info_contact_status)

        nameText.text = contactName
        phoneText.text = contactId

        loadContactData()
        setupButtons()
    }

    private var userListenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null

    private fun getLocalContactName(phoneNumber: String): String {
        val prefs = getSharedPreferences("contacts_prefs", MODE_PRIVATE)
        val mapString = prefs.getString("contacts_map", "") ?: ""
        if (mapString.isNotEmpty()) {
            val pairs = mapString.split(";")
            for (pair in pairs) {
                val parts = pair.split("|")
                if (parts.size == 2 && parts[1].trim() == phoneNumber.trim()) return parts[0]
            }
        }
        return phoneNumber
    }

    override fun onResume() {
        super.onResume()
        loadContactData()
    }

    override fun onStop() {
        super.onStop()
        userListenerRegistration?.remove()
    }

    private fun loadContactData() {
        val localName = getLocalContactName(contactId)
        if (localName != contactId && localName.isNotEmpty()) {
            nameText.text = localName
        } else {
            nameText.text = contactName
        }
        phoneText.text = contactId

        val prefs = getSharedPreferences("chat_app_prefs", MODE_PRIVATE)
        val b2Token = prefs.getString("b2_auth_token", "") ?: ""

        userListenerRegistration?.remove()
        userListenerRegistration = db.collection("users").document(contactId).addSnapshotListener { sn, _ ->
            if (sn != null && sn.exists()) {
                val serverName = sn.getString("name") ?: contactName
                val avatar = sn.getString("profileImageUrl") ?: contactImage
                val bio = sn.getString("about") ?: "Hey there! I am using Chat Circle."

                val freshLocalName = getLocalContactName(contactId)
                if (freshLocalName != contactId && freshLocalName.isNotEmpty()) {
                    nameText.text = freshLocalName
                } else if (serverName.isNotEmpty()) {
                    nameText.text = serverName
                }

                statusText.text = bio

                if (avatar.isNotEmpty()) {
                    val glideLoad: Any =
                        if (b2Token.isNotEmpty() && avatar.contains("backblazeb2.com")) {
                            GlideUrl(
                                avatar,
                                LazyHeaders.Builder().addHeader("Authorization", b2Token).build(),
                            )
                        } else avatar

                    Glide.with(this).load(glideLoad).diskCacheStrategy(DiskCacheStrategy.ALL)
                        .circleCrop().placeholder(R.drawable.ic_default_user).into(profileImage)
                }
            }
        }
    }

    private fun setupButtons() {
        findViewById<android.view.View>(R.id.btn_info_message).setOnClickListener {
            val intent = Intent(this, ChatActivity::class.java).apply {
                putExtra("other_phone", contactId)
                putExtra("contact_name", contactName)
                putExtra("contact_image", contactImage)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
            finish()
        }

        findViewById<android.view.View>(R.id.btn_info_call).setOnClickListener {
            val intent = Intent(this, CallActivity::class.java).apply {
                putExtra("other_phone", contactId)
                putExtra("contact_name", nameText.text.toString())
                putExtra("call_type", "voice")
                putExtra("is_incoming", false)
            }
            startActivity(intent)
        }

        findViewById<android.view.View>(R.id.btn_info_video).setOnClickListener {
            val intent = Intent(this, CallActivity::class.java).apply {
                putExtra("other_phone", contactId)
                putExtra("contact_name", nameText.text.toString())
                putExtra("call_type", "video")
                putExtra("is_incoming", false)
            }
            startActivity(intent)
        }
        
        findViewById<android.view.View>(R.id.btn_clear_chat).setOnClickListener {
            Toast.makeText(this, "Chat cleared", Toast.LENGTH_SHORT).show()
        }

        findViewById<android.view.View>(R.id.btn_block_contact).setOnClickListener {
            Toast.makeText(this, "Contact blocked", Toast.LENGTH_SHORT).show()
        }

        findViewById<android.view.View>(R.id.btn_report_contact).setOnClickListener {
            Toast.makeText(this, "Contact reported", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.contact_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_share -> {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Contact: $contactName ($contactId)")
                }
                startActivity(Intent.createChooser(shareIntent, "Share Contact"))
                true
            }
            R.id.action_edit -> {
                val intent = Intent(this, EditContactActivity::class.java).apply {
                    putExtra("contact_phone", contactId)
                    putExtra("contact_name", contactName)
                }
                startActivity(intent)
                true
            }
            R.id.action_view_address_book -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("content://contacts/people/"))
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this, "Address book unavailable", Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_verify_security -> {
                Toast.makeText(this, "Security code for $contactName verified", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
