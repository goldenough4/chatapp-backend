package com.gold.chatapp

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.*

class MessagesFragment : Fragment() {

    private val contactsList = mutableListOf<ChatItem>()
    private val filteredList = mutableListOf<ChatItem>()
    private lateinit var adapter: MessagesAdapter
    private val db = FirebaseFirestore.getInstance()

    private val activeListeners = mutableMapOf<String, ListenerRegistration>()
    private var currentB2Token: String = ""

    private lateinit var searchInput: EditText
    private lateinit var clearSearch: ImageView

    private var actionMode: android.view.ActionMode? = null

    private val actionModeCallback = object : android.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
            mode?.menuInflater?.inflate(R.menu.chat_selection_menu, menu)
            return true
        }

        override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
            mode?.title = "${adapter.getSelectedCount()} selected"
            return true
        }

        override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?): Boolean {
            return when (item?.itemId) {
                R.id.action_pin_chat -> {
                    Toast.makeText(context, "Chat pinned", Toast.LENGTH_SHORT).show()
                    mode?.finish()
                    true
                }
                R.id.action_delete_chat -> {
                    showDeleteConfirmationDialog()
                    true
                }
                R.id.action_mute_chat -> {
                    Toast.makeText(context, "Notifications muted", Toast.LENGTH_SHORT).show()
                    mode?.finish()
                    true
                }
                R.id.action_archive_chat -> {
                    Toast.makeText(context, "Chat archived", Toast.LENGTH_SHORT).show()
                    mode?.finish()
                    true
                }
                R.id.action_add_shortcut -> {
                    Toast.makeText(context, "Shortcut added to Home screen", Toast.LENGTH_SHORT).show()
                    mode?.finish()
                    true
                }
                R.id.action_view_contact -> {
                    val selectedIds = adapter.getSelectedIds()
                    if (selectedIds.isNotEmpty()) {
                        val firstPhone = selectedIds.first()
                        val intent = android.content.Intent(context, ContactInfoActivity::class.java).apply {
                            putExtra("contact_phone", firstPhone)
                            putExtra("contact_name", getLocalContactName(firstPhone))
                        }
                        startActivity(intent)
                    }
                    mode?.finish()
                    true
                }
                R.id.action_mark_unread -> {
                    Toast.makeText(context, "Marked as unread", Toast.LENGTH_SHORT).show()
                    mode?.finish()
                    true
                }
                R.id.action_select_all -> {
                    adapter.selectAll()
                    mode?.invalidate()
                    true
                }
                R.id.action_lock_chat -> {
                    Toast.makeText(context, "Chat locked with passcode", Toast.LENGTH_SHORT).show()
                    mode?.finish()
                    true
                }
                R.id.action_add_favorites -> {
                    Toast.makeText(context, "Added to Favorites", Toast.LENGTH_SHORT).show()
                    mode?.finish()
                    true
                }
                R.id.action_add_to_list -> {
                    Toast.makeText(context, "Added to list", Toast.LENGTH_SHORT).show()
                    mode?.finish()
                    true
                }
                R.id.action_clear_chat -> {
                    Toast.makeText(context, "Chat cleared", Toast.LENGTH_SHORT).show()
                    mode?.finish()
                    true
                }
                R.id.action_block_contact -> {
                    Toast.makeText(context, "Contact blocked", Toast.LENGTH_SHORT).show()
                    mode?.finish()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: android.view.ActionMode?) {
            adapter.clearSelection()
            actionMode = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = inflater.inflate(R.layout.fragment_messages, container, false)
        val recyclerView = view.findViewById<RecyclerView>(R.id.messagesRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        searchInput = view.findViewById(R.id.search_input)
        clearSearch = view.findViewById(R.id.clear_search)

        val prefs = requireContext().getSharedPreferences("chat_app_prefs", Context.MODE_PRIVATE)
        currentB2Token = prefs.getString("b2_auth_token", "") ?: ""

        adapter = MessagesAdapter(filteredList, currentB2Token)
        recyclerView.adapter = adapter

        adapter.onSelectionChanged = { isInSelectionMode ->
            if (isInSelectionMode) {
                startSelectionMode()
            } else {
                finishSelectionMode()
            }
        }

        setupSearch()

        return view
    }

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                filter(s.toString())
                clearSearch.isVisible = !s.isNullOrEmpty()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        clearSearch.setOnClickListener {
            searchInput.setText("")
        }
    }

    private fun startSelectionMode() {
        if (actionMode == null) {
            actionMode = activity?.startActionMode(actionModeCallback)
        }
        actionMode?.invalidate() // Update title
    }

    private fun finishSelectionMode() {
        actionMode?.finish()
    }

    private fun showDeleteConfirmationDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete this chat?")
            .setMessage("Messages will be removed from this device.")
            .setPositiveButton("Delete Chat") { _, _ ->
                deleteSelectedChats()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSelectedChats() {
        val selectedIds = adapter.getSelectedIds()
        val prefs = requireContext().getSharedPreferences("contacts_prefs", Context.MODE_PRIVATE)
        val currentContacts = prefs.getStringSet("contacts", emptySet())?.toMutableSet() ?: mutableSetOf()

        for (id in selectedIds) {
            // 1. Remove from local preferences
            currentContacts.removeAll { it.contains(id) }
            
            // 2. Remove from internal list and listeners
            contactsList.removeAll { it.id == id }
            activeListeners["user_$id"]?.remove()
            activeListeners.remove("user_$id")
            
            // Note: We don't delete Firestore data here to preserve it for the other user.
        }

        prefs.edit().putStringSet("contacts", currentContacts).apply()
        
        // Update the contacts_map as well
        val mapString = prefs.getString("contacts_map", "") ?: ""
        val contactMap = mutableMapOf<String, String>()
        if (mapString.isNotEmpty()) {
            mapString.split(";").forEach { part ->
                val p = part.split("|")
                if (p.size == 2) contactMap[p[1]] = p[0]
            }
            for (id in selectedIds) {
                contactMap.remove(id)
            }
            val newMapString = contactMap.entries.joinToString(";") { "${it.value}|${it.key}" }
            prefs.edit().putString("contacts_map", newMapString).apply()
        }

        adapter.clearSelection()
        filter(searchInput.text.toString())
        Toast.makeText(context, "Chat deleted", Toast.LENGTH_SHORT).show()
    }

    private fun filter(query: String) {
        filteredList.clear()
        if (query.isEmpty()) {
            filteredList.addAll(contactsList)
        } else {
            val lowerCaseQuery = query.lowercase(Locale.getDefault())
            for (contact in contactsList) {
                if (contact.name.lowercase(Locale.getDefault()).contains(lowerCaseQuery) ||
                    contact.phone.contains(lowerCaseQuery)) {
                    filteredList.add(contact)
                }
            }
        }
        adapter.notifyDataSetChanged()
    }

    private val contactUpdateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (intent?.action == "com.gold.chatapp.CONTACT_UPDATED") {
                loadContacts()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = android.content.IntentFilter("com.gold.chatapp.CONTACT_UPDATED")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(contactUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            requireContext().registerReceiver(contactUpdateReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            requireContext().unregisterReceiver(contactUpdateReceiver)
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        val prefs = requireContext().getSharedPreferences("chat_app_prefs", Context.MODE_PRIVATE)
        val updatedToken = prefs.getString("b2_auth_token", "") ?: ""

        if (updatedToken.isNotEmpty() && (updatedToken != currentB2Token)) {
            updateFragmentToken(updatedToken)
        }

        loadContacts()
    }

    fun updateFragmentToken(token: String) {
        this.currentB2Token = token
        if (::adapter.isInitialized) {
            adapter.updateToken(token)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadContacts() {
        val context = context ?: return
        val prefs = context.getSharedPreferences("contacts_prefs", Context.MODE_PRIVATE)
        val loginPrefs = context.getSharedPreferences("chat_app_prefs", Context.MODE_PRIVATE)

        val myPhone = loginPrefs.getString("user_phone", "") ?: ""
        val savedContacts = prefs.getStringSet("contacts", emptySet()) ?: emptySet()

        Log.d("CONTACTS", "Loading contacts. Count: ${savedContacts.size}")

        // Remove old listeners to prevent memory leaks and duplicate updates
        for (listener in activeListeners.values) listener.remove()
        activeListeners.clear()
        
        contactsList.clear()
        val myCleanId = myPhone.replace(Regex("[^0-9+]"), "").trim()

        for (item in savedContacts) {
            val parts = item.split("|")
            if (parts.size >= 2) {
                val name = parts[0].trim()
                val phoneRaw = parts[1].trim()
                val cleanPhone = phoneRaw.replace(Regex("[^0-9+]"), "").trim()

                if (cleanPhone.isNotEmpty() && (cleanPhone != myCleanId)) {
                    val chatItem = ChatItem(
                        id = cleanPhone,
                        name = name,
                        phone = phoneRaw,
                    )
                    contactsList.add(chatItem)
                    setupListenersForContact(cleanPhone, myCleanId)
                }
            }
        }

        filter(searchInput.text.toString())
    }

    private fun getLocalContactName(phoneNumber: String): String {
        val ctx = context ?: return phoneNumber
        val prefs = ctx.getSharedPreferences("contacts_prefs", Context.MODE_PRIVATE)
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

    private fun setupListenersForContact(otherPhone: String, myPhone: String) {
        // 1. Profile Picture & Name Listener (Sync from Server)
        val userListener = db.collection("users").document(otherPhone)
            .addSnapshotListener { snapshot, _ ->
                if ((snapshot != null) && snapshot.exists()) {
                    val profilePic = snapshot.getString("profileImageUrl") ?: ""
                    val serverName = snapshot.getString("name") ?: ""
                    val localName = getLocalContactName(otherPhone)
                    
                    updateContactUI(otherPhone) { contact ->
                        contact.profileImageUrl = profilePic
                        if (localName != otherPhone && localName.isNotEmpty()) {
                            contact.name = localName
                        } else if (serverName.isNotEmpty()) {
                            contact.name = serverName
                        }
                    }
                }
            }
        activeListeners["user_$otherPhone"] = userListener

        // 2. Real-time Last Message & Sorting Listener
        val sortedPhones = listOf(myPhone, otherPhone).sorted()
        val chatId = "chat_${sortedPhones[0]}_${sortedPhones[1]}"

        val chatListener = db.collection("chats").document(chatId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snapshots, _ ->
                if (snapshots != null && !snapshots.isEmpty) {
                    val doc = snapshots.documents[0]
                    val sender = doc.getString("sender") ?: ""
                    val type = doc.getString("messageType") ?: "text"

                    var messagePreview = when (type) {
                        "image" -> "📷 Photo"
                        "video" -> "🎥 Video"
                        "voice" -> "🎤 Voice message"
                        else -> doc.getString("text") ?: ""
                    }

                    if (sender == myPhone) {
                        messagePreview = "You: $messagePreview"
                    }

                    val rawTs = doc.get("timestamp")
                    val timeInMillis = when (rawTs) {
                        is com.google.firebase.Timestamp -> rawTs.toDate().time
                        is Long -> rawTs
                        is Double -> rawTs.toLong()
                        is Number -> rawTs.toLong()
                        else -> System.currentTimeMillis()
                    }

                    updateContactUI(otherPhone) { contact ->
                        contact.lastMessage = messagePreview
                        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
                        contact.lastMessageTime = sdf.format(Date(timeInMillis))
                        contact.lastTimestamp = timeInMillis
                    }

                    // Re-sort the list so newest conversations jump to the top
                    sortContactsByTime()
                }
            }
        activeListeners["chat_$chatId"] = chatListener
    }

    private fun updateContactUI(phone: String, updateLogic: (ChatItem) -> Unit) {
        val index = contactsList.indexOfFirst { it.id == phone }
        if (index != -1) {
            updateLogic(contactsList[index])
            val filteredIndex = filteredList.indexOfFirst { it.id == phone }
            activity?.runOnUiThread {
                if (isAdded && filteredIndex != -1) {
                    adapter.notifyItemChanged(filteredIndex)
                }
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun sortContactsByTime() {
        contactsList.sortByDescending { it.lastTimestamp }
        filter(searchInput.text.toString())
    }

    override fun onPause() {
        super.onPause()
        for (listener in activeListeners.values) listener.remove()
        activeListeners.clear()
    }
}
