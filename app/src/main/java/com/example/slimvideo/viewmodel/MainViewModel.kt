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
    data class Completed(val savedPath: String) : CompressionState()
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

    private val _compressionState = MutableStateFlow<CompressionState>(CompressionState.Idle)
    val compressionState: StateFlow<CompressionState> = _compressionState.asStateFlow()

    private val _compressedVideoSize = MutableStateFlow(0L)
    val compressedVideoSize: StateFlow<Long> = _compressedVideoSize.asStateFlow()

    private val _currentTheme = MutableStateFlow(AppTheme.TECH_BLUE)
    val currentTheme: StateFlow<AppTheme> = _currentTheme.asStateFlow()

    private val _shouldPromptOverwrite = MutableStateFlow(true)
    val shouldPromptOverwrite: StateFlow<Boolean> = _shouldPromptOverwrite.asStateFlow()

    private val _debugLogs = MutableStateFlow<List<String>>(emptyList())
    val debugLogs: StateFlow<List<String>> = _debugLogs.asStateFlow()

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

    private fun isVirtualPath(path: String?): Boolean {
        if (path.isNullOrEmpty()) return true
        return path.contains(".transforms", ignoreCase = true) || 
               path.contains("synthetic", ignoreCase = true) || 
               path.contains("/picker/", ignoreCase = true)
    }

    private fun isVirtualName(name: String?): Boolean {
        if (name.isNullOrEmpty()) return true
        val nameWithoutExt = name.substringBeforeLast(".")
        return nameWithoutExt.all { it.isDigit() } || nameWithoutExt.contains("picker", ignoreCase = true)
    }

    init {
        val savedThemeId = sharedPreferences.getString("selected_theme_id", AppTheme.TECH_BLUE.name)
        val matchedTheme = AppTheme.values().firstOrNull { it.name == savedThemeId } ?: AppTheme.TECH_BLUE
        _currentTheme.value = matchedTheme

        val savedOverwritePrompt = sharedPreferences.getBoolean("should_prompt_overwrite", true)
        _shouldPromptOverwrite.value = savedOverwritePrompt
    }

    fun setTheme(theme: AppTheme) {
        _currentTheme.value = theme
        sharedPreferences.edit().putString("selected_theme_id", theme.name).apply()
    }

    fun setShouldPromptOverwrite(value: Boolean) {
        _shouldPromptOverwrite.value = value
        sharedPreferences.edit().putBoolean("should_prompt_overwrite", value).apply()
    }

    fun setUseH265(value: Boolean) {
        _useH265.value = value
    }

    private var currentWorkId: UUID? = null
    private var tempOutputPath: String? = null

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

            val videoItem = withContext(Dispatchers.IO) {
                try {
                    val resolver: ContentResolver = context.contentResolver
                    var displayName = "Unknown Video"
                    var size = 0L

                    // 1. ALWAYS query the basic columns using the original virtual Picker URI (uri) first, which is 100% safe and guaranteed to succeed!
                    resolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIdx != -1) displayName = cursor.getString(nameIdx)
                            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
                        }
                    }
                    logDebug("基础查询结果 - 名称: $displayName, 大小: $size 字节")

                    // 2. ALWAYS query the advanced attributes using the original virtual Picker URI (uri) first using MediaMetadataRetriever, which is 100% safe!
                    logDebug("启动 MediaMetadataRetriever 提取元数据...")
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, uri)
                    
                    val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    var bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
                    
                    retriever.release()
                    logDebug("元数据解析完毕 - 时长: $durationMs ms, 宽: $width, 高: $height, 码率: $bitrate bps")

                    // If bitrate is missing, calcul                    // 3. Try to resolve the real physical path and real filename by mapping Picker/Document URI to standard MediaStore URI and applying multiple fallback strategies
                    var absolutePath: String? = null
                    var realDisplayName: String? = null

                    // Strategy 1 (Direct Query): Try to query absolute path and display name from the original uri (works on legacy systems or direct providers)
                    logDebug("【Strategy 1】启动：尝试直接查询原始 URI 的 DATA 字段...")
                    try {
                        val projection = arrayOf(MediaStore.Video.Media.DATA, OpenableColumns.DISPLAY_NAME)
                        resolver.query(uri, projection, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val pathIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                                if (pathIdx != -1) {
                                    val p = cursor.getString(pathIdx)
                                    if (!p.isNullOrEmpty()) {
                                        if (!isVirtualPath(p)) {
                                            absolutePath = p
                                            logDebug("  -> Strategy 1 成功获取真实绝对路径: $absolutePath")
                                        } else {
                                            logDebug("  -> Strategy 1 拒绝获取的虚拟路径: $p")
                                        }
                                    }
                                }
                                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (nameIdx != -1) {
                                    val n = cursor.getString(nameIdx)
                                    if (!n.isNullOrEmpty()) {
                                        if (!isVirtualName(n)) {
                                            realDisplayName = n
                                            logDebug("  -> Strategy 1 成功获取真实文件名: $realDisplayName")
                                        } else {
                                            logDebug("  -> Strategy 1 拒绝获取的虚拟文件名: $n")
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logDebug("  -> Strategy 1 失败报错: ${e.message}")
                    }

                    // Strategy 2 (ID Mapping): If path/name is still unresolved, try to parse the ID from PhotoPicker or DocumentProvider URI and map to standard MediaStore URI
                    if (absolutePath == null || realDisplayName == null) {
                        logDebug("【Strategy 2】启动：尝试进行 Picker / DocumentProvider ID 拆解与标准 MediaStore 重组映射...")
                        try {
                            var documentId: String? = null
                            if (android.provider.DocumentsContract.isDocumentUri(context, uri)) {
                                documentId = android.provider.DocumentsContract.getDocumentId(uri)
                                logDebug("  -> 检测到 Document URI, documentId: $documentId")
                            }

                            var parsedId: Long? = null
                            if (documentId != null) {
                                if (documentId.contains(":")) {
                                    val parts = documentId.split(":")
                                    val type = parts[0]
                                    val idStr = parts[1]
                                    logDebug("  -> 拆解 documentId 类型: $type, ID字符串: $idStr")
                                    if (type.equals("video", ignoreCase = true) || type.equals("image", ignoreCase = true)) {
                                        parsedId = idStr.toLongOrNull()
                                    } else if (uri.authority?.contains("externalstorage") == true) {
                                        if (type.equals("primary", ignoreCase = true)) {
                                            val p = android.os.Environment.getExternalStorageDirectory().toString() + "/" + idStr
                                            logDebug("  -> 外部存储提供者 primary 映射路径: $p")
                                            if (java.io.File(p).exists()) {
                                                if (!isVirtualPath(p)) {
                                                    absolutePath = p
                                                    logDebug("  -> Strategy 2 路径有效并采纳: $absolutePath")
                                                } else {
                                                    logDebug("  -> Strategy 2 拒绝虚拟路径: $p")
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    parsedId = documentId.toLongOrNull()
                                }
                            } else {
                                parsedId = try {
                                    android.content.ContentUris.parseId(uri)
                                } catch (e: Exception) {
                                    uri.lastPathSegment?.toLongOrNull()
                                }
                            }

                            logDebug("  -> 解析出的 MediaStore ID: $parsedId")
                            if (parsedId != null) {
                                val targetUri = android.content.ContentUris.withAppendedId(
                                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 
                                    parsedId
                                )
                                logDebug("  -> 重组后的目标 MediaStore URI: $targetUri")

                                resolver.query(
                                    targetUri, 
                                    arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media.DISPLAY_NAME), 
                                    null, 
                                    null, 
                                    null
                                )?.use { cursor ->
                                    if (cursor.moveToFirst()) {
                                        if (absolutePath == null) {
                                            val pathIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                                            if (pathIdx != -1) {
                                                val p = cursor.getString(pathIdx)
                                                if (!p.isNullOrEmpty()) {
                                                    if (!isVirtualPath(p)) {
                                                        absolutePath = p
                                                        logDebug("  -> Strategy 2 成功获取真实绝对路径: $absolutePath")
                                                    } else {
                                                        logDebug("  -> Strategy 2 拒绝虚拟绝对路径: $p")
                                                    }
                                                }
                                            }
                                        }
                                        if (realDisplayName == null) {
                                            val nameIdx = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                                            if (nameIdx != -1) {
                                                val n = cursor.getString(nameIdx)
                                                if (!n.isNullOrEmpty()) {
                                                    if (!isVirtualName(n)) {
                                                        realDisplayName = n
                                                        logDebug("  -> Strategy 2 成功获取真实文件名: $realDisplayName")
                                                    } else {
                                                        logDebug("  -> Strategy 2 拒绝虚拟文件名: $n")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            logDebug("  -> Strategy 2 失败报错: ${e.message}")
                        }
                    }

                    // Strategy 3 (Dynamic Fingerprinting Search - Ultimate Fallback):
                    // Query MediaStore for videos matching the exact size, then verify via duration, width, height
                    if (absolutePath == null && size > 0) {
                        logDebug("【Strategy 3】启动：启动智能特征指纹 MediaStore 全盘搜寻兜底...")
                        try {
                            val selection = "${MediaStore.Video.Media.SIZE} = ?"
                            val selectionArgs = arrayOf(size.toString())
                            val projection = arrayOf(
                                MediaStore.Video.Media.DATA,
                                MediaStore.Video.Media.DISPLAY_NAME,
                                MediaStore.Video.Media.DURATION,
                                MediaStore.Video.Media.WIDTH,
                                MediaStore.Video.Media.HEIGHT
                            )

                            logDebug("  -> 开始在本地 MediaStore 中检索 size = $size 字节 的记录...")
                            resolver.query(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                projection,
                                selection,
                                selectionArgs,
                                null
                            )?.use { cursor ->
                                var bestMatchPath: String? = null
                                var bestMatchName: String? = null
                                var matchScore = 0
                                var candidateCount = 0

                                while (cursor.moveToNext()) {
                                    val p = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA))
                                    val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME))
                                    val dur = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION))
                                    val w = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH))
                                    val h = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT))

                                    // Check if candidate path is virtual itself
                                    if (isVirtualPath(p)) {
                                        logDebug("  [候选者(被剔除)] 路径: $p (检测为虚拟路径)")
                                        continue
                                    }

                                    candidateCount++
                                    logDebug("  [候选者 #$candidateCount] 路径: $p, 名字: $name, 时长: $dur, 宽: $w, 高: $h")

                                    var score = 1 // Exact size matched perfectly
                                    if (durationMs > 0 && Math.abs(dur - durationMs) < 2000) {
                                        score += 2 // Duration matched within 2 seconds
                                    }
                                    if (width > 0 && w == width) {
                                        score += 1 // Width matched
                                    }
                                    if (height > 0 && h == height) {
                                        score += 1 // Height matched
                                    }

                                    logDebug("  [候选者 #$candidateCount] 指纹匹配得分: $score")
                                    if (score > matchScore) {
                                        matchScore = score
                                        bestMatchPath = p
                                        bestMatchName = name
                                    }
                                }

                                logDebug("  -> 检索完毕。共评估有效候选文件数: $candidateCount，最高得分: $matchScore")
                                if (matchScore >= 2 && !bestMatchPath.isNullOrEmpty()) {
                                    absolutePath = bestMatchPath
                                    if (realDisplayName == null && !bestMatchName.isNullOrEmpty() && !bestMatchName.all { it.isDigit() }) {
                                        realDisplayName = bestMatchName
                                    }
                                    logDebug("  -> Strategy 3 成功还原真实路径: $absolutePath, 文件名: $realDisplayName")
                                } else {
                                    logDebug("  -> 未能找到得分足够高的真实匹配文件。")
                                }
                            }
                        } catch (e: Exception) {
                            logDebug("  -> Strategy 3 失败报错: ${e.message}")
                        }
                    }

                    // Apply final display name
                    if (!realDisplayName.isNullOrEmpty()) {
                        displayName = realDisplayName!!
                    }

                    logDebug("==== 诊断解析完毕 ====")
                    logDebug("决议路径 (Path): ${absolutePath ?: "【未能获取真实路径，回退到虚拟路径】"}")
                    logDebug("决议名称 (Name): $displayName")

                    VideoItem(
                        uri = uri, // Keep original Picker URI for WorkManager permission compatibility!
                        displayName = displayName,
                        size = size,
                        durationMs = durationMs,
                        width = width,
                        height = height,
                        bitrate = bitrate,
                        path = absolutePath
                    )
                } catch (e: Exception) {
                    logDebug("诊断解析捕获严重异常: ${e.message}")
                    Log.e("MainViewModel", "Error analyzing video metadata", e)
                    null
                }
            }

            if (videoItem != null) {
                _selectedVideo.value = videoItem
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
        _compressionState.value = CompressionState.Compressing

        val inputData = Data.Builder()
            .putString(VideoCompressionWorker.KEY_VIDEO_URI, video.uri.toString())
            .putString(VideoCompressionWorker.KEY_PRESET_ID, preset.id)
            .putBoolean(VideoCompressionWorker.KEY_USE_H265, _useH265.value)
            .build()

        val compressionWork = OneTimeWorkRequestBuilder<VideoCompressionWorker>()
            .setInputData(inputData)
            .build()

        currentWorkId = compressionWork.id
        val workManager = WorkManager.getInstance(context)
        workManager.enqueue(compressionWork)

        // Observe progress & state
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(compressionWork.id).collect { workInfo ->
                if (workInfo != null) {
                    when (workInfo.state) {
                        WorkInfo.State.RUNNING -> {
                            val progress = workInfo.progress.getInt(VideoCompressionWorker.KEY_PROGRESS, 0)
                            _compressionProgress.value = progress
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            val path = workInfo.outputData.getString(VideoCompressionWorker.KEY_OUTPUT_PATH)
                            tempOutputPath = path
                            if (path != null) {
                                val file = File(path)
                                if (file.exists()) {
                                    _compressedVideoSize.value = file.length()
                                }
                                if (_shouldPromptOverwrite.value) {
                                    _compressionState.value = CompressionState.AwaitingOverwriteConfirmation
                                } else {
                                    // Bypass the app's custom dialog and request system overwrite deletion directly!
                                    _compressionState.value = CompressionState.Replacing
                                    requestDeleteOriginal(getApplication())
                                }
                            } else {
                                _compressionState.value = CompressionState.Failed("转码完成，但未找到临时输出文件。")
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            val errMsg = workInfo.outputData.getString(VideoCompressionWorker.KEY_ERROR_MSG) ?: "未知硬件加速错误"
                            _compressionState.value = CompressionState.Failed("视频压缩失败: $errMsg")
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
            val originalPath = video.path
            val tempFile = File(tempPath)

            if (!tempFile.exists()) {
                _compressionState.value = CompressionState.Failed("找不到转码完成的压缩临时文件。")
                return@launch
            }

            var success = false
            var errorMsg = "未知错误"

            // Try 1: Atomic File Path Rename if the app has direct write access (highly compatible on legacy/legacy storage configs)
            if (originalPath != null) {
                try {
                    val originalFile = File(originalPath)
                    
                    // Copy file channel content to destination
                    FileInputStream(tempFile).use { input ->
                        FileOutputStream(originalFile).use { output ->
                            input.channel.transferTo(0, input.channel.size(), output.channel)
                        }
                    }
                    success = true
                    tempFile.delete()
                    Log.i("MainViewModel", "File replaced successfully via direct channel copy.")
                } catch (e: Exception) {
                    errorMsg = "物理路径直写失败: ${e.localizedMessage ?: e.message}"
                    Log.w("MainViewModel", "Direct copy failed, falling back to MediaStore insert: ${e.message}")
                }
            }

            // Try 2: If direct writing to physical storage fails under strict Android 11+ scoped storage, we write via MediaStore
            if (!success) {
                try {
                    val resolver = context.contentResolver
                    val finalDisplayName = if (video.displayName.endsWith(".mp4", ignoreCase = true)) {
                        video.displayName
                    } else {
                        "${video.displayName}.mp4"
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
                                        val subPath = originalPath.substring(index + 1) // e.g. "DCIM/Camera/video.mp4"
                                        val dir = subPath.substringBeforeLast("/") // e.g. "DCIM/Camera"
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
                        Log.i("MainViewModel", "File saved successfully via MediaStore insertion.")
                    } else {
                        errorMsg = "MediaStore插入返回Uri为空"
                    }
                } catch (e: Exception) {
                    errorMsg = "MediaStore写入捕获异常: ${e.localizedMessage ?: e.message}"
                    Log.e("MainViewModel", "MediaStore write failed", e)
                }
            }

            if (success) {
                // Force media scanner re-index to refresh gallery correctly
                val pathsToScan = if (originalPath != null) arrayOf(originalPath) else arrayOf()
                MediaScannerConnection.scanFile(context, pathsToScan, arrayOf("video/mp4")) { path, uri ->
                    Log.i("MainViewModel", "MediaScanner refresh completed for: $path -> $uri")
                }
                
                withContext(Dispatchers.Main) {
                    _compressionState.value = CompressionState.Completed(originalPath ?: video.displayName)
                    _selectedVideo.value = null
                    _selectedPreset.value = null
                }
            } else {
                withContext(Dispatchers.Main) {
                    _compressionState.value = CompressionState.Failed("写入并覆盖压缩视频失败 ($errorMsg)")
                }
            }
        }
    }

    fun resetState() {
        _compressionState.value = CompressionState.Idle
        _selectedVideo.value = null
        _selectedPreset.value = null
        _useH265.value = true
        _compressionProgress.value = 0
    }
}
