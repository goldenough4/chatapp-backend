package com.gold.chatapp

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
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
        val countryCodeInput = findViewById<TextInputEditText>(R.id.input_country_code)
        val syncSwitch = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_sync)
        val saveButton = findViewById<MaterialButton>(R.id.btn_save)

        saveButton.setOnClickListener {
            val firstName = firstNameInput.text?.toString()?.trim() ?: ""
            val lastName = lastNameInput.text?.toString()?.trim() ?: ""
            val username = usernameInput.text?.toString()?.trim() ?: ""
            val rawPhone = phoneInput.text?.toString()?.trim() ?: ""
            val countryCode = countryCodeInput.text?.toString()?.trim() ?: ""
            val isSyncEnabled = syncSwitch.isChecked

            if (firstName.isEmpty() || rawPhone.isEmpty()) {
                Toast.makeText(this, "First name and phone are required", Toast.LENGTH_SHORT).show()
            } else {
                val fullName = if (lastName.isEmpty()) firstName else "$firstName $lastName"
                val fullPhone = if (rawPhone.startsWith("+")) rawPhone else "$countryCode$rawPhone"
                val cleanPhone = fullPhone.replace(Regex("[\\s\\-()]"), "")
                
                // Note: Username and sync preference can be handled here if needed in future schema updates
                searchAndSaveContact(fullName, cleanPhone)
            }
        }
    }

    private fun searchAndSaveContact(name: String, cleanPhone: String) {
        // Search the global "users" collection for this cleaned phone number
        db.collection("users")
            .document(cleanPhone)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    saveToPrefs(name, cleanPhone)
                } else {
                    // Still save it so the user can attempt to message them
                    Toast.makeText(this, "User not registered, but added", Toast.LENGTH_SHORT)
                        .show()
                    saveToPrefs(name, cleanPhone)
                }
            }
            .addOnFailureListener { e ->
                Log.e("ADD_CONTACT", "Error: ${e.message}")
                saveToPrefs(name, cleanPhone)
            }
    }

    private fun saveToPrefs(name: String, phoneToUse: String) {
        val prefs = getSharedPreferences("contacts_prefs", MODE_PRIVATE)
        val currentContacts =
            prefs.getStringSet("contacts", emptySet())?.toMutableSet() ?: mutableSetOf()

        // 1. Add to the Set for the main list
        currentContacts.add("$name|$phoneToUse")
        prefs.edit { putStringSet("contacts", currentContacts) }

        // 2. Update the contacts_map (used for looking up names from phone numbers)
        val mapString = prefs.getString("contacts_map", "") ?: ""
        val contactMap = mutableMapOf<String, String>()

        if (mapString.isNotEmpty()) {
            mapString.split(";").forEach { part ->
                val p = part.split("|")
                if (p.size == 2) contactMap[p[1]] = p[0]
            }
        }

        contactMap[phoneToUse] = name

        val newMapString = contactMap.entries.asSequence()
            .filter { it.key.isNotEmpty() }
            .joinToString(";") { "${it.value}|${it.key}" }

        prefs.edit { putString("contacts_map", newMapString) }

        Toast.makeText(this, "Contact added: $name", Toast.LENGTH_SHORT).show()
        finish()
    }
}
