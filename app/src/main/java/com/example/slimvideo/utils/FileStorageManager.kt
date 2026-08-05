package com.example.slimvideo.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.example.slimvideo.data.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

sealed class ReplaceResult {
    data class Success(
        val savedPath: String,
        val size: Long,
        val width: Int,
        val height: Int,
        val fps: Double,
        val codec: String
    ) : ReplaceResult()
    data class Failure(val error: String) : ReplaceResult()
}

object FileStorageManager {

    suspend fun replaceOriginalVideo(
        context: Context,
        originalVideo: VideoItem,
        tempPath: String,
        log: (String) -> Unit
    ): ReplaceResult = withContext(Dispatchers.IO) {
        val tempFile = File(tempPath)
        if (!tempFile.exists()) {
            return@withContext ReplaceResult.Failure("找不到转码完成的压缩临时文件。")
        }

        var success = false
        var errorMsg = "未知错误"

        val originalPath = originalVideo.path
        val finalSize = tempFile.length()
        var finalWidth = 0
        var finalHeight = 0
        var finalFps = 30.0
        var finalCodec = "未知"

        val ret = MediaMetadataRetriever()
        try {
            ret.setDataSource(tempFile.absolutePath)
            finalWidth = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            finalHeight = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val frameCount = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
                val durationMs = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (frameCount > 0 && durationMs > 0) {
                    finalFps = frameCount.toDouble() / (durationMs / 1000.0)
                }
            }
        } catch (e: Exception) {
            Log.e("FileStorageManager", "Pre-retrieving output details failed", e)
        } finally {
            try { ret.release() } catch (e: Exception) {}
        }

        val ext = android.media.MediaExtractor()
        try {
            ext.setDataSource(tempFile.absolutePath)
            for (i in 0 until ext.trackCount) {
                val format = ext.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    finalCodec = when {
                        mime.contains("hevc") || mime.contains("h265") -> "H.265"
                        mime.contains("avc") || mime.contains("h264") -> "H.264"
                        mime.contains("vp9") -> "VP9"
                        else -> mime.substringAfter("video/").uppercase()
                    }
                    if (format.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) {
                        finalFps = try {
                            format.getFloat(android.media.MediaFormat.KEY_FRAME_RATE).toDouble()
                        } catch (e: Exception) {
                            format.getInteger(android.media.MediaFormat.KEY_FRAME_RATE).toDouble()
                        }
                    }
                    break
                }
            }
        } catch (e: Exception) {
            Log.e("FileStorageManager", "Pre-extracting output details failed", e)
        } finally {
            try { ext.release() } catch (e: Exception) {}
        }

        // Try 1: Direct File Copy (works on legacy paths / direct storage access)
        if (originalPath != null) {
            try {
                val originalFile = File(originalPath)
                FileInputStream(tempFile).use { input ->
                    FileOutputStream(originalFile).use { output ->
                        input.channel.transferTo(0, input.channel.size(), output.channel)
                    }
                }
                success = true
                tempFile.delete()
                log("文件已成功通过直接通道复制替换。")
            } catch (e: Exception) {
                errorMsg = "物理路径直写失败: ${e.localizedMessage ?: e.message}"
                Log.w("FileStorageManager", "Direct copy failed, falling back to MediaStore insert: ${e.message}")
            }
        }

        // Try 2: MediaStore insert fallback
        if (!success) {
            try {
                val resolver = context.contentResolver
                val finalDisplayName = if (originalVideo.displayName.endsWith(".mp4", ignoreCase = true)) {
                    originalVideo.displayName
                } else {
                    "${originalVideo.displayName}.mp4"
                }
                val contentValues = android.content.ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, finalDisplayName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && originalPath != null) {
                        val relativePath = try {
                            val upperPath = originalPath.uppercase()
                            val folders = listOf("DCIM", "MOVIES", "PICTURES", "DOWNLOAD", "DOCUMENTS")
                            var parsedPath = ""
                            for (folder in folders) {
                                val index = upperPath.indexOf("/$folder/")
                                if (index != -1) {
                                    val subPath = originalPath.substring(index + 1)
                                    val dir = subPath.substringBeforeLast("/")
                                    parsedPath = if (dir.endsWith("/")) dir else "$dir/"
                                    break
                                }
                            }
                            if (parsedPath.isNotEmpty()) parsedPath else "Movies/"
                        } catch (e: Exception) {
                            "Movies/"
                        }
                        put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                    }
                }

                val newUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (newUri != null) {
                    resolver.openOutputStream(newUri)?.use { output ->
                        FileInputStream(tempFile).use { input ->
                            input.copyTo(output)
                        }
                    }
                    tempFile.delete()
                    success = true
                    log("文件已成功通过 MediaStore 插入保存。")
                } else {
                    errorMsg = "MediaStore 插入返回 Uri 为空"
                }
            } catch (e: Exception) {
                errorMsg = "MediaStore 写入捕获异常: ${e.localizedMessage ?: e.message}"
                Log.e("FileStorageManager", "MediaStore write failed", e)
            }
        }

        if (success) {
            val pathsToScan = if (originalPath != null) arrayOf(originalPath) else arrayOf()
            MediaScannerConnection.scanFile(context, pathsToScan, arrayOf("video/mp4")) { path, uri ->
                Log.i("FileStorageManager", "MediaScanner refresh completed: $path -> $uri")
            }
            ReplaceResult.Success(
                savedPath = originalPath ?: originalVideo.displayName,
                size = finalSize,
                width = finalWidth,
                height = finalHeight,
                fps = finalFps,
                codec = finalCodec
            )
        } else {
            // Clean up tempFile on failure
            if (tempFile.exists()) {
                try { tempFile.delete() } catch (e: Exception) {}
            }
            ReplaceResult.Failure("写入并覆盖压缩视频失败 ($errorMsg)")
        }
    }

    fun cleanTempFiles(context: Context) {
        try {
            val cacheDir = context.cacheDir
            val tempFiles = cacheDir.listFiles { _, name ->
                (name.startsWith("temp_compressed_") || name.startsWith("temp_chunk_")) && name.endsWith(".mp4")
            }
            tempFiles?.forEach { file ->
                if (file.exists()) {
                    file.delete()
                }
            }
            Log.i("FileStorageManager", "Temp file cleanup completed.")
        } catch (e: Exception) {
            Log.e("FileStorageManager", "Error cleaning up cache directory: ${e.message}")
        }
    }
}
