package com.example.slimvideo.viewmodel

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.slimvideo.data.CompressionPreset
import com.example.slimvideo.data.VideoItem
import com.example.slimvideo.data.AppTheme
import com.example.slimvideo.worker.VideoCompressionWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

sealed class CompressionState {
    object Idle : CompressionState()
    object Analyzing : CompressionState()
    object Ready : CompressionState()
    object Compressing : CompressionState()
    object AwaitingOverwriteConfirmation : CompressionState()
    data class AwaitingUserPermission(val intentSender: IntentSender) : CompressionState()
    object Replacing : CompressionState()
    data class Completed(
        val savedPath: String,
        val originalSize: Long,
        val size: Long,
        val width: Int,
        val height: Int,
        val fps: Double,
        val codec: String
    ) : CompressionState()
    data class Failed(val error: String) : CompressionState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val sharedPreferences = application.getSharedPreferences("slim_video_settings", Context.MODE_PRIVATE)

    private val _selectedVideo = MutableStateFlow<VideoItem?>(null)
    val selectedVideo: StateFlow<VideoItem?> = _selectedVideo.asStateFlow()

    private val _selectedPreset = MutableStateFlow<CompressionPreset?>(null)
    val selectedPreset: StateFlow<CompressionPreset?> = _selectedPreset.asStateFlow()

    private val _useH265 = MutableStateFlow(true)
    val useH265: StateFlow<Boolean> = _useH265.asStateFlow()

    private val _compressionProgress = MutableStateFlow(0)
    val compressionProgress: StateFlow<Int> = _compressionProgress.asStateFlow()

    private val _compressionStatusText = MutableStateFlow("正在准备转码任务...")
    val compressionStatusText: StateFlow<String> = _compressionStatusText.asStateFlow()

    private val _compressionState = MutableStateFlow<CompressionState>(CompressionState.Idle)
    val compressionState: StateFlow<CompressionState> = _compressionState.asStateFlow()

    private val _compressedVideoSize = MutableStateFlow(0L)
    val compressedVideoSize: StateFlow<Long> = _compressedVideoSize.asStateFlow()

    private var lastLoggedStep = ""
    private var lastLoggedProgressMilestone = -1
    private var currentWorkId: UUID? = null
    private var tempOutputPath: String? = null

    private val _currentTheme = MutableStateFlow(AppTheme.TECH_BLUE)
    val currentTheme: StateFlow<AppTheme> = _currentTheme.asStateFlow()

    private val _shouldPromptOverwrite = MutableStateFlow(true)
    val shouldPromptOverwrite: StateFlow<Boolean> = _shouldPromptOverwrite.asStateFlow()

    private val _defaultKeepFps = MutableStateFlow(false)
    val defaultKeepFps: StateFlow<Boolean> = _defaultKeepFps.asStateFlow()

    private val _keepOriginalFps = MutableStateFlow(false)
    val keepOriginalFps: StateFlow<Boolean> = _keepOriginalFps.asStateFlow()

    private val _debugLogs = MutableStateFlow<List<String>>(emptyList())
    val debugLogs: StateFlow<List<String>> = _debugLogs.asStateFlow()

    private val _tempFilesSize = MutableStateFlow(0L)
    val tempFilesSize: StateFlow<Long> = _tempFilesSize.asStateFlow()

