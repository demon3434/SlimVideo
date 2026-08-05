package com.example.slimvideo.utils

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.example.slimvideo.data.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VideoMetadataResolver {

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

    suspend fun resolveVideoItem(
        context: Context,
        uri: Uri,
        log: (String) -> Unit
    ): VideoItem? = withContext(Dispatchers.IO) {
        try {
            val resolver: ContentResolver = context.contentResolver
            var displayName = "Unknown Video"
            var size = 0L

            // 1. ALWAYS query the basic columns using the original virtual Picker URI (uri) first
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1) displayName = cursor.getString(nameIdx)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
                }
            }
            log("基础查询结果 - 名称: $displayName, 大小: $size 字节")

            // 2. ALWAYS query the advanced attributes using MediaMetadataRetriever
            log("启动 MediaMetadataRetriever 提取元数据...")
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            
            retriever.release()
            log("元数据解析完毕 - 时长: $durationMs ms, 宽: $width, 高: $height, 码率: $bitrate bps")

            // 2.1 Advanced metadata extraction using MediaExtractor
            var fps = 0.0
            var codec = "未知"
            var bitDepth = 8

            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                val trackCount = extractor.trackCount
                for (i in 0 until trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/")) {
                        if (format.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) {
                            fps = try {
                                format.getFloat(android.media.MediaFormat.KEY_FRAME_RATE).toDouble()
                            } catch (e: Exception) {
                                format.getInteger(android.media.MediaFormat.KEY_FRAME_RATE).toDouble()
                            }
                        }
                        codec = when {
                            mime.contains("hevc") || mime.contains("h265") -> "H.265"
                            mime.contains("avc") || mime.contains("h264") -> "H.264"
                            mime.contains("vp9") -> "VP9"
                            mime.contains("vp8") -> "VP8"
                            mime.contains("av01") || mime.contains("av1") -> "AV1"
                            else -> mime.substringAfter("video/").uppercase()
                        }
                        if (format.containsKey(android.media.MediaFormat.KEY_PROFILE)) {
                            val profile = format.getInteger(android.media.MediaFormat.KEY_PROFILE)
                            if (mime.contains("hevc") && (profile == 2 || profile == 4096 || profile == 8192)) {
                                bitDepth = 10
                            } else if (mime.contains("avc") && profile == 2048) {
                                bitDepth = 10
                            } else if (mime.contains("vp9") && (profile == 4096 || profile == 8192)) {
                                bitDepth = 10
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log("MediaExtractor 提取高级属性失败: ${e.message}")
            } finally {
                try {
                    extractor.release()
                } catch (e: Exception) { /* ignore */ }
            }

            // Fallback FPS calculation
            if (fps <= 0.0) {
                try {
                    val ret = MediaMetadataRetriever()
                    ret.setDataSource(context, uri)
                    val frameCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
                    } else 0
                    if (frameCount > 0 && durationMs > 0) {
                        fps = frameCount.toDouble() / (durationMs / 1000.0)
                    }
                    ret.release()
                } catch (e: Exception) { /* ignore */ }
            }
            if (fps <= 0.0) fps = 30.0
            log("高级属性提取完毕 - 帧率: $fps fps, 编码: $codec, 位深: $bitDepth bit")

            // 3. Try to resolve the real physical path and real filename
            var absolutePath: String? = null
            var realDisplayName: String? = null

            // Strategy 1 (Direct Query): DATA column query
            log("【Strategy 1】启动：尝试直接查询原始 URI 的 DATA 字段...")
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
                                    log("  -> Strategy 1 成功获取真实绝对路径: $absolutePath")
                                } else {
                                    log("  -> Strategy 1 拒绝获取的虚拟路径: $p")
                                }
                            }
                        }
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx != -1) {
                            val n = cursor.getString(nameIdx)
                            if (!n.isNullOrEmpty()) {
                                if (!isVirtualName(n)) {
                                    realDisplayName = n
                                    log("  -> Strategy 1 成功获取真实文件名: $realDisplayName")
                                } else {
                                    log("  -> Strategy 1 拒绝获取的虚拟文件名: $n")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log("  -> Strategy 1 失败报错: ${e.message}")
            }

            // Strategy 2 (ID Mapping)
            if (absolutePath == null || realDisplayName == null) {
                log("【Strategy 2】启动：尝试进行 Picker / DocumentProvider ID 拆解与标准 MediaStore 重组映射...")
                try {
                    var documentId: String? = null
                    if (android.provider.DocumentsContract.isDocumentUri(context, uri)) {
                        documentId = android.provider.DocumentsContract.getDocumentId(uri)
                        log("  -> 检测到 Document URI, documentId: $documentId")
                    }

                    var parsedId: Long? = null
                    if (documentId != null) {
                        if (documentId.contains(":")) {
                            val parts = documentId.split(":")
                            val type = parts[0]
                            val idStr = parts[1]
                            log("  -> 拆解 documentId 类型: $type, ID字符串: $idStr")
                            if (type.equals("video", ignoreCase = true) || type.equals("image", ignoreCase = true)) {
                                parsedId = idStr.toLongOrNull()
                            } else if (uri.authority?.contains("externalstorage") == true) {
                                if (type.equals("primary", ignoreCase = true)) {
                                    val p = android.os.Environment.getExternalStorageDirectory().toString() + "/" + idStr
                                    log("  -> 外部存储提供者 primary 映射路径: $p")
                                    if (File(p).exists()) {
                                        if (!isVirtualPath(p)) {
                                            absolutePath = p
                                            log("  -> Strategy 2 路径有效并采纳: $absolutePath")
                                        } else {
                                            log("  -> Strategy 2 拒绝虚拟路径: $p")
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

                    log("  -> 解析出的 MediaStore ID: $parsedId")
                    if (parsedId != null) {
                        val targetUri = android.content.ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 
                            parsedId
                        )
                        log("  -> 重组后的目标 MediaStore URI: $targetUri")

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
                                                log("  -> Strategy 2 成功获取真实绝对路径: $absolutePath")
                                            } else {
                                                log("  -> Strategy 2 拒绝虚拟绝对路径: $p")
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
                                                log("  -> Strategy 2 成功获取真实文件名: $realDisplayName")
                                            } else {
                                                log("  -> Strategy 2 拒绝虚拟文件名: $n")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    log("  -> Strategy 2 失败报错: ${e.message}")
                }
            }

            // Strategy 3 (Dynamic Fingerprinting Search)
            if (absolutePath == null && size > 0) {
                log("【Strategy 3】启动：启动智能特征指纹 MediaStore 全盘搜寻兜底...")
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

                    log("  -> 开始在本地 MediaStore 中检索 size = $size 字节 的记录...")
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

                            if (isVirtualPath(p)) {
                                log("  [候选者(被剔除)] 路径: $p (检测为虚拟路径)")
                                continue
                            }

                            candidateCount++
                            log("  [候选者 #$candidateCount] 路径: $p, 名字: $name, 时长: $dur, 宽: $w, 高: $h")

                            var score = 1
                            if (durationMs > 0 && Math.abs(dur - durationMs) < 2000) {
                                score += 2
                            }
                            if (width > 0 && w == width) {
                                score += 1
                            }
                            if (height > 0 && h == height) {
                                score += 1
                            }

                            log("  [候选者 #$candidateCount] 指纹匹配得分: $score")
                            if (score > matchScore) {
                                matchScore = score
                                bestMatchPath = p
                                bestMatchName = name
                            }
                        }

                        log("  -> 检索完毕。共评估有效候选文件数: $candidateCount，最高得分: $matchScore")
                        if (matchScore >= 2 && !bestMatchPath.isNullOrEmpty()) {
                            absolutePath = bestMatchPath
                            if (realDisplayName == null && !bestMatchName.isNullOrEmpty() && !bestMatchName.all { it.isDigit() }) {
                                realDisplayName = bestMatchName
                            }
                            log("  -> Strategy 3 成功还原真实路径: $absolutePath, 文件名: $realDisplayName")
                        } else {
                            log("  -> 未能找到得分足够高的真实匹配文件。")
                        }
                    }
                } catch (e: Exception) {
                    log("  -> Strategy 3 失败报错: ${e.message}")
                }
            }

            if (!realDisplayName.isNullOrEmpty()) {
                displayName = realDisplayName!!
            }

            var fileTimestamp = 0L
            if (!absolutePath.isNullOrEmpty()) {
                try {
                    val file = File(absolutePath!!)
                    if (file.exists()) {
                        fileTimestamp = file.lastModified()
                        log("文件日期提取 - 通过绝对路径提取成功: $fileTimestamp")
                    }
                } catch (e: Exception) {
                    log("文件日期提取 - 绝对路径读取异常: ${e.message}")
                }
            }
            
            if (fileTimestamp <= 0L) {
                try {
                    val projection = arrayOf(MediaStore.Video.Media.DATE_MODIFIED)
                    resolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val dateIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                            if (dateIdx != -1) {
                                fileTimestamp = cursor.getLong(dateIdx) * 1000L
                                log("文件日期提取 - 通过 MediaStore DATE_MODIFIED 提取成功: $fileTimestamp")
                            }
                        }
                    }
                } catch (e: Exception) {
                    log("文件日期提取 - MediaStore 读取异常: ${e.message}")
                }
            }
            
            if (fileTimestamp <= 0L) {
                fileTimestamp = System.currentTimeMillis()
                log("文件日期提取 - 采用当前系统时间兜底: $fileTimestamp")
            }
            
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val fileDate = sdf.format(Date(fileTimestamp))

            log("==== 诊断解析完毕 ====")
            log("决议路径 (Path): ${absolutePath ?: "【未能获取真实路径，回退 to 虚拟路径】"}")
            log("决议名称 (Name): $displayName")
            log("决议日期 (Date): $fileDate")

            VideoItem(
                uri = uri,
                displayName = displayName,
                size = size,
                durationMs = durationMs,
                width = width,
                height = height,
                bitrate = bitrate,
                path = absolutePath,
                fps = fps,
                codec = codec,
                bitDepth = bitDepth,
                fileDate = fileDate
            )
        } catch (e: Exception) {
            log("诊断解析捕获严重异常: ${e.message}")
            Log.e("VideoMetadataResolver", "Error resolving video metadata", e)
            null
        }
    }
}
