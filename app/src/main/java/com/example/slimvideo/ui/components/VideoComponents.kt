package com.example.slimvideo.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.slimvideo.data.AppTheme
import com.example.slimvideo.data.CompressionPreset
import com.example.slimvideo.data.VideoItem
import com.example.slimvideo.viewmodel.CompressionState
import java.util.Locale

@Composable
fun HeaderSection(
    theme: AppTheme,
    onSettingsClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (theme.isDark) {
                    Brush.verticalGradient(
                        colors = listOf(theme.surface.copy(alpha = 0.6f), theme.background.copy(alpha = 0.8f))
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(theme.surface, theme.background.copy(alpha = 0.5f))
                    )
                }
            )
            .border(
                1.dp,
                if (theme.isDark) {
                    Brush.horizontalGradient(listOf(theme.primary.copy(alpha = 0.4f), theme.secondary.copy(alpha = 0.4f)))
                } else {
                    Brush.horizontalGradient(listOf(theme.primary.copy(alpha = 0.2f), theme.secondary.copy(alpha = 0.2f)))
                },
                RoundedCornerShape(12.dp)
            )
            .padding(vertical = 6.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "胶卷瘦身",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = theme.textPrimary,
            textAlign = TextAlign.Center
        )

        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier
                .size(36.dp)
                .align(Alignment.CenterEnd)
        ) {
            Text(
                text = "⚙️",
                fontSize = 18.sp,
                color = theme.primary
            )
        }
    }
}

@Composable
fun ImportBox(onClick: () -> Unit, isAnalyzing: Boolean, theme: AppTheme) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(theme.surface.copy(alpha = if (theme.isDark) 0.4f else 0.8f))
            .border(
                BorderStroke(2.dp, Brush.sweepGradient(listOf(theme.primary, theme.secondary))),
                RoundedCornerShape(20.dp)
            )
            .clickable(enabled = !isAnalyzing, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isAnalyzing) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = theme.primary)
                Spacer(modifier = Modifier.height(12.dp))
                Text("智能分析源视频属性中...", color = theme.textPrimary, fontSize = 15.sp)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "➕",
                    fontSize = 42.sp,
                    color = theme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "导入源视频进行智能高效瘦身",
                    fontWeight = FontWeight.Bold,
                    color = theme.textPrimary,
                    fontSize = 16.sp
                )
                Text(
                    text = "本地安全压缩 • 原地覆盖原片，直接释放手机空间",
                    color = theme.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun VideoMetadataCard(video: VideoItem, theme: AppTheme, onSelectAnother: () -> Unit) {
    val durationSec = video.durationMs / 1000
    val durationText = String.format(Locale.getDefault(), "%02d:%02d", durationSec / 60, durationSec % 60)
    // val sizeMb = video.size.toDouble() / (1024 * 1024) // 旧二进制标准
    val sizeMb = video.size.toDouble() / (1000 * 1000) // 改用十进制标准 (SI)，与手机相册保持一致
    val sizeText = String.format(Locale.getDefault(), "%.1f MB", sizeMb)
    val bitrateText = String.format(Locale.getDefault(), "%.1f Mbps", video.bitrate.toDouble() / 1_000_000)

    val fpsText = if (video.fps % 1 == 0.0) "${video.fps.toInt()} fps" else String.format(Locale.getDefault(), "%.2f fps", video.fps)
    val bitDepthText = "${video.bitDepth} bit"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(theme.surface.copy(alpha = if (theme.isDark) 0.6f else 0.9f))
            .border(1.dp, theme.primary.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🎥 选定视频元数据",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = theme.textPrimary
            )
            Text(
                text = "重新选择",
                color = theme.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable(onClick = onSelectAnother)
                    .padding(4.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = video.displayName,
            fontSize = 14.sp,
            color = theme.textPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = "路径: ${video.path ?: video.uri.toString()}",
            fontSize = 11.sp,
            color = theme.textSecondary,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 第一行：体积大小 | 播放时长 | 原始分辨率 | 视频码率
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetadataColumn("体积大小", sizeText, theme)
            MetadataColumn("播放时长", durationText, theme)
            MetadataColumn("原始分辨率", "${video.width} × ${video.height}", theme)
            MetadataColumn("视频码率", bitrateText, theme)
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = theme.border.copy(alpha = 0.2f), thickness = 1.dp)
        Spacer(modifier = Modifier.height(12.dp))

        // 第二行：帧率 | 视频编码 | 色彩位深 | 文件日期
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetadataColumn("帧率", fpsText, theme)
            MetadataColumn("视频编码", video.codec, theme)
            MetadataColumn("色彩位深", bitDepthText, theme)
            MetadataColumn("文件日期", video.fileDate, theme)
        }
    }
}

