package com.example.slimvideo.worker

import android.app.Notification
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.FrameDropEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.slimvideo.SlimVideoApp
import com.example.slimvideo.data.CompressionPreset
import com.example.slimvideo.utils.Mp4Merger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@UnstableApi
class VideoCompressionWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

    companion object {
        const val KEY_VIDEO_URI = "INPUT_VIDEO_URI"
        const val KEY_RESOLVED_PATH = "INPUT_RESOLVED_PATH"
        const val KEY_PRESET_ID = "INPUT_PRESET_ID"
        const val KEY_USE_H265 = "INPUT_USE_H265"
        const val KEY_KEEP_ORIGINAL_FPS = "INPUT_KEEP_ORIGINAL_FPS"
        const val KEY_OUTPUT_PATH = "OUTPUT_PATH"
        const val KEY_ERROR_MSG = "ERROR_MSG"
        const val KEY_PROGRESS = "PROGRESS"
        const val KEY_STATUS_TEXT = "STATUS_TEXT"
        const val NOTIFICATION_ID = 1001
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "SlimVideo:TranscodeWakeLock")
        try {
            wakeLock.acquire(3 * 60 * 60 * 1000L) // 3 hours safety timeout
        } catch (e: Exception) {
            android.util.Log.w("VideoCompressionWorker", "Failed to acquire wake lock: ${e.message}")
        }

        val tempOutputFile = File(context.cacheDir, "temp_compressed_${System.currentTimeMillis()}.mp4")
        val chunkFiles = mutableListOf<File>()
        var isCompletedSuccessfully = false

        try {
            val videoUriStr = inputData.getString(KEY_VIDEO_URI) ?: return@withContext Result.failure(
            workDataOf(KEY_ERROR_MSG to "Missing video URI input")
        )
        val resolvedPath = inputData.getString(KEY_RESOLVED_PATH)
        val presetId = inputData.getString(KEY_PRESET_ID) ?: return@withContext Result.failure(
            workDataOf(KEY_ERROR_MSG to "Missing preset ID input")
        )
        val useH265 = inputData.getBoolean(KEY_USE_H265, true)
        val keepOriginalFps = inputData.getBoolean(KEY_KEEP_ORIGINAL_FPS, false)

        val uri = Uri.parse(videoUriStr)
        val sourceUri = getBestReadableUri(context, uri, resolvedPath)
        val preset = try {
            CompressionPreset.valueOf(presetId)
        } catch (e: Exception) {
            return@withContext Result.failure(workDataOf(KEY_ERROR_MSG to "Invalid preset ID"))
        }

        // 1. Detect video display dimensions, orientation and duration
        var originalDisplayWidth = 0
        var originalDisplayHeight = 0
        var originalFps = 30
        var originalDurationMs = 0L
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, sourceUri)
            val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            
            if (rotation == 90 || rotation == 270) {
                originalDisplayWidth = height
                originalDisplayHeight = width
            } else {
                originalDisplayWidth = width
                originalDisplayHeight = height
            }

            originalDurationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

            val fpsStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            if (!fpsStr.isNullOrEmpty()) {
                originalFps = fpsStr.toFloat().toInt()
            } else {
                val frameCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
                } else 0
                if (frameCount > 0 && originalDurationMs > 0) {
                    originalFps = (frameCount.toDouble() / (originalDurationMs / 1000.0)).toInt()
                }
            }
            if (originalFps <= 0) originalFps = 30

            android.util.Log.d("VideoCompressionWorker", "Metadata parsed - display width: $originalDisplayWidth, display height: $originalDisplayHeight, rotation: $rotation, originalFps: $originalFps, durationMs: $originalDurationMs")
        } catch (e: Exception) {
            android.util.Log.e("VideoCompressionWorker", "Failed to retrieve video metadata", e)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // ignore
            }
        }

        // Set as foreground service immediately to prevent system killing
        setForeground(createForegroundInfo(0, "正在准备压缩任务..."))

        if (tempOutputFile.exists()) {
            tempOutputFile.delete()
        }

        // 2. Define video segments (dynamically calculated based on system total RAM to prevent GPU leaks on low-end devices)
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalRamGb = memoryInfo.totalMem.toDouble() / (1024 * 1024 * 1024)

        val segmentDurationMs = when {
            totalRamGb >= 11.0 -> 30 * 60 * 1000L  // 12GB RAM and above (flagships): 30 minutes
            totalRamGb >= 7.0 -> 15 * 60 * 1000L   // 8GB RAM (mid-high range): 15 minutes
            else -> 5 * 60 * 1000L                 // Under 8GB RAM (low-end): 5 minutes
        }
        android.util.Log.i("VideoCompressionWorker", "System RAM: ${String.format(java.util.Locale.US, "%.2f", totalRamGb)} GB. Decided segment duration: ${segmentDurationMs / (60 * 1000)} minutes.")
        val segments = mutableListOf<Pair<Long, Long>>()
        val isClipped = originalDurationMs > segmentDurationMs
        if (isClipped) {
            var currentStart = 0L
            while (currentStart < originalDurationMs) {
                val currentEnd = minOf(currentStart + segmentDurationMs, originalDurationMs)
                segments.add(currentStart to currentEnd)
                currentStart = currentEnd
            }
        } else {
            segments.add(0L to originalDurationMs)
        }

        val numSegments = segments.size
        val chunkStartTimes = mutableListOf<Long>()

        // 3. Transcode segments sequentially
        for (idx in 0 until numSegments) {
            val startMs = segments[idx].first
            val endMs = segments[idx].second
            val chunkOutputFile = File(context.cacheDir, "temp_chunk_${idx}_${System.currentTimeMillis()}.mp4")
            
            var attemptEffects = true
            var segmentSuccess = false
            var segmentErrorMsg = ""
            val overallBaseProgress = (idx * 100) / numSegments

            for (retry in 1..2) {
                val deferred = CompletableDeferred<Result>()
                var transformer: Transformer? = null
                val currentAttemptUseH265 = useH265

                try {
                    val targetMax = maxOf(preset.targetWidth, preset.targetHeight).toDouble()
                    val originalMax = maxOf(originalDisplayWidth, originalDisplayHeight).toDouble()
                    val scale = if (originalMax > targetMax && originalMax > 0) {
                        targetMax / originalMax
                    } else {
                        1.0
                    }

                    var finalWidth = if (originalDisplayWidth > 0) {
                        Math.round(originalDisplayWidth * scale).toInt()
                    } else {
                        preset.targetWidth
                    }
                    var finalHeight = if (originalDisplayHeight > 0) {
                        Math.round(originalDisplayHeight * scale).toInt()
                    } else {
                        preset.targetHeight
                    }

                    if (finalWidth % 2 != 0) finalWidth += 1
                    if (finalHeight % 2 != 0) finalHeight += 1

                    val needScale = scale < 1.0
                    val needFrameDrop = !keepOriginalFps && originalFps > preset.targetFps
                    val useVideoEffects = attemptEffects && (needScale || needFrameDrop)

                    val codecName = if (currentAttemptUseH265) "H.265" else "H.264"
                    val segmentInfoStr = if (numSegments > 1) " (分段 ${idx + 1}/$numSegments)" else ""
                    
                    updateStatus(overallBaseProgress, "[1/4] 正在解析视频源并检查音视频轨道参数...$segmentInfoStr")

                    withContext(Dispatchers.Main) {
                        val targetBitrate = if (currentAttemptUseH265) {
                            preset.targetBitrateBps.toInt()
                        } else {
                            (preset.targetBitrateBps * 1.8).toInt()
                        }

                        val videoEncoderSettings = VideoEncoderSettings.Builder()
                            .setBitrate(targetBitrate)
                            .build()

                        val encoderFactory = DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(videoEncoderSettings)
                            .setEnableFallback(true)
                            .build()

                        val mediaItemBuilder = MediaItem.Builder().setUri(sourceUri)
                        if (isClipped) {
                            mediaItemBuilder.setClippingConfiguration(
                                MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(startMs)
                                    .setEndPositionMs(endMs)
                                    .build()
                            )
                        }

                        val editedMediaItem = if (useVideoEffects) {
                            val presentation = Presentation.createForWidthAndHeight(
                                finalWidth,
                                finalHeight,
                                Presentation.LAYOUT_SCALE_TO_FIT
                            )
                            val videoEffects = mutableListOf<androidx.media3.common.Effect>(presentation)
                            if (needFrameDrop) {
                                videoEffects.add(FrameDropEffect.createDefaultFrameDropEffect(preset.targetFps.toFloat()))
                            }
                            val effects = Effects(emptyList(), videoEffects)
                            EditedMediaItem.Builder(mediaItemBuilder.build())
                                .setEffects(effects)
                                .build()
                        } else {
                            EditedMediaItem.Builder(mediaItemBuilder.build()).build()
                        }

                        val mimeType = if (currentAttemptUseH265) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264

                        val t = Transformer.Builder(context)
                            .setVideoMimeType(mimeType)
                            .setAudioMimeType(MimeTypes.AUDIO_AAC)
                            .setEncoderFactory(encoderFactory)
                            .setMaxDelayBetweenMuxerSamplesMs(60_000)
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    deferred.complete(Result.success(workDataOf(KEY_OUTPUT_PATH to chunkOutputFile.absolutePath)))
                                }

                                override fun onError(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                    exportException: ExportException
                                ) {
                                    val detailedError = getDetailedErrorLog(exportException)
                                    deferred.complete(Result.failure(workDataOf(KEY_ERROR_MSG to detailedError)))
                                }
                            })
                            .build()

                        t.start(editedMediaItem, chunkOutputFile.absolutePath)
                        transformer = t
                    }

                    val progressHolder = ProgressHolder()
                    while (deferred.isActive) {
                        delay(1000)
                        val activeTransformer = transformer ?: continue
                        val progressState = withContext(Dispatchers.Main) {
                            activeTransformer.getProgress(progressHolder)
                        }
                        if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                            val chunkProgress = progressHolder.progress
                            val overallProgress = ((idx * 100) + chunkProgress) / numSegments

                            val stepStr = when {
                                chunkProgress == 0 -> "[1/4] 正在解析视频源并提取音视频特征参数...$segmentInfoStr"
                                chunkProgress in 1..3 -> "[2/4] 正在初始化 $codecName 硬件编码器与 AAC 音频重构轨道...$segmentInfoStr"
                                chunkProgress in 4..15 -> "[3/4] 正在建立 GOP 关键帧并校准物理错乱的音频时间戳...$segmentInfoStr"
                                chunkProgress in 16..50 -> "[3/4] 正在进行 $codecName 视频压缩与 AAC 音频重构对齐...$segmentInfoStr"
                                chunkProgress in 51..85 -> "[3/4] 正在将对齐的音视频流进行多路复用混流写入...$segmentInfoStr"
                                chunkProgress in 86..97 -> "[3/4] 正在刷新硬件编解码缓冲区并校对音画同步...$segmentInfoStr"
                                else -> "[4/4] 正在封包并写入 MP4 文件头索引 (Moov Box)...$segmentInfoStr"
                            }
                            updateStatus(overallProgress, stepStr)
                        }
                    }

                    val finalResult = deferred.await()
                    if (finalResult is Result.Success) {
                        segmentSuccess = true
                        chunkFiles.add(chunkOutputFile)
                        chunkStartTimes.add(startMs)
                        break
                    } else {
                        val errMsg = finalResult.outputData.getString(KEY_ERROR_MSG) ?: "Segment transcode failed"
                        throw Exception(errMsg)
                    }

                } catch (e: Exception) {
                    android.util.Log.w("VideoCompressionWorker", "Segment $idx attempt $retry failed: ${e.message}")
                    if (chunkOutputFile.exists()) {
                        chunkOutputFile.delete()
                    }
                    segmentErrorMsg = e.message ?: "Unknown segment error"

                    if (retry == 1) {
                        attemptEffects = false
                        val codecName = if (useH265) "H.265" else "H.264"
                        val retryMsg = "分段 ${idx + 1} 第一阶段失败 (${e.message})，正在尝试不使用 OpenGL 的 $codecName 安全转码路径..."
                        android.util.Log.i("VideoCompressionWorker", retryMsg)
                        updateStatus(overallBaseProgress, retryMsg)
                    }
                }
            }

            if (!segmentSuccess) {
                for (file in chunkFiles) {
                    if (file.exists()) file.delete()
                }
                return@withContext Result.failure(workDataOf(KEY_ERROR_MSG to "分段 ${idx + 1} 压缩失败: $segmentErrorMsg"))
            }
        }

        // 4. Merge all segments
        updateStatus(99, "正在进行音视频流无损拼接混流写入...")
        try {
            Mp4Merger.merge(chunkFiles, chunkStartTimes, tempOutputFile)
            android.util.Log.i("VideoCompressionWorker", "All segments merged successfully.")
        } catch (e: Exception) {
            android.util.Log.e("VideoCompressionWorker", "Failed to merge segments", e)
            for (file in chunkFiles) {
                if (file.exists()) file.delete()
            }
            return@withContext Result.failure(workDataOf(KEY_ERROR_MSG to "合并视频分段失败: ${e.message}"))
        }

        // 5. Clean up temporary chunks
        for (file in chunkFiles) {
            if (file.exists()) {
                file.delete()
            }
        }

        updateStatus(100, "压缩任务成功完成")
        isCompletedSuccessfully = true
        return@withContext Result.success(workDataOf(KEY_OUTPUT_PATH to tempOutputFile.absolutePath))
        } finally {
            if (!isCompletedSuccessfully) {
                try {
                    if (tempOutputFile.exists()) {
                        tempOutputFile.delete()
                    }
                    for (file in chunkFiles) {
                        if (file.exists()) {
                            file.delete()
                        }
                    }
                    android.util.Log.i("VideoCompressionWorker", "Cleaned up temporary transcode files due to failure or cancellation.")
                } catch (e: Exception) {
                    // ignore
                }
            }
            try {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun createForegroundInfo(progress: Int, content: String): ForegroundInfo {
        val title = "胶卷瘦身后台加速中"
        
        val builder = NotificationCompat.Builder(context, SlimVideoApp.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        // API 34+ requires service type decoration
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, builder.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } else {
            ForegroundInfo(NOTIFICATION_ID, builder.build())
        }
    }

    private suspend fun updateStatus(progress: Int, statusText: String) {
        setProgress(workDataOf(
            KEY_PROGRESS to progress,
            KEY_STATUS_TEXT to statusText
        ))
        try {
            setForeground(createForegroundInfo(progress, "$statusText $progress%"))
        } catch (e: Exception) {
            // Fallback to direct NotificationManager update if setForeground throws (e.g. if worker is stopping)
            val notification = createForegroundInfo(progress, "$statusText $progress%").notification
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun getBestReadableUri(context: Context, pickerUri: Uri, resolvedPath: String?): Uri {
        // 1. Try standard MediaStore URI (reconstructed from Picker ID)
        try {
            val mediaId = try {
                android.content.ContentUris.parseId(pickerUri)
            } catch (e: Exception) {
                pickerUri.lastPathSegment?.toLongOrNull()
            }
            if (mediaId != null) {
                val standardUri = android.content.ContentUris.withAppendedId(
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    mediaId
                )
                if (isUriReadable(context, standardUri)) {
                    android.util.Log.d("VideoCompressionWorker", "Best URI resolved to standard MediaStore URI: $standardUri")
                    return standardUri
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("VideoCompressionWorker", "Failed to check standard MediaStore URI: ${e.message}")
        }

        // 2. Try physical file path
        if (!resolvedPath.isNullOrEmpty()) {
            try {
                val file = File(resolvedPath)
                if (file.exists() && file.canRead()) {
                    val fileUri = Uri.fromFile(file)
                    android.util.Log.d("VideoCompressionWorker", "Best URI resolved to physical file URI: $fileUri")
                    return fileUri
                }
            } catch (e: Exception) {
                android.util.Log.w("VideoCompressionWorker", "Failed to check physical file path: ${e.message}")
            }
        }

        // 3. Fallback to original Picker URI
        android.util.Log.d("VideoCompressionWorker", "Best URI fallback to original Picker URI: $pickerUri")
        return pickerUri
    }

    private fun getDetailedErrorLog(e: ExportException): String {
        val sb = StringBuilder()
        sb.append("Media3 错误代码: ").append(e.errorCode)
        try {
            val nameField = e.javaClass.getField("errorCodeName")
            val name = nameField.get(e) as? String
            if (name != null) {
                sb.append(" (").append(name).append(")")
            }
        } catch (ex: Exception) {
            // ignore
        }
        sb.append("\n错误详情: ").append(e.message)
        
        var cause = e.cause
        while (cause != null) {
            sb.append("\n[底层原因] ").append(cause.javaClass.name).append(": ").append(cause.message)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && cause is android.media.MediaCodec.CodecException) {
                sb.append(" (MediaCodec 详情: isTransient=").append(cause.isTransient)
                  .append(", isRecoverable=").append(cause.isRecoverable)
                  .append(", errorCode=").append(cause.errorCode).append(")")
            }
            cause = cause.cause
        }
        return sb.toString()
    }

    private fun isUriReadable(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }
}
