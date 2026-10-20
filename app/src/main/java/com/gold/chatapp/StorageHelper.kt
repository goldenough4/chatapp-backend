package com.gold.chatapp

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object StorageHelper {

    private const val TAG = "StorageHelper"
    private const val MAIN_APP_DIR = "ChatApp"

    // Root Subfolders
    const val BACKUPS = "Backups"
    const val DATABASES = "Databases"
    const val MEDIA = "Media"

    // Media Subfolders
    const val MEDIA_LINKS = ".Links"
    const val MEDIA_STATUSES = ".Statuses"
    const val MEDIA_WAMO_CACHE = ".wamocache"
    const val MEDIA_WALLPAPER = "WallPaper"
    const val MEDIA_AI_MEDIA = "ChatApp AI Media"
    const val MEDIA_ANIMATED_GIFS = "ChatApp Animated Gifs"
    const val MEDIA_AUDIO = "ChatApp Audio"
    const val MEDIA_BACKUP_EXCLUDED_STICKERS = "ChatApp Backup Excluded Stickers"
    const val MEDIA_BUG_REPORT_ATTACHMENTS = "ChatApp Bug Report Attachments"
    const val MEDIA_DOCUMENTS = "ChatApp Documents"
    const val MEDIA_IMAGES = "ChatApp Images"
    const val MEDIA_PROFILE_PHOTOS = "ChatApp Profile Photos"
    const val MEDIA_STICKER_PACKS = "ChatApp Sticker Packs"
    const val MEDIA_STICKERS = "ChatApp Stickers"
    const val MEDIA_VIDEO = "ChatApp Video"
    const val MEDIA_VIDEO_NOTES = "ChatApp Video Notes"
    const val MEDIA_VOICE_NOTES = "ChatApp Voice Notes"

    /**
     * Initializes the entire ChatApp folder structure inside Android/media/{package}/
     */
    fun initChatAppFolders(context: Context) {
        try {
            val root = getAppRootFolder(context)
            if (!root.exists()) root.mkdirs()

            // 1. Create Root folders
            val rootFolders = listOf(BACKUPS, DATABASES, MEDIA)
            for (folderName in rootFolders) {
                File(root, folderName).takeIf { !it.exists() }?.mkdirs()
            }

            // 2. Create Media subfolders
            val mediaRoot = File(root, MEDIA)
            val mediaFolders = listOf(
                MEDIA_LINKS, MEDIA_STATUSES, MEDIA_WAMO_CACHE, MEDIA_WALLPAPER,
                MEDIA_AI_MEDIA, MEDIA_ANIMATED_GIFS, MEDIA_AUDIO, MEDIA_BACKUP_EXCLUDED_STICKERS,
                MEDIA_BUG_REPORT_ATTACHMENTS, MEDIA_DOCUMENTS, MEDIA_IMAGES, MEDIA_PROFILE_PHOTOS,
                MEDIA_STICKER_PACKS, MEDIA_STICKERS, MEDIA_VIDEO, MEDIA_VIDEO_NOTES, MEDIA_VOICE_NOTES
            )
            for (folderName in mediaFolders) {
                File(mediaRoot, folderName).takeIf { !it.exists() }?.mkdirs()
            }
            Log.d(TAG, "Full WhatsApp-style structure initialized at ${root.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing folders: ${e.message}")
        }
    }

    /**
     * Returns the base ChatApp folder: /storage/emulated/0/Android/media/{package}/ChatApp/
     */
    fun getAppRootFolder(context: Context): File {
        val mediaDir = Environment.getExternalStorageDirectory()
        return File(mediaDir, "Android/media/${context.packageName}/$MAIN_APP_DIR")
    }

    /**
     * Safely retrieves a specific media sub-folder, creating it if it doesn't exist.
     */
    fun getMediaFolder(context: Context, folderName: String): File {
        val mediaRoot = File(getAppRootFolder(context), MEDIA)
        val folder = File(mediaRoot, folderName)
        if (!folder.exists()) folder.mkdirs()
        return folder
    }

    /**
     * Safely retrieves a specific media folder with a date-based subfolder (e.g., YYYYWW).
     */
    fun getMediaFolderWithDate(context: Context, baseFolderName: String): File {
        val baseFolder = getMediaFolder(context, baseFolderName)
        val dateSubFolder = SimpleDateFormat("yyyyww", Locale.getDefault()).format(Date())
        val folder = File(baseFolder, dateSubFolder)
        if (!folder.exists()) folder.mkdirs()
        return folder
    }
}
