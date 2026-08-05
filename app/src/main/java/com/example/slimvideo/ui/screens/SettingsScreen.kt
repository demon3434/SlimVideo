package com.example.slimvideo.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.example.slimvideo.data.AppTheme

@Composable
fun SettingsScreen(
    theme: AppTheme,
    shouldPromptOverwrite: Boolean,
    onPromptOverwriteChange: (Boolean) -> Unit,
    defaultKeepFps: Boolean,
    onDefaultKeepFpsChange: (Boolean) -> Unit,
    settingsLevel: Int,
    onSettingsLevelChange: (Int) -> Unit,
    onBackClick: () -> Unit,
    onThemeSelect: (AppTheme) -> Unit,
    debugLogs: List<String>,
    onClearDebugLogs: () -> Unit,
    tempFilesSize: Long,
    onClearTempFiles: () -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.background)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Text(
                    text = "←",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = theme.primary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when (settingsLevel) {
                    2 -> "选择配色主题"
                    3 -> "路径解析诊断日志"
                    else -> "设置"
                },
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = theme.textPrimary
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
        ) {
            if (settingsLevel == 1) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = theme.surface),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, theme.border.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "🎨 个性化",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = theme.textPrimary,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSettingsLevelChange(2) }
                                .padding(vertical = 14.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(theme.primary)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "切换配色主题",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = theme.textPrimary
                                )
                                Text(
                                    text = "挑选个性化的高内聚暗黑或浅色主题",
                                    fontSize = 12.sp,
                                    color = theme.textSecondary,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End
                            ) {
                                Text(
                                    text = theme.displayName,
                                    fontSize = 13.sp,
                                    color = theme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "›",
                                    fontSize = 20.sp,
                                    color = theme.textSecondary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = theme.surface),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, theme.border.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "🎞️ 视频帧率默认配置",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = theme.textPrimary,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onDefaultKeepFpsChange(!defaultKeepFps) }
                                .padding(vertical = 12.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "默认保留原始视频帧率",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = theme.textPrimary
                                )
                                Text(
                                    text = "开启时，导入视频将默认选择“保留原帧率”；关闭时，默认采用“智能降帧”以最大限度压缩文件体积。",
                                    fontSize = 12.sp,
                                    color = theme.textSecondary,
                                    modifier = Modifier.padding(top = 4.dp, end = 8.dp)
                                )
                            }

                            Switch(
                                checked = defaultKeepFps,
                                onCheckedChange = onDefaultKeepFpsChange,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = theme.secondary,
                                    uncheckedThumbColor = theme.textSecondary,
                                    uncheckedTrackColor = theme.border.copy(alpha = 0.5f)
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = theme.surface),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, theme.border.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "💾 存储与覆盖设置",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = theme.textPrimary,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPromptOverwriteChange(!shouldPromptOverwrite) }
                                .padding(vertical = 12.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "保存时提示覆盖确认",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = theme.textPrimary
                                )
                                Text(
                                    text = "开启时，压缩完成后会弹窗提示确认是否覆盖原视频；关闭时，压缩完直接启动系统覆盖，免去应用内多余的确认步骤。",
                                    fontSize = 12.sp,
                                    color = theme.textSecondary,
                                    modifier = Modifier.padding(top = 4.dp, end = 8.dp)
                                )
                            }

                            Switch(
                                checked = shouldPromptOverwrite,
                                onCheckedChange = onPromptOverwriteChange,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = theme.secondary,
                                    uncheckedThumbColor = theme.textSecondary,
                                    uncheckedTrackColor = theme.border.copy(alpha = 0.5f)
                                )
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = theme.border.copy(alpha = 0.2f)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val tempSizeMb = tempFilesSize.toDouble() / (1024 * 1024)
                            val sizeText = if (tempSizeMb > 1024.0) {
                                String.format(java.util.Locale.getDefault(), "%.2f GB", tempSizeMb / 1024.0)
                            } else {
                                String.format(java.util.Locale.getDefault(), "%.1f MB", tempSizeMb)
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "转码临时缓存大小",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = theme.textPrimary
                                )
                                Text(
                                    text = "已占用: $sizeText",
                                    fontSize = 12.sp,
                                    color = theme.textSecondary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }

                            Button(
                                onClick = onClearTempFiles,
                                colors = ButtonDefaults.buttonColors(containerColor = theme.primary.copy(alpha = 0.8f)),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("立即清理", color = Color.White, fontSize = 13.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = theme.surface),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, theme.border.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "🛠️ 调试与诊断",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = theme.textPrimary,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSettingsLevelChange(3) }
                                .padding(vertical = 14.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📟",
                                fontSize = 20.sp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "路径解析诊断日志",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = theme.textPrimary
                                )
                                Text(
                                    text = "查看视频在系统相册中的底层数据库解析状态与日志反馈",
                                    fontSize = 12.sp,
                                    color = theme.textSecondary,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End
                            ) {
                                val logCount = debugLogs.size
                                Text(
                                    text = if (logCount > 0) "${logCount} 条日志" else "空闲",
                                    fontSize = 13.sp,
                                    color = if (logCount > 0) theme.secondary else theme.textSecondary,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "›",
                                    fontSize = 20.sp,
                                    color = theme.textSecondary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            } else if (settingsLevel == 2) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = theme.surface),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, theme.border.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "🎨 切换配色主题",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = theme.textPrimary,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        AppTheme.values().forEach { appTheme ->
                            val isSelected = appTheme == theme
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isSelected) theme.primary.copy(alpha = if (theme.isDark) 0.2f else 0.1f)
                                        else Color.Transparent
                                    )
                                    .clickable { onThemeSelect(appTheme) }
                                    .padding(vertical = 12.dp, horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(appTheme.primary)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = appTheme.displayName,
                                        fontSize = 15.sp,
                                        color = theme.textPrimary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        text = if (appTheme.isDark) "深色调" else "浅色调",
                                        fontSize = 11.sp,
                                        color = theme.textSecondary
                                    )
                                }
                                if (isSelected) {
                                    Text(
                                        text = "✓",
                                        color = theme.primary,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            } else if (settingsLevel == 3) {
                val clipboardManager = LocalClipboardManager.current
                val localContext = LocalContext.current

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = theme.surface),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, theme.border.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "🛠️ 路径解析可视化诊断终端",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = theme.textPrimary,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        Text(
                            text = "当您在手机上选择视频后，下方的诊断控制台会实时打印视频数据库解析的底层字段。请复制或截图此日志发送给开发人员。",
                            fontSize = 12.sp,
                            color = theme.textSecondary,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(380.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF0D0E15))
                                .border(1.dp, Color(0xFF1F2437), RoundedCornerShape(12.dp))
                                .padding(8.dp)
                        ) {
                            if (debugLogs.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "终端空闲中。\n请返回主页并导入视频以启动诊断分析...",
                                        color = Color(0xFF6B7280),
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 20.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            } else {
                                val terminalScrollState = rememberScrollState()
                                LaunchedEffect(debugLogs.size) {
                                    terminalScrollState.animateScrollTo(terminalScrollState.maxValue)
                                }

                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(terminalScrollState)
                                ) {
                                    debugLogs.forEach { logLine ->
                                        val textColor = when {
                                            logLine.contains("成功") || logLine.contains("Strategy 1 成功") || logLine.contains("Strategy 2 成功") || logLine.contains("Strategy 3 成功") -> Color(0xFF10B981)
                                            logLine.contains("失败") || logLine.contains("DENIED") || logLine.contains("异常") -> Color(0xFFEF4444)
                                            logLine.contains("Strategy") || logLine.contains("【Strategy") -> Color(0xFFF59E0B)
                                            logLine.contains("====") -> Color(0xFF3B82F6)
                                            else -> Color(0xFFE5E7EB)
                                        }
                                        Text(
                                            text = logLine,
                                            color = textColor,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            lineHeight = 15.sp,
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (debugLogs.isNotEmpty()) {
                                        val logText = debugLogs.joinToString("\n")
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(logText))
                                        Toast.makeText(localContext, "诊断日志已复制到剪贴板！", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = debugLogs.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = theme.primary,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("📋 复制诊断日志", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = onClearDebugLogs,
                                enabled = debugLogs.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                                border = BorderStroke(1.dp, theme.border),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = theme.textPrimary
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("🧹 清空终端", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