    init {
        val savedThemeId = sharedPreferences.getString("selected_theme_id", AppTheme.TECH_BLUE.name)
        val matchedTheme = AppTheme.values().firstOrNull { it.name == savedThemeId } ?: AppTheme.TECH_BLUE
        _currentTheme.value = matchedTheme

        val savedOverwritePrompt = sharedPreferences.getBoolean("should_prompt_overwrite", true)
        _shouldPromptOverwrite.value = savedOverwritePrompt

        val savedDefaultKeepFps = sharedPreferences.getBoolean("default_keep_fps", false)
        _defaultKeepFps.value = savedDefaultKeepFps
        _keepOriginalFps.value = savedDefaultKeepFps

        // Restore active task state if it was interrupted/completed in background
        val hasActiveTask = sharedPreferences.getBoolean("active_task_exists", false)
        if (hasActiveTask) {
            val uriStr = sharedPreferences.getString("active_video_uri", null)
            val name = sharedPreferences.getString("active_video_name", null)
            val tempPath = sharedPreferences.getString("active_temp_output_path", null)
            if (uriStr != null && name != null) {
                val videoItem = VideoItem(
                    uri = Uri.parse(uriStr),
                    displayName = name,
                    size = sharedPreferences.getLong("active_video_size", 0L),
                    durationMs = sharedPreferences.getLong("active_video_duration", 0L),
                    width = sharedPreferences.getInt("active_video_width", 0),
                    height = sharedPreferences.getInt("active_video_height", 0),
                    bitrate = sharedPreferences.getLong("active_video_bitrate", 0L),
                    path = sharedPreferences.getString("active_video_path", null),
                    fps = sharedPreferences.getFloat("active_video_fps", 30.0f).toDouble(),
                    codec = sharedPreferences.getString("active_video_codec", "H.264") ?: "H.264",
                    bitDepth = sharedPreferences.getInt("active_video_bitdepth", 8),
                    fileDate = sharedPreferences.getString("active_video_filedate", "") ?: ""
                )
                _selectedVideo.value = videoItem
                tempOutputPath = tempPath
                
                if (tempPath != null) {
                    val file = File(tempPath)
                    if (file.exists()) {
                        _compressedVideoSize.value = file.length()
                        _compressionState.value = CompressionState.AwaitingOverwriteConfirmation
                        logDebug("检测到后台转码任务已成功完成，自动恢复覆盖提示框。")
                    }
                }
            }
        }

        val savedWorkIdStr = sharedPreferences.getString("active_work_id", null)
        if (savedWorkIdStr != null) {
            try {
                val workId = java.util.UUID.fromString(savedWorkIdStr)
                currentWorkId = workId
                observeWorkById(workId)
            } catch (e: Exception) {
                // ignore
            }
        }

        // Clean up historical cache files in background
        viewModelScope.launch(Dispatchers.IO) {
            com.example.slimvideo.utils.FileStorageManager.cleanTempFiles(application)
            calculateTempFilesSize()
        }
    }

    fun logDebug(msg: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
        val formatted = "[$timestamp] $msg"
        Log.d("SlimVideoDebug", formatted)
        val currentList = _debugLogs.value.toMutableList()
        currentList.add(formatted)
        _debugLogs.value = currentList
    }

    fun clearDebugLogs() {
        _debugLogs.value = emptyList()
    }

    fun calculateTempFilesSize() {
        viewModelScope.launch(Dispatchers.IO) {
            var size = 0L
            try {
                val cacheDir = getApplication<Application>().cacheDir
                val tempFiles = cacheDir.listFiles { _, name ->
                    (name.startsWith("temp_compressed_") || name.startsWith("temp_chunk_")) && name.endsWith(".mp4")
                }
                tempFiles?.forEach { file ->
                    if (file.exists()) {
                        size += file.length()
                    }
                }
            } catch (e: java.lang.Exception) {
                Log.e("MainViewModel", "Error calculating cache size", e)
            }
            withContext(Dispatchers.Main) {
                _tempFilesSize.value = size
            }
        }
    }

