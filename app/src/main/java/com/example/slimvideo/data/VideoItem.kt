package com.example.slimvideo.data

import android.net.Uri

data class VideoItem(
    val uri: Uri,
    val displayName: String,
    val size: Long,          // in bytes
    val durationMs: Long,    // in milliseconds
    val width: Int,
    val height: Int,
    val bitrate: Long,       // in bps
    val path: String?
)
