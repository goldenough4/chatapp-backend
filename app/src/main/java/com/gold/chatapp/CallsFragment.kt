package com.gold.chatapp

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date

class CallsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private val callsList = mutableListOf<CallLogItem>()
    private lateinit var callsAdapter: CallsAdapter
    private val db = FirebaseFirestore.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val view = inflater.inflate(R.layout.fragment_calls, container, false)
        recyclerView = view.findViewById(R.id.calls_recycler_view)
        emptyText = view.findViewById(R.id.empty_calls_text)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        callsAdapter = CallsAdapter(callsList)
        recyclerView.adapter = callsAdapter

        loadCalls()
        return view
    }

    private fun parseSafeDate(raw: Any?): Date? {
        return when (raw) {
            is com.google.firebase.Timestamp -> raw.toDate()
            is Long -> Date(raw)
            is Double -> Date(raw.toLong())
            is Number -> Date(raw.toLong())
            is Date -> raw
            else -> null
        }
    }

    private fun loadCalls() {
        val prefs = requireActivity().getSharedPreferences("chat_app_prefs", Context.MODE_PRIVATE)
        val myPhone = prefs.getString("user_phone", "") ?: ""
        if (myPhone.isEmpty()) return

        db.collection("calls")
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e("CALLS_FRAGMENT", "Firestore listener error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshots != null) {
                    callsList.clear()
                    for (doc in snapshots) {
                        val caller = doc.getString("caller") ?: ""
                        val callee = doc.getString("callee") ?: ""
                        if (caller == myPhone || callee == myPhone) {
                            val callType = doc.getString("callType") ?: "voice"
                            val status = doc.getString("status") ?: "ended"
                            val timestampDate = parseSafeDate(doc.get("timestamp"))

                            callsList.add(
                                CallLogItem(
                                    callId = doc.id,
                                    caller = caller,
                                    callee = callee,
                                    callType = callType,
                                    status = status,
                                    timestamp = timestampDate,
                                    isOutgoing = caller == myPhone
                                )
                            )
                        }
                    }
                    callsList.sortByDescending { it.timestamp }
                    callsAdapter.notifyDataSetChanged()
                    emptyText.visibility = if (callsList.isEmpty()) View.VISIBLE else View.GONE
                }
            }
    }
}
