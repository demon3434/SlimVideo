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
    val path: String?,
    val fps: Double,         // frame rate, e.g. 60.0
    val codec: String,       // e.g. "H.264" or "H.265"
    val bitDepth: Int,       // bit depth, e.g. 8 or 10
    val fileDate: String     // formatted local file date, e.g. "2026-06-01"
)
