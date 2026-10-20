package com.gold.chatapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import de.hdodenhof.circleimageview.CircleImageView

class MessagesAdapter(
    private val contacts: List<ChatItem>,
    private var b2Token: String,
) : RecyclerView.Adapter<MessagesAdapter.ContactViewHolder>() {

    private val selectedIds = mutableSetOf<String>()
    var onSelectionChanged: ((Boolean) -> Unit)? = null

    fun isSelectionMode() = selectedIds.isNotEmpty()
    fun getSelectedCount() = selectedIds.size
    fun getSelectedIds() = selectedIds.toList()

    fun clearSelection() {
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(false)
    }

    fun selectAll() {
        selectedIds.clear()
        selectedIds.addAll(contacts.map { it.id })
        notifyDataSetChanged()
        onSelectionChanged?.invoke(true)
    }

    class ContactViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val contactName: TextView = view.findViewById(R.id.contact_name)
        val lastMessage: TextView = view.findViewById(R.id.contact_last_message)
        val contactTime: TextView = view.findViewById(R.id.contact_time)
        val profileImage: CircleImageView = view.findViewById(R.id.contact_profile_image)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateToken(newToken: String) {
        this.b2Token = newToken
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ContactViewHolder(view)
    }

    private fun getAuthorizedUrl(url: String?): Any {
        if (url.isNullOrEmpty()) return R.drawable.ic_default_user

        // Secure access to private B2 profile images with guaranteed 100% token usage
        if (url.contains("backblazeb2.com") && b2Token.isNotEmpty()) {
            return try {
                GlideUrl(
                    url,
                    LazyHeaders.Builder()
                        .addHeader("Authorization", b2Token)
                        .build(),
                )
            } catch (e: Exception) {
                url
            }
        }
        return url
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        val contact = contacts[position]

        holder.contactName.text = contact.name
        holder.lastMessage.text = contact.lastMessage.ifEmpty { "No messages yet" }
        holder.contactTime.text = contact.lastMessageTime

        // Load Profile Image with B2 Authorization - This ensures private bucket images show up
        val profileSource = getAuthorizedUrl(contact.profileImageUrl)

        Glide.with(holder.itemView.context)
            .load(profileSource)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .placeholder(R.drawable.ic_default_user)
            .error(R.drawable.ic_default_user)
            .into(holder.profileImage)

        holder.profileImage.setOnClickListener {
            val activity = holder.itemView.context as? androidx.appcompat.app.AppCompatActivity
            activity?.let {
                ProfilePopupDialog.newInstance(
                    id = contact.id,
                    name = contact.name,
                    imageUrl = contact.profileImageUrl,
                    token = b2Token,
                ).show(it.supportFragmentManager, "profile_popup")
            }
        }

        // Selection UI feedback
        val isSelected = selectedIds.contains(contact.id)
        holder.itemView.setBackgroundColor(
            if (isSelected) 0x33075E54.toInt() else 0 // 20% opacity WhatsApp teal highlight
        )

        holder.itemView.setOnLongClickListener {
            if (selectedIds.contains(contact.id)) {
                selectedIds.remove(contact.id)
            } else {
                selectedIds.add(contact.id)
            }
            notifyItemChanged(position)
            onSelectionChanged?.invoke(isSelectionMode())
            true
        }

        holder.itemView.setOnClickListener {
            if (isSelectionMode()) {
                if (selectedIds.contains(contact.id)) {
                    selectedIds.remove(contact.id)
                } else {
                    selectedIds.add(contact.id)
                }
                notifyItemChanged(position)
                onSelectionChanged?.invoke(isSelectionMode())
            } else {
                val context = holder.itemView.context
                val prefs = context.getSharedPreferences("chat_app_prefs", Context.MODE_PRIVATE)
                val myPhone = prefs.getString("user_phone", "") ?: ""

                val intent = Intent(context, ChatActivity::class.java).apply {
                    putExtra("my_phone", myPhone)
                    putExtra("other_phone", contact.id)
                    putExtra("contact_name", contact.name)
                    putExtra("contact_image", contact.profileImageUrl)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(intent)
            }
        }
    }

    override fun getItemCount(): Int = contacts.size
}
