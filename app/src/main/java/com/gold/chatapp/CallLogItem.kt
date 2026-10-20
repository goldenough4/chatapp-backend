package com.gold.chatapp

import java.util.Date

data class CallLogItem(
    val callId: String = "",
    val caller: String = "",
    val callee: String = "",
    val callType: String = "voice",
    val status: String = "ended",
    val timestamp: Date? = null,
    val isOutgoing: Boolean = true
)
