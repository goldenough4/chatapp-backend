package com.gold.chatapp

data class ChatItem(
    var name: String,
    var phone: String,
    val id: String, // Cleaned phone used for Firestore IDs
    var lastMessage: String = "",       // Preview text (e.g., "You: Hello")
    var lastMessageTime: String = "",   // Formatted time (e.g., "10:30 PM")
    var profileImageUrl: String = "",   // URL for Backblaze B2
    var lastTimestamp: Long = 0L,       // CRITICAL: Used for sorting the list (WhatsApp style)
)
