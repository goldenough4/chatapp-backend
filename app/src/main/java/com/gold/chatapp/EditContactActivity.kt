package com.gold.chatapp

import android.os.Bundle
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class EditContactActivity : AppCompatActivity() {

    private lateinit var firstNameInput: TextInputEditText
    private lateinit var lastNameInput: TextInputEditText
    private lateinit var usernameInput: TextInputEditText
    private lateinit var countryCodeInput: TextInputEditText
    private lateinit var phoneInput: TextInputEditText
    private lateinit var syncSwitch: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var saveButton: MaterialButton

    private var contactPhone: String = ""
    private var contactName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_contact)

        contactPhone = intent.getStringExtra("contact_phone") ?: ""
        contactName = intent.getStringExtra("contact_name") ?: ""

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.edit_contact_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        firstNameInput = findViewById(R.id.edit_first_name)
        lastNameInput = findViewById(R.id.edit_last_name)
        usernameInput = findViewById(R.id.edit_username)
        countryCodeInput = findViewById(R.id.edit_country_code)
        phoneInput = findViewById(R.id.edit_phone_number)
        syncSwitch = findViewById(R.id.edit_switch_sync)
        saveButton = findViewById(R.id.btn_save_edit)

        val nameParts = contactName.split(" ")
        if (nameParts.isNotEmpty()) firstNameInput.setText(nameParts[0])
        if (nameParts.size > 1) {
            lastNameInput.setText(nameParts.subList(1, nameParts.size).joinToString(" "))
        }

        phoneInput.setText(contactPhone)

        saveButton.setOnClickListener {
            saveEditedContact()
        }

        // Setup dropdown for Sync To
        val accounts = arrayOf("Google Account", "Phone Storage", "SIM Card")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, accounts)
        val syncToDropdown = findViewById<AutoCompleteTextView>(R.id.edit_sync_account)
        syncToDropdown.setAdapter(adapter)
    }

    private fun saveEditedContact() {
        val fName = firstNameInput.text.toString().trim()
        val lName = lastNameInput.text.toString().trim()
        val rawPhone = phoneInput.text.toString().trim()
        val cCode = countryCodeInput.text.toString().trim()

        if (fName.isEmpty() || rawPhone.isEmpty()) {
            Toast.makeText(this, "First name and phone are required", Toast.LENGTH_SHORT).show()
            return
        }

        val fullName = if (lName.isEmpty()) fName else "$fName $lName"
        val fullPhone = if (rawPhone.startsWith("+")) rawPhone else "$cCode$rawPhone"
        val cleanPhone = fullPhone.replace(Regex("[\\s\\-()]"), "")

        // Use the same logic as AddContactActivity but update instead of just adding
        updateContactInPrefs(fullName, cleanPhone)
    }

    private fun updateContactInPrefs(newName: String, newPhone: String) {
        val prefs = getSharedPreferences("contacts_prefs", MODE_PRIVATE)
        val currentContacts = prefs.getStringSet("contacts", emptySet())?.toMutableSet() ?: mutableSetOf()

        // Remove old entry if phone changed or just to refresh
        currentContacts.removeAll { it.contains(contactPhone) }
        currentContacts.add("$newName|$newPhone")
        
        prefs.edit { putStringSet("contacts", currentContacts) }

        // Update map
        val mapString = prefs.getString("contacts_map", "") ?: ""
        val contactMap = mutableMapOf<String, String>()
        if (mapString.isNotEmpty()) {
            mapString.split(";").forEach { part ->
                val p = part.split("|")
                if (p.size == 2) contactMap[p[1]] = p[0]
            }
        }
        
        contactMap.remove(contactPhone)
        contactMap[newPhone] = newName

        val newMapString = contactMap.entries.joinToString(";") { "${it.value}|${it.key}" }
        prefs.edit { putString("contacts_map", newMapString) }

        val broadcastIntent = android.content.Intent("com.gold.chatapp.CONTACT_UPDATED").apply {
            putExtra("old_phone", contactPhone)
            putExtra("new_phone", newPhone)
            putExtra("new_name", newName)
        }
        sendBroadcast(broadcastIntent)

        Toast.makeText(this, "Contact Updated", Toast.LENGTH_SHORT).show()
        finish()
    }
}