    fun clearTempFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                com.example.slimvideo.utils.FileStorageManager.cleanTempFiles(context)
            } catch (e: java.lang.Exception) {
                Log.e("MainViewModel", "Error clearing cache", e)
            }
            calculateTempFilesSize()
        }
    }

    fun setTheme(theme: AppTheme) {
        _currentTheme.value = theme
        sharedPreferences.edit().putString("selected_theme_id", theme.name).apply()
    }

    fun setShouldPromptOverwrite(value: Boolean) {
        _shouldPromptOverwrite.value = value
        sharedPreferences.edit().putBoolean("should_prompt_overwrite", value).apply()
    }

    fun setDefaultKeepFps(value: Boolean) {
        _defaultKeepFps.value = value
        sharedPreferences.edit().putBoolean("default_keep_fps", value).apply()
    }

    fun setKeepOriginalFps(value: Boolean) {
        _keepOriginalFps.value = value
    }

    fun setUseH265(value: Boolean) {
        _useH265.value = value
    }

    /**
     * Resolves metadata of selected video URI.
     */
    fun selectVideo(context: Context, uri: Uri) {
        viewModelScope.launch {
            _compressionState.value = CompressionState.Analyzing
            _selectedVideo.value = null
            _selectedPreset.value = null
            _useH265.value = true
            _compressionProgress.value = 0

            clearDebugLogs()
            logDebug("==== 启动视频路径诊断 ====")
            logDebug("手机品牌 (Manufacturer): ${android.os.Build.MANUFACTURER}")
            logDebug("手机型号 (Model): ${android.os.Build.MODEL}")
            logDebug("系统 API 级别 (SDK_INT): ${android.os.Build.VERSION.SDK_INT}")
            
            // Check storage permissions
            val readVideo = androidx.core.content.ContextCompat.checkSelfPermission(
                context, 
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    android.Manifest.permission.READ_MEDIA_VIDEO
                } else {
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                }
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            logDebug("媒体读取权限授权状态: ${if (readVideo) "已授予 (GRANTED)" else "被拒绝 (DENIED)"}")
            logDebug("输入虚拟 URI: $uri")

            // 1. Dump Column Names and values for Diagnostics
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val columnNames = cursor.columnNames
                    logDebug("[字段暴力转储] 底层数据库暴露列数: ${columnNames.size}")
                    if (cursor.moveToFirst()) {
                        for (colName in columnNames) {
                            val idx = cursor.getColumnIndex(colName)
                            val value = try {
                                cursor.getString(idx)
                            } catch (e: Exception) {
                                "binary/error: ${e.message}"
                            }
                            logDebug("  • $colName = $value")
                        }
                    } else {
                        logDebug("[字段暴力转储] Cursor 移动到首行失败，没有数据记录。")
                    }
                }
            } catch (e: Exception) {
                logDebug("[字段暴力转储] 发生错误: ${e.message}")
            }

            val videoItem = com.example.slimvideo.utils.VideoMetadataResolver.resolveVideoItem(context, uri) { logDebug(it) }

            if (videoItem != null) {
                _selectedVideo.value = videoItem
                _keepOriginalFps.value = _defaultKeepFps.value
                // Default to DAILY_HD if eligible, otherwise fallback to the first eligible preset
                if (CompressionPreset.DAILY_HD.isEligible(videoItem.width, videoItem.height, videoItem.bitrate)) {
                    _selectedPreset.value = CompressionPreset.DAILY_HD
                } else {
                    _selectedPreset.value = CompressionPreset.values().firstOrNull {
                        it.isEligible(videoItem.width, videoItem.height, videoItem.bitrate)
                    }
                }
                _compressionState.value = CompressionState.Ready
            } else {
                _compressionState.value = CompressionState.Failed("解析视频元数据失败，该文件可能已损坏或不支持。")
            }
        }
    }

    fun selectPreset(preset: CompressionPreset) {
        val video = _selectedVideo.value
        if (video != null && preset.isEligible(video.width, video.height, video.bitrate)) {
            _selectedPreset.value = preset
        }
    }

    /**
     * Triggers the WorkManager task to compress video in background.
     */
    fun startCompression(context: Context) {
        val video = _selectedVideo.value ?: return
        val preset = _selectedPreset.value ?: return

        _compressionProgress.value = 0
        _compressionStatusText.value = "正在初始化转码..."
        _compressionState.value = CompressionState.Compressing

        lastLoggedStep = ""
        // val originalSizeMb = video.size.toDouble() / (1024 * 1024) // 旧二进制标准
        val originalSizeMb = video.size.toDouble() / (1000 * 1000) // 改用十进制标准 (SI)
        val sizeStr = String.format(java.util.Locale.US, "%.2f", originalSizeMb)
        logDebug("=== 启动硬件加速转码任务 ===")
        logDebug("输入视频: ${video.displayName} (${video.width}x${video.height}, ${video.fps} fps, $sizeStr MB)")
        logDebug("目标配置: 档位: ${preset.name} | H.265编码: ${_useH265.value} | 保持原始帧率: ${_keepOriginalFps.value}")

        val inputData = Data.Builder()
            .putString(VideoCompressionWorker.KEY_VIDEO_URI, video.uri.toString())
            .putString(VideoCompressionWorker.KEY_RESOLVED_PATH, video.path)
            .putString(VideoCompressionWorker.KEY_PRESET_ID, preset.id)
            .putBoolean(VideoCompressionWorker.KEY_USE_H265, _useH265.value)
            .putBoolean(VideoCompressionWorker.KEY_KEEP_ORIGINAL_FPS, _keepOriginalFps.value)
            .build()

        val compressionWork = OneTimeWorkRequestBuilder<VideoCompressionWorker>()
            .setInputData(inputData)
            .build()

        currentWorkId = compressionWork.id
        
        // Save state immediately to handle app kills during transcode
        saveActiveTaskState(video, null)
        sharedPreferences.edit().putString("active_work_id", compressionWork.id.toString()).apply()

        val workManager = WorkManager.getInstance(context)
        workManager.enqueue(compressionWork)

        observeWorkById(compressionWork.id)
    }

    private fun observeWorkById(workId: UUID) {
        val workManager = WorkManager.getInstance(getApplication())
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workId).collect { workInfo ->
                if (workInfo != null) {
                    when (workInfo.state) {
                        WorkInfo.State.RUNNING -> {
                            val progress = workInfo.progress.getInt(VideoCompressionWorker.KEY_PROGRESS, 0)
                            _compressionProgress.value = progress
                            val statusText = workInfo.progress.getString(VideoCompressionWorker.KEY_STATUS_TEXT)
                            if (!statusText.isNullOrEmpty()) {
                                _compressionStatusText.value = statusText
                                if (statusText != lastLoggedStep) {
                                    lastLoggedStep = statusText
                                    logDebug("执行状态变更 ➜ $statusText")
                                }
                            }
                            _compressionState.value = CompressionState.Compressing
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            val path = workInfo.outputData.getString(VideoCompressionWorker.KEY_OUTPUT_PATH)
                            tempOutputPath = path
                            logDebug("=== 转码任务成功完成 ===")
                            if (path != null) {
                                val file = File(path)
                                if (file.exists()) {
                                    _compressedVideoSize.value = file.length()
                                    // val compSizeMb = file.length().toDouble() / (1024 * 1024) // 旧二进制标准
                                    val compSizeMb = file.length().toDouble() / (1000 * 1000) // 改用十进制标准 (SI)
                                    val compSizeStr = String.format(java.util.Locale.US, "%.2f", compSizeMb)
                                    logDebug("输出文件路径: $path ($compSizeStr MB)")
                                }
                                
                                // Update saved state with tempOutputPath
                                _selectedVideo.value?.let { video ->
                                    saveActiveTaskState(video, path)
                                }

                                if (_shouldPromptOverwrite.value) {
                                    _compressionState.value = CompressionState.AwaitingOverwriteConfirmation
                                } else {
                                    _compressionState.value = CompressionState.Replacing
                                    requestDeleteOriginal(getApplication())
                                }
                            } else {
                                logDebug("警告: 转码完成但输出路径为空")
                                _compressionState.value = CompressionState.Failed("转码完成，但未找到临时输出文件。")
                                clearActiveTaskState()
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            val errMsg = workInfo.outputData.getString(VideoCompressionWorker.KEY_ERROR_MSG) ?: "未知硬件加速错误"
                            logDebug("=== 转码任务失败 ===")
                            logDebug("错误信息: $errMsg")
                            _compressionState.value = CompressionState.Failed("视频压缩失败: $errMsg")
                            clearActiveTaskState()
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    /**
     * Request Scoped Storage deletion permission for original video file.
     */
    private fun requestDeleteOriginal(context: Context) {
        val video = _selectedVideo.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Reconstruct picker URI to standard MediaStore URI
                var targetUri = video.uri
                
                // Priority 1: If we have a resolved physical path, reverse query its ID in MediaStore
                if (video.path != null) {
                    try {
                        context.contentResolver.query(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            arrayOf(MediaStore.Video.Media._ID),
                            "${MediaStore.Video.Media.DATA} = ?",
                            arrayOf(video.path),
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                                targetUri = android.content.ContentUris.withAppendedId(
                                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                    id
                                )
                                Log.i("MainViewModel", "Reverse resolved targetUri from path successfully: $targetUri")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("MainViewModel", "Reverse query targetUri from path failed: ${e.message}")
                    }
                }

                // Priority 2: Fallback to picker ID mapping if targetUri is still the original and is picker URI
                if (targetUri == video.uri && (video.uri.authority?.contains("photopicker") == true || video.uri.path?.contains("picker") == true)) {
                    try {
                        val id = android.content.ContentUris.parseId(video.uri)
                        targetUri = android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    } catch (e: Exception) {
                        val lastSegment = video.uri.lastPathSegment
                        val id = lastSegment?.toLongOrNull()
                        if (id != null) {
                            targetUri = android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                        }
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+ delete request API
                    val uris = listOf(targetUri)
                    val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, uris)
                    _compressionState.value = CompressionState.AwaitingUserPermission(pendingIntent.intentSender)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10 delete request API
                    val uris = listOf(targetUri)
                    val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, uris)
                    _compressionState.value = CompressionState.AwaitingUserPermission(pendingIntent.intentSender)
                } else {
                    // Legacy Android version direct file deletion
                    val file = video.path?.let { File(it) }
                    if (file != null && file.exists()) {
                        val deleted = file.delete()
                        if (deleted) {
                            onDeleteConfirmed(context)
                        } else {
                            _compressionState.value = CompressionState.Failed("无法删除原高清视频文件。")
                        }
                    } else {
                        onDeleteConfirmed(context)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error creating delete request", e)
                _compressionState.value = CompressionState.Failed("申请删除权限失败: ${e.message}")
            }
        }
    }

    fun onDeleteCancelled() {
        _compressionState.value = CompressionState.Failed("用户拒绝了原视频删除申请，原片已安全保留，压缩文件已暂存。")
    }

    fun confirmOverwrite(context: Context) {
        requestDeleteOriginal(context)
    }

    /**
     * Invoked after the user successfully confirms video deletion inside system dialog.
     */
    fun onDeleteConfirmed(context: Context) {
        val video = _selectedVideo.value ?: return
        val tempPath = tempOutputPath ?: return

        _compressionState.value = CompressionState.Replacing

        viewModelScope.launch(Dispatchers.IO) {
            val result = com.example.slimvideo.utils.FileStorageManager.replaceOriginalVideo(context, video, tempPath) { logDebug(it) }
            
            withContext(Dispatchers.Main) {
                when (result) {
                    is com.example.slimvideo.utils.ReplaceResult.Success -> {
                        _compressionState.value = CompressionState.Completed(
                            savedPath = result.savedPath,
                            originalSize = video.size,
                            size = result.size,
                            width = result.width,
                            height = result.height,
                            fps = result.fps,
                            codec = result.codec
                        )
                        clearActiveTaskState()
                    }
                    is com.example.slimvideo.utils.ReplaceResult.Failure -> {
                        _compressionState.value = CompressionState.Failed(result.error)
                    }
                }
            }
        }
    }

    private fun saveActiveTaskState(video: VideoItem?, tempPath: String?) {
        val editor = sharedPreferences.edit()
        if (video != null) {
            editor.putString("active_video_uri", video.uri.toString())
            editor.putString("active_video_name", video.displayName)
            editor.putLong("active_video_size", video.size)
            editor.putLong("active_video_duration", video.durationMs)
            editor.putInt("active_video_width", video.width)
            editor.putInt("active_video_height", video.height)
            editor.putLong("active_video_bitrate", video.bitrate)
            editor.putString("active_video_path", video.path)
            editor.putFloat("active_video_fps", video.fps.toFloat())
            editor.putString("active_video_codec", video.codec)
            editor.putInt("active_video_bitdepth", video.bitDepth)
            editor.putString("active_video_filedate", video.fileDate)
            editor.putString("active_temp_output_path", tempPath)
            editor.putBoolean("active_task_exists", true)
        } else {
            editor.remove("active_video_uri")
            editor.remove("active_video_name")
            editor.remove("active_video_size")
            editor.remove("active_video_duration")
            editor.remove("active_video_width")
            editor.remove("active_video_height")
            editor.remove("active_video_bitrate")
            editor.remove("active_video_path")
            editor.remove("active_video_fps")
            editor.remove("active_video_codec")
            editor.remove("active_temp_output_path")
            editor.putBoolean("active_task_exists", false)
        }
        editor.apply()
    }

    private fun clearActiveTaskState() {
        saveActiveTaskState(null, null)
        sharedPreferences.edit().remove("active_work_id").apply()
        currentWorkId = null
    }

    fun cancelCompression() {
        val workId = currentWorkId
        if (workId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val workManager = WorkManager.getInstance(getApplication())
                    workManager.cancelWorkById(workId)
                    Log.i("MainViewModel", "Cancelled WorkManager job: $workId")
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error cancelling work", e)
                }
            }
        }
        resetState()
    }

    fun resetState() {
        _compressionState.value = CompressionState.Idle
        _selectedVideo.value = null
        _selectedPreset.value = null
        _useH265.value = true
        _compressionProgress.value = 0
        tempOutputPath?.let { path ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val file = File(path)
                    if (file.exists()) {
                        file.delete()
                        Log.i("MainViewModel", "Cleaned up temporary file: $path")
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to clean up temp file: ${e.message}")
                }
            }
            tempOutputPath = null
        }
        clearActiveTaskState()
    }
}
