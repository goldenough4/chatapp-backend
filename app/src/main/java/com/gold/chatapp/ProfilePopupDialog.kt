package com.gold.chatapp

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders

class ProfilePopupDialog : DialogFragment() {

    private var contactId: String = ""
    private var contactName: String = ""
    private var profileImageUrl: String = ""
    private var b2Token: String = ""

    companion object {
        fun newInstance(
            id: String,
            name: String,
            imageUrl: String,
            token: String
        ): ProfilePopupDialog {
            val frag = ProfilePopupDialog()
            val args = Bundle()
            args.putString("id", id)
            args.putString("name", name)
            args.putString("image", imageUrl)
            args.putString("token", token)
            frag.arguments = args
            return frag
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contactId = arguments?.getString("id") ?: ""
        contactName = arguments?.getString("name") ?: ""
        profileImageUrl = arguments?.getString("image") ?: ""
        b2Token = arguments?.getString("token") ?: ""
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_profile_popup, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val nameText = view.findViewById<TextView>(R.id.popup_contact_name)
        val imageView = view.findViewById<ImageView>(R.id.popup_profile_image)
        val btnMessage = view.findViewById<ImageButton>(R.id.btn_popup_message)
        val btnCall = view.findViewById<ImageButton>(R.id.btn_popup_call)
        val btnVideo = view.findViewById<ImageButton>(R.id.btn_popup_video)
        val btnInfo = view.findViewById<ImageButton>(R.id.btn_popup_info)

        nameText.text = contactName

        val glideLoad: Any = if (b2Token.isNotEmpty() && profileImageUrl.contains("backblazeb2.com")) {
            GlideUrl(
                profileImageUrl,
                LazyHeaders.Builder().addHeader("Authorization", b2Token).build()
            )
        } else {
            profileImageUrl
        }

        Glide.with(this)
            .load(glideLoad)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .placeholder(R.drawable.ic_default_user)
            .error(R.drawable.ic_default_user)
            .into(imageView)

        btnMessage.setOnClickListener {
            dismiss()
            val intent = Intent(requireContext(), ChatActivity::class.java).apply {
                putExtra("other_phone", contactId)
                putExtra("contact_name", contactName)
                putExtra("contact_image", profileImageUrl)
            }
            startActivity(intent)
        }

        btnInfo.setOnClickListener {
            dismiss()
            val intent = Intent(requireContext(), ContactInfoActivity::class.java).apply {
                putExtra("other_phone", contactId)
                putExtra("contact_name", contactName)
                putExtra("contact_image", profileImageUrl)
            }
            startActivity(intent)
        }
        
        // Placeholder listeners for Call/Video
        btnCall.setOnClickListener { dismiss() }
        btnVideo.setOnClickListener { dismiss() }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return dialog
    }
}
