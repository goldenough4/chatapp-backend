package com.gold.chatapp

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.FirebaseFirestore

class AddContactActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_contact)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.add_contact_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val firstNameInput = findViewById<TextInputEditText>(R.id.input_first_name)
        val lastNameInput = findViewById<TextInputEditText>(R.id.input_last_name)
        val usernameInput = findViewById<TextInputEditText>(R.id.input_username)
        val phoneInput = findViewById<TextInputEditText>(R.id.input_phone)
        val emailInput = findViewById<TextInputEditText>(R.id.input_email)
        val countryCodeInput = findViewById<TextInputEditText>(R.id.input_country_code)
        val syncSwitch = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_sync)
        val saveButton = findViewById<MaterialButton>(R.id.btn_save)

        saveButton.setOnClickListener {
            val firstName = firstNameInput.text?.toString()?.trim() ?: ""
            val lastName = lastNameInput.text?.toString()?.trim() ?: ""
            val username = usernameInput.text?.toString()?.trim() ?: ""
            val rawPhone = phoneInput.text?.toString()?.trim() ?: ""
            val email = emailInput.text?.toString()?.trim() ?: ""
            val countryCode = countryCodeInput.text?.toString()?.trim() ?: ""

            if (firstName.isEmpty() || (rawPhone.isEmpty() && email.isEmpty())) {
                Toast.makeText(this, "First name and either phone or email are required", Toast.LENGTH_SHORT).show()
            } else {
                val fullName = if (lastName.isEmpty()) firstName else "$firstName $lastName"
                val identifier = if (email.isNotEmpty()) {
                    email.lowercase()
                } else {
                    val fullPhone = if (rawPhone.startsWith("+")) rawPhone else "$countryCode$rawPhone"
                    fullPhone.replace(Regex("[\\s\\-()]"), "")
                }
                
                searchAndSaveContact(fullName, identifier)
            }
        }
    }

    private fun searchAndSaveContact(name: String, identifier: String) {
        // Search the global "users" collection for this phone number or email by document ID or field query
        db.collection("users")
            .document(identifier)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val primaryId = document.getString("phone")?.takeIf { it.isNotEmpty() } 
                        ?: document.getString("email")?.takeIf { it.isNotEmpty() } 
                        ?: document.id
                    Toast.makeText(this, "Contact added: $name", Toast.LENGTH_SHORT).show()
                    saveToPrefs(name, primaryId)
                } else {
                    val fieldQueryField = if (identifier.contains("@")) "email" else "phone"
                    db.collection("users")
                        .whereEqualTo(fieldQueryField, identifier)
                        .get()
                        .addOnSuccessListener { querySnapshot ->
                            if (!querySnapshot.isEmpty) {
                                val doc = querySnapshot.documents[0]
                                val primaryId = doc.getString("phone")?.takeIf { it.isNotEmpty() } 
                                    ?: doc.getString("email")?.takeIf { it.isNotEmpty() } 
                                    ?: doc.id
                                Toast.makeText(this, "Contact added: $name", Toast.LENGTH_SHORT).show()
                                saveToPrefs(name, primaryId)
                            } else {
                                Toast.makeText(this, "User not registered, but added", Toast.LENGTH_SHORT).show()
                                saveToPrefs(name, identifier)
                            }
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "Contact added: $name", Toast.LENGTH_SHORT).show()
                            saveToPrefs(name, identifier)
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e("ADD_CONTACT", "Error: ${e.message}")
                Toast.makeText(this, "Contact added: $name", Toast.LENGTH_SHORT).show()
                saveToPrefs(name, identifier)
            }
    }

    private fun saveToPrefs(name: String, rawIdentifier: String) {
        val identifierToUse = rawIdentifier.trim().lowercase()
        val prefs = getSharedPreferences("contacts_prefs", MODE_PRIVATE)
        val currentContacts =
            prefs.getStringSet("contacts", emptySet())?.toMutableSet() ?: mutableSetOf()

        // 1. Remove any old entry for this exact identifier to prevent duplicate entries or stale names
        currentContacts.removeAll { entry ->
            val parts = entry.split("|")
            parts.size >= 2 && parts[1].trim().lowercase() == identifierToUse
        }

        // 2. Add the updated contact entry
        currentContacts.add("$name|$identifierToUse")

        // 3. Update the contacts_map (used for looking up names from identifiers)
        val mapString = prefs.getString("contacts_map", "") ?: ""
        val contactMap = mutableMapOf<String, String>()

        if (mapString.isNotEmpty()) {
            mapString.split(";").forEach { part ->
                val p = part.split("|")
                if (p.size == 2) {
                    val k = p[1].trim().lowercase()
                    val v = p[0].trim()
                    if (k.isNotEmpty()) contactMap[k] = v
                }
            }
        }

        contactMap[identifierToUse] = name

        val newMapString = contactMap.entries.asSequence()
            .filter { it.key.isNotEmpty() }
            .joinToString(";") { "${it.value}|${it.key}" }

        // Use a fresh HashSet copy and synchronous commit() to guarantee instant memory & disk sync
        prefs.edit()
            .putStringSet("contacts", HashSet(currentContacts))
            .putString("contacts_map", newMapString)
            .commit()

        // Broadcast to update MessagesFragment & UI lists in real time
        val broadcastIntent = android.content.Intent("com.gold.chatapp.CONTACT_UPDATED").apply {
            putExtra("phone", identifierToUse)
            putExtra("name", name)
        }
        sendBroadcast(broadcastIntent)

        Toast.makeText(this, "Contact added: $name", Toast.LENGTH_SHORT).show()
        finish()
    }
}