@Composable
fun MetadataColumn(label: String, value: String, theme: AppTheme) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = theme.textSecondary, fontSize = 11.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, color = theme.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PresetSelectionGrid(
    video: VideoItem,
    selectedPreset: CompressionPreset?,
    useH265: Boolean,
    keepOriginalFps: Boolean,
    theme: AppTheme,
    onPresetSelected: (CompressionPreset) -> Unit
) {
    val presets = CompressionPreset.values()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        presets.forEach { preset ->
            val eligible = preset.isEligible(video.width, video.height, video.bitrate)
            val selected = selectedPreset == preset

            val cardBgColor = when {
                !eligible -> theme.surface.copy(alpha = 0.2f)
                selected -> theme.primary.copy(alpha = if (theme.isDark) 0.3f else 0.15f)
                else -> theme.surface.copy(alpha = if (theme.isDark) 0.6f else 0.9f)
            }
            val borderBrush = when {
                !eligible -> Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                selected -> Brush.linearGradient(listOf(theme.primary, theme.secondary))
                else -> Brush.linearGradient(listOf(theme.border.copy(alpha = 0.4f), theme.border.copy(alpha = 0.4f)))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(cardBgColor)
                    .border(1.dp, borderBrush, RoundedCornerShape(14.dp))
                    .clickable(enabled = eligible, onClick = { onPresetSelected(preset) })
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected,
                    onClick = { if (eligible) onPresetSelected(preset) },
                    enabled = eligible,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = theme.secondary,
                        unselectedColor = theme.textSecondary.copy(alpha = 0.7f)
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = preset.presetName,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (eligible) theme.textPrimary else theme.textSecondary
                        )
                        if (eligible) {
                            val durationSec = video.durationMs / 1000
                            val estSize = preset.estimateSizeMb(durationSec, useH265)
                            // val originalSizeMb = video.size.toDouble() / (1024 * 1024) // 旧二进制标准
                            val originalSizeMb = video.size.toDouble() / (1000 * 1000) // 改用十进制标准 (SI)
                            val savingsPercent = ((originalSizeMb - estSize) / originalSizeMb * 100).coerceAtLeast(0.0)

                            Text(
                                text = String.format(Locale.getDefault(), "节省约 %.0f%%", savingsPercent),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = theme.secondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(theme.secondary.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        } else {
                            Text(
                                text = "低于限制 • 不可选",
                                fontSize = 11.sp,
                                color = Color(0xFFEF4444),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFEF4444).copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Text(
                        text = preset.description,
                        fontSize = 12.sp,
                        color = if (eligible) theme.textSecondary else theme.textSecondary.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    if (eligible) {
                        val durationSec = video.durationMs / 1000
                        val estSize = preset.estimateSizeMb(durationSec, useH265)
                        val displayFps = if (keepOriginalFps) video.fps.toInt() else preset.targetFps
                        Text(
                            text = String.format(
                                Locale.getDefault(),
                                "目标参数: %s / %dp / %dfps / %.1fMbps • 预估体积: %.1f MB",
                                if (useH265) "H.265" else "H.264",
                                preset.targetHeight,
                                displayFps,
                                preset.targetBitrateBps.toDouble() / 1_000_000,
                                estSize
                            ),
                            fontSize = 11.sp,
                            color = theme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ActionSection(
    state: CompressionState,
    progress: Int,
    statusText: String,
    selectedPreset: CompressionPreset?,
    theme: AppTheme,
    onStartCompression: () -> Unit,
    onReset: () -> Unit,
    onCancelCompression: () -> Unit
) {
    when (state) {
        is CompressionState.Ready -> {
            Button(
                onClick = onStartCompression,
                enabled = selectedPreset != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(16.dp)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = theme.secondary,
                    disabledContainerColor = theme.border.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    text = if (selectedPreset != null) "一键智能极速瘦身" else "请选择压缩档位",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selectedPreset != null) Color.White else theme.textSecondary
                )
            }
        }
        is CompressionState.Compressing -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(theme.surface.copy(alpha = if (theme.isDark) 0.5f else 0.8f))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = statusText,
                    fontSize = 12.sp,
                    color = theme.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                    minLines = 2,
                    maxLines = 2
                )

                Spacer(modifier = Modifier.height(12.dp))

                LinearProgressIndicator(
                    progress = { progress.toFloat() / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = theme.primary,
                    trackColor = theme.border
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "$progress%",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    color = theme.primary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onCancelCompression,
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(38.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF4444),
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = "取消转码",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        is CompressionState.AwaitingUserPermission -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFD97706).copy(alpha = 0.15f))
                    .border(1.dp, Color(0xFFD97706), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⏳ 等待系统授权删除原始大视频...",
                    color = Color(0xFFFBBF24),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        is CompressionState.Replacing -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = theme.secondary)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "正在安全地原位同名替换原始视频...",
                    color = theme.textPrimary,
                    fontSize = 14.sp
                )
            }
        }
        is CompressionState.Completed -> {
            val savedPath = state.savedPath
            // val origSizeMb = state.originalSize.toDouble() / (1024 * 1024) // 旧二进制标准
            // val newSizeMb = state.size.toDouble() / (1024 * 1024) // 旧二进制标准
            val origSizeMb = state.originalSize.toDouble() / (1000 * 1000) // 改用十进制标准 (SI)
            val newSizeMb = state.size.toDouble() / (1000 * 1000) // 改用十进制标准 (SI)
            val savedSizeMb = origSizeMb - newSizeMb
            
            val origSizeText = String.format(Locale.getDefault(), "%.1f MB", origSizeMb)
            val newSizeText = String.format(Locale.getDefault(), "%.1f MB", newSizeMb)
            val savedSizeText = String.format(Locale.getDefault(), "%.1f MB", savedSizeMb)

            val savingsPercent = if (state.originalSize > 0) {
                ((state.originalSize - state.size).toDouble() / state.originalSize.toDouble()) * 100
            } else 0.0
            val savingsPercentText = String.format(Locale.getDefault(), "%.1f%%", savingsPercent)
            val newFpsText = if (state.fps % 1 == 0.0) "${state.fps.toInt()} fps" else String.format(Locale.getDefault(), "%.2f fps", state.fps)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(theme.secondary.copy(alpha = 0.15f))
                    .border(2.dp, theme.secondary, RoundedCornerShape(20.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "🎉 瘦身完美成功！",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = theme.secondary
                )
                
                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(theme.surface.copy(alpha = 0.8f))
                        .border(1.dp, theme.secondary.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                        .padding(vertical = 12.dp, horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = buildAnnotatedString {
                            append(origSizeText)
                            append(" → ")
                            withStyle(SpanStyle(color = theme.secondary, fontWeight = FontWeight.Black)) {
                                append(newSizeText)
                            }
                            append("，文件减小了 ")
                            withStyle(SpanStyle(color = theme.secondary, fontWeight = FontWeight.Black)) {
                                append(savedSizeText)
                            }
                        },
                        fontSize = 13.sp,
                        color = theme.textPrimary,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(theme.surface.copy(alpha = 0.5f))
                        .padding(vertical = 10.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetadataResultColumn("体积缩减", savingsPercentText, theme)
                    MetadataResultColumn("分辨率", "${state.width} × ${state.height}", theme)
                    MetadataResultColumn("最终帧率", newFpsText, theme)
                    MetadataResultColumn("视频编码", state.codec, theme)
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "原视频已被瘦身版原位同名覆盖，相册已同步刷新。已为您释放大量手机空间！",
                    fontSize = 13.sp,
                    color = theme.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                Text(
                    text = "保存完整路径:\n$savedPath",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = theme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                )
                Button(
                    onClick = onReset,
                    colors = ButtonDefaults.buttonColors(containerColor = theme.secondary),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("继续瘦身其他视频")
                }
            }
        }
        is CompressionState.Failed -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFFEF4444).copy(alpha = 0.15f))
                    .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(20.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "⚠️ 处理过程中止",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFEF4444)
                )
                Text(
                    text = state.error,
                    fontSize = 13.sp,
                    color = Color(0xFFEF4444),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Button(
                    onClick = onReset,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("返回重试")
                }
            }
        }
        else -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(theme.surface),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "请先选择视频",
                    color = theme.textSecondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun FormatSelectionSection(
    useH265: Boolean,
    theme: AppTheme,
    onFormatSelected: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp)
    ) {
        Text(
            text = "📀 选择编码标准 (兼容与效率)",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = theme.textPrimary,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(theme.surface.copy(alpha = 0.6f))
                .border(1.dp, theme.border.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (useH265) theme.primary.copy(alpha = if (theme.isDark) 0.3f else 0.15f)
                        else Color.Transparent
                    )
                    .border(
                        1.dp,
                        if (useH265) theme.primary else Color.Transparent,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { onFormatSelected(true) }
                    .padding(vertical = 12.dp, horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                RadioButton(
                    selected = useH265,
                    onClick = { onFormatSelected(true) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = theme.secondary,
                        unselectedColor = theme.textSecondary.copy(alpha = 0.7f)
                    )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "H.265 (HEVC)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.textPrimary
                    )
                    Text(
                        text = "推荐 • 体积超小",
                        fontSize = 10.sp,
                        color = theme.secondary
                    )
                }
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (!useH265) theme.primary.copy(alpha = if (theme.isDark) 0.3f else 0.15f)
                        else Color.Transparent
                    )
                    .border(
                        1.dp,
                        if (!useH265) theme.primary else Color.Transparent,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { onFormatSelected(false) }
                    .padding(vertical = 12.dp, horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                RadioButton(
                    selected = !useH265,
                    onClick = { onFormatSelected(false) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = theme.secondary,
                        unselectedColor = theme.textSecondary.copy(alpha = 0.7f)
                    )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "H.264 (AVC)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.textPrimary
                    )
                    Text(
                        text = "通用 • 兼容度极高",
                        fontSize = 10.sp,
                        color = theme.textSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun FpsOptionSection(
    keepOriginalFps: Boolean,
    theme: AppTheme,
    onToggle: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp)
    ) {
        Text(
            text = "🎞️ 帧率处理标准 (保留与降帧)",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = theme.textPrimary,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(theme.surface.copy(alpha = 0.6f))
                .border(1.dp, theme.border.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (!keepOriginalFps) theme.primary.copy(alpha = if (theme.isDark) 0.3f else 0.15f)
                        else Color.Transparent
                    )
                    .border(
                        1.dp,
                        if (!keepOriginalFps) theme.primary else Color.Transparent,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { onToggle(false) }
                    .padding(vertical = 12.dp, horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                RadioButton(
                    selected = !keepOriginalFps,
                    onClick = { onToggle(false) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = theme.secondary,
                        unselectedColor = theme.textSecondary.copy(alpha = 0.7f)
                    )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "智能降帧",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.textPrimary
                    )
                    Text(
                        text = "省空间 • 推荐",
                        fontSize = 10.sp,
                        color = theme.secondary
                    )
                }
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (keepOriginalFps) theme.primary.copy(alpha = if (theme.isDark) 0.3f else 0.15f)
                        else Color.Transparent
                    )
                    .border(
                        1.dp,
                        if (keepOriginalFps) theme.primary else Color.Transparent,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { onToggle(true) }
                    .padding(vertical = 12.dp, horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                RadioButton(
                    selected = keepOriginalFps,
                    onClick = { onToggle(true) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = theme.secondary,
                        unselectedColor = theme.textSecondary.copy(alpha = 0.7f)
                    )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "保留原帧率",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.textPrimary
                    )
                    Text(
                        text = "极度丝滑",
                        fontSize = 10.sp,
                        color = theme.textSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun MetadataResultColumn(label: String, value: String, theme: AppTheme) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = theme.textSecondary.copy(alpha = 0.8f), fontSize = 10.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, color = theme.secondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
