package com.example.slimvideo.worker

import android.app.Notification
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
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
        const val KEY_PRESET_ID = "INPUT_PRESET_ID"
        const val KEY_USE_H265 = "INPUT_USE_H265"
        const val KEY_KEEP_ORIGINAL_FPS = "INPUT_KEEP_ORIGINAL_FPS"
        const val KEY_OUTPUT_PATH = "OUTPUT_PATH"
        const val KEY_ERROR_MSG = "ERROR_MSG"
        const val KEY_PROGRESS = "PROGRESS"
        const val NOTIFICATION_ID = 1001
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val videoUriStr = inputData.getString(KEY_VIDEO_URI) ?: return@withContext Result.failure(
            workDataOf(KEY_ERROR_MSG to "Missing video URI input")
        )
        val presetId = inputData.getString(KEY_PRESET_ID) ?: return@withContext Result.failure(
            workDataOf(KEY_ERROR_MSG to "Missing preset ID input")
        )
        val useH265 = inputData.getBoolean(KEY_USE_H265, true)
        val keepOriginalFps = inputData.getBoolean(KEY_KEEP_ORIGINAL_FPS, false)

        val uri = Uri.parse(videoUriStr)
        val preset = try {
            CompressionPreset.valueOf(presetId)
        } catch (e: Exception) {
            return@withContext Result.failure(workDataOf(KEY_ERROR_MSG to "Invalid preset ID"))
        }

        // Detect video display dimensions and orientation to prevent transcode aspect ratio distortion
        var originalDisplayWidth = 0
        var originalDisplayHeight = 0
        var originalFps = 30
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
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

            val fpsStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            if (!fpsStr.isNullOrEmpty()) {
                originalFps = fpsStr.toFloat().toInt()
            } else {
                val frameCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
                } else 0
                val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (frameCount > 0 && durationMs > 0) {
                    originalFps = (frameCount.toDouble() / (durationMs / 1000.0)).toInt()
                }
            }
            if (originalFps <= 0) originalFps = 30

            android.util.Log.d("VideoCompressionWorker", "Metadata parsed - display width: $originalDisplayWidth, display height: $originalDisplayHeight, rotation: $rotation, originalFps: $originalFps")
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

        // Setup temporary output file in app cache directory
        val tempOutputFile = File(context.cacheDir, "temp_compressed_${System.currentTimeMillis()}.mp4")
        if (tempOutputFile.exists()) {
            tempOutputFile.delete()
        }

        var attemptEffects = true
        var outputResult: Result? = null

        // Two-attempt safe fallback logic (for virtualized emulator GL & HEVC buffer failures)
        for (retry in 1..2) {
            val deferred = CompletableDeferred<Result>()
            var transformer: Transformer? = null

            try {
                // Build and start Transformer on the Main Thread (Media3 requires UI Looper thread)
                withContext(Dispatchers.Main) {
                    val currentAttemptUseH265 = useH265 && attemptEffects
                    val targetBitrate = if (currentAttemptUseH265) {
                        preset.targetBitrateBps.toInt()
                    } else {
                        (preset.targetBitrateBps * 1.8).toInt()
                    }

                    // Build VideoEncoderSettings with target bitrate
                    val videoEncoderSettings = VideoEncoderSettings.Builder()
                        .setBitrate(targetBitrate)
                        .build()

                    // Setup Encoder Factory
                    val encoderFactory = DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(videoEncoderSettings)
                        .setEnableFallback(true) // Automatically fallback to standard profile / AVC if HEVC fails
                        .build()

                    val editedMediaItem = if (attemptEffects) {
                        // Attempt 1: Apply scaling presentation (uses OpenGL) and HEVC/AVC
                        // Dynamically calculate final width and height to fit inside preset's max boundary
                        // while keeping the original aspect ratio exactly intact and avoiding upscaling.
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

                        // Enforce codec-compliant even dimensions (divisible by 2)
                        if (finalWidth % 2 != 0) finalWidth += 1
                        if (finalHeight % 2 != 0) finalHeight += 1

                        val presentation = Presentation.createForWidthAndHeight(
                            finalWidth,
                            finalHeight,
                            Presentation.LAYOUT_SCALE_TO_FIT
                        )
                        val videoEffects = mutableListOf<androidx.media3.common.Effect>(presentation)
                        if (!keepOriginalFps) {
                            videoEffects.add(FrameDropEffect.createDefaultFrameDropEffect(preset.targetFps.toFloat()))
                        }
                        val effects = Effects(
                            emptyList(), // No audio effects
                            videoEffects
                        )
                        EditedMediaItem.Builder(MediaItem.fromUri(uri))
                            .setEffects(effects)
                            .build()
                    } else {
                        // Attempt 2: Safe fallback, skip OpenGL frame processing completely
                        EditedMediaItem.Builder(MediaItem.fromUri(uri)).build()
                    }

                    // Fallback to standard H.264/AVC (which is 100% stable in emulators and low-end devices)
                    val mimeType = if (currentAttemptUseH265) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264

                    // Build Transformer
                    val t = Transformer.Builder(context)
                        .setVideoMimeType(mimeType)
                        .setEncoderFactory(encoderFactory)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                deferred.complete(Result.success(workDataOf(KEY_OUTPUT_PATH to tempOutputFile.absolutePath)))
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException
                            ) {
                                deferred.complete(Result.failure(workDataOf(KEY_ERROR_MSG to exportException.message)))
                            }
                        })
                        .build()

                    // Start hardware-accelerated encoding
                    t.start(editedMediaItem, tempOutputFile.absolutePath)
                    transformer = t
                }

                // Dynamic progress polling, reading from Main thread
                val progressHolder = ProgressHolder()
                while (deferred.isActive) {
                    delay(500)
                    val activeTransformer = transformer ?: continue
                    val progressState = withContext(Dispatchers.Main) {
                        activeTransformer.getProgress(progressHolder)
                    }
                    if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                        val progress = progressHolder.progress
                        setProgress(workDataOf(KEY_PROGRESS to progress))
                        updateNotification(progress, "正在压缩视频中... ${progress}%")
                    }
                }

                val finalResult = deferred.await()
                if (finalResult is Result.Success) {
                    outputResult = finalResult
                    break // Successfully finished!
                } else {
                    val errMsg = finalResult.outputData.getString(KEY_ERROR_MSG) ?: "Transcode failed"
                    throw Exception(errMsg)
                }
            } catch (e: Exception) {
                android.util.Log.w("VideoCompressionWorker", "Attempt $retry failed: ${e.message}")
                if (tempOutputFile.exists()) {
                    tempOutputFile.delete()
                }

                if (retry == 1) {
                    attemptEffects = false
                    android.util.Log.i("VideoCompressionWorker", "Retrying video compression using H.264 (AVC) and no OpenGL...")
                } else {
                    outputResult = Result.failure(workDataOf(KEY_ERROR_MSG to (e.message ?: "Unknown compression error")))
                }
            }
        }

        return@withContext outputResult ?: Result.failure(workDataOf(KEY_ERROR_MSG to "Unknown build error"))
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

    private fun updateNotification(progress: Int, content: String) {
        val notification = createForegroundInfo(progress, content).notification
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
