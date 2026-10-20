package com.gold.chatapp

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.firebase.firestore.FirebaseFirestore
import de.hdodenhof.circleimageview.CircleImageView
import java.text.SimpleDateFormat
import java.util.*

class CallsAdapter(private val callsList: List<CallLogItem>) :
    RecyclerView.Adapter<CallsAdapter.CallViewHolder>() {

    class CallViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val profileImage: CircleImageView = view.findViewById(R.id.call_item_image)
        val nameText: TextView = view.findViewById(R.id.call_item_name)
        val directionIcon: ImageView = view.findViewById(R.id.call_item_direction_icon)
        val timestampText: TextView = view.findViewById(R.id.call_item_timestamp)
        val actionBtn: ImageView = view.findViewById(R.id.call_item_action_btn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CallViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_call, parent, false)
        return CallViewHolder(view)
    }

    override fun onBindViewHolder(holder: CallViewHolder, position: Int) {
        val item = callsList[position]
        val context = holder.itemView.context

        val prefs = context.getSharedPreferences("chat_app_prefs", Context.MODE_PRIVATE)
        val myPhone = prefs.getString("user_phone", "") ?: ""
        val otherPhone = if (item.caller == myPhone) item.callee else item.caller

        // Check local contact name first
        var contactDisplayName = getLocalContactName(context, otherPhone)
        if (contactDisplayName.isEmpty()) {
            contactDisplayName = otherPhone
        }
        holder.nameText.text = contactDisplayName

        holder.timestampText.text = formatWhatsAppTimestamp(item.timestamp)

        // Direction & status indicators
        if (item.isOutgoing) {
            holder.directionIcon.setImageResource(R.drawable.ic_call_outgoing)
            holder.directionIcon.setColorFilter(Color.parseColor("#00A884"))
        } else {
            holder.directionIcon.setImageResource(R.drawable.ic_call_incoming)
            if (item.status == "answered" || item.status == "connected") {
                holder.directionIcon.setColorFilter(Color.parseColor("#00A884"))
            } else {
                holder.directionIcon.setColorFilter(Color.parseColor("#EA0038")) // Missed call
            }
        }

        // Action button (Voice or Video icon)
        if (item.callType == "video") {
            holder.actionBtn.setImageResource(R.drawable.ic_call_video)
        } else {
            holder.actionBtn.setImageResource(R.drawable.ic_call_speaker)
        }
        holder.actionBtn.setColorFilter(Color.parseColor("#00A884"))

        val finalName = contactDisplayName
        holder.actionBtn.setOnClickListener {
            val intent = Intent(context, CallActivity::class.java).apply {
                putExtra("other_phone", otherPhone)
                putExtra("contact_name", finalName)
                putExtra("call_type", item.callType)
                putExtra("is_incoming", false)
            }
            context.startActivity(intent)
        }

        holder.itemView.setOnClickListener {
            val intent = Intent(context, CallActivity::class.java).apply {
                putExtra("other_phone", otherPhone)
                putExtra("contact_name", finalName)
                putExtra("call_type", item.callType)
                putExtra("is_incoming", false)
            }
            context.startActivity(intent)
        }

        // Fetch Firestore profile info if needed
        FirebaseFirestore.getInstance()
            .collection("users").document(otherPhone).get()
            .addOnSuccessListener { sn ->
                if (sn != null && sn.exists()) {
                    val firestoreName = sn.getString("name") ?: ""
                    if (firestoreName.isNotEmpty() && contactDisplayName == otherPhone) {
                        holder.nameText.text = firestoreName
                    }
                    val avatar = sn.getString("profileImageUrl") ?: ""
                    if (avatar.isNotEmpty()) {
                        Glide.with(context)
                            .load(avatar)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .circleCrop()
                            .placeholder(R.drawable.ic_default_user)
                            .into(holder.profileImage)
                    }
                }
            }
    }

    private fun getLocalContactName(context: Context, phoneNumber: String): String {
        val prefs = context.getSharedPreferences("contacts_prefs", Context.MODE_PRIVATE)
        val mapString = prefs.getString("contacts_map", "") ?: ""
        if (mapString.isNotEmpty()) {
            val pairs = mapString.split(";")
            for (pair in pairs) {
                val parts = pair.split("|")
                if (parts.size == 2 && parts[1].trim() == phoneNumber.trim()) {
                    return parts[0]
                }
            }
        }
        return ""
    }

    private fun formatWhatsAppTimestamp(date: Date?): String {
        if (date == null) return "Recent"
        val now = Calendar.getInstance()
        val time = Calendar.getInstance().apply { time = date }

        val sameDay = now.get(Calendar.YEAR) == time.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == time.get(Calendar.DAY_OF_YEAR)

        if (sameDay) {
            return SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
        }

        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val isYesterday = yesterday.get(Calendar.YEAR) == time.get(Calendar.YEAR) &&
                yesterday.get(Calendar.DAY_OF_YEAR) == time.get(Calendar.DAY_OF_YEAR)

        if (isYesterday) {
            return "Yesterday, " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
        }

        return SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(date)
    }

    override fun getItemCount(): Int = callsList.size
}
