package com.gold.chatapp

data class Chat(
    val id: String = "",                // The unique ID (usually the cleaned phone number)
    val name: String = "",              // Display name from contacts
    val phone: String = "",             // Raw phone number
    var lastMessage: String = "",       // The text shown in the list preview
    var lastMessageTime: String = "",   // e.g., "10:30 PM"
    var lastTimestamp: Long = 0L,       // CRITICAL: Used to sort the list (Newest on top)
    var profileImageUrl: String = "",   // The private B2 URL for the contact's face
    var unreadCount: Int = 0,           // Optional: for the green unread badge
)
