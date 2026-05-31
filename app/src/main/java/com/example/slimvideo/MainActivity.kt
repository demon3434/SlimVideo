package com.example.slimvideo

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.example.slimvideo.data.CompressionPreset
import com.example.slimvideo.data.VideoItem
import com.example.slimvideo.data.AppTheme
import com.example.slimvideo.viewmodel.CompressionState
import com.example.slimvideo.viewmodel.MainViewModel
import java.util.Locale

@UnstableApi
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SlimVideoMainScreen()
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun SlimVideoMainScreen(viewModel: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val context = LocalContext.current
    val video by viewModel.selectedVideo.collectAsState()
    val preset by viewModel.selectedPreset.collectAsState()
    val progress by viewModel.compressionProgress.collectAsState()
    val state by viewModel.compressionState.collectAsState()
    val useH265 by viewModel.useH265.collectAsState()
    val theme by viewModel.currentTheme.collectAsState()
    val shouldPromptOverwrite by viewModel.shouldPromptOverwrite.collectAsState()
    val debugLogs by viewModel.debugLogs.collectAsState()

    val scrollState = rememberScrollState()

    // Request permissions launcher (for post notifications on API 33+)
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
        if (!isGranted) {
            Toast.makeText(context, "警告：若不授予通知权限，转码时将无法在通知栏显示进度！", Toast.LENGTH_LONG).show()
        }
    }

    // Dynamic storage reading permission request based on SDK version
    val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasStoragePermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, storagePermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasStoragePermission = isGranted
        if (!isGranted) {
            Toast.makeText(context, "提示：未授予媒体读取权限，软件将以安全沙盒模式运行，可能无法完美还原相册原始物理路径。", Toast.LENGTH_LONG).show()
        }
    }

    // Trigger permission checks at launch
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!hasStoragePermission) {
            storagePermissionLauncher.launch(storagePermission)
        }
    }

    // System photo picker launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            try {
                // VERY IMPORTANT: Persist Uri permission so background WorkManager can read the file without SecurityException
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (e: Exception) {
                // In some older Android variants, persist permission might throw, but we still proceed
            }
            viewModel.selectVideo(context, uri)
        }
    }

    // Launcher for original video delete request callback
    val deleteRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onDeleteConfirmed(context)
        } else {
            viewModel.onDeleteCancelled()
        }
    }

    // Handle incoming delete pending intent from view model
    LaunchedEffect(state) {
        if (state is CompressionState.AwaitingUserPermission) {
            val sender = (state as CompressionState.AwaitingUserPermission).intentSender
            deleteRequestLauncher.launch(
                androidx.activity.result.IntentSenderRequest.Builder(sender).build()
            )
        }
    }

    val colorScheme = if (theme.isDark) {
        darkColorScheme(
            primary = theme.primary,
            secondary = theme.secondary,
            background = theme.background,
            surface = theme.surface
        )
    } else {
        lightColorScheme(
            primary = theme.primary,
            secondary = theme.secondary,
            background = theme.background,
            surface = theme.surface
        )
    }

    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            var showSettings by remember { mutableStateOf(false) }
            var settingsLevel by remember { mutableStateOf(1) }

            BackHandler(enabled = showSettings) {
                if (settingsLevel == 2 || settingsLevel == 3) {
                    settingsLevel = 1
                } else {
                    showSettings = false
                }
            }

            if (showSettings) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    SettingsScreen(
                        theme = theme,
                        shouldPromptOverwrite = shouldPromptOverwrite,
                        onPromptOverwriteChange = { viewModel.setShouldPromptOverwrite(it) },
                        settingsLevel = settingsLevel,
                        onSettingsLevelChange = { settingsLevel = it },
                        onBackClick = {
                            if (settingsLevel == 2 || settingsLevel == 3) {
                                settingsLevel = 1
                            } else {
                                showSettings = false
                            }
                        },
                        onThemeSelect = { viewModel.setTheme(it) },
                        debugLogs = debugLogs,
                        onClearDebugLogs = { viewModel.clearDebugLogs() }
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 1. Sleek Neon Header with Settings Icon (Fixed at the very top!)
                    HeaderSection(
                        theme = theme,
                        onSettingsClick = { showSettings = true }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Scrollable content area
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(scrollState),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 2. Video Import and Information Card
                        if (video == null) {
                            ImportBox(
                                onClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                    )
                                },
                                isAnalyzing = state is CompressionState.Analyzing,
                                theme = theme
                            )
                        } else {
                            VideoMetadataCard(
                                video = video!!,
                                theme = theme,
                                onSelectAnother = { viewModel.resetState() }
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // 3. Preset selector section (only show if video is chosen)
                        AnimatedVisibility(visible = video != null) {
                            Column(horizontalAlignment = Alignment.Start) {
                                FormatSelectionSection(
                                    useH265 = useH265,
                                    theme = theme,
                                    onFormatSelected = { viewModel.setUseH265(it) }
                                )

                                Text(
                                    text = "⚙️ 选择瘦身档位 (情景标签化)",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = theme.textPrimary,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )

                                video?.let { v ->
                                    PresetSelectionGrid(
                                        video = v,
                                        selectedPreset = preset,
                                        useH265 = useH265,
                                        theme = theme,
                                        onPresetSelected = { viewModel.selectPreset(it) }
                                    )
                                }

                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }

                        // 4. Processing Progress and Dynamic Buttons
                        ActionSection(
                            state = state,
                            progress = progress,
                            selectedPreset = preset,
                            selectedVideo = video,
                            theme = theme,
                            onStartCompression = { viewModel.startCompression(context) },
                            onReset = { viewModel.resetState() }
                        )

                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }

            // Custom Dialog for "是否原位覆盖原视频"
            if (state is CompressionState.AwaitingOverwriteConfirmation) {
                val compressedSize by viewModel.compressedVideoSize.collectAsState()
                val originalSizeMb = video?.size?.toDouble()?.div(1024 * 1024) ?: 0.0
                val compressedSizeMb = compressedSize.toDouble() / (1024 * 1024)
                val savingsPercent = ((originalSizeMb - compressedSizeMb) / originalSizeMb * 100).coerceAtLeast(0.0)

                AlertDialog(
                    onDismissRequest = { viewModel.resetState() },
                    title = {
                        Text(
                            text = "是否原位覆盖原视频？",
                            color = theme.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    },
                    text = {
                        Text(
                            text = String.format(
                                Locale.getDefault(),
                                "转码已成功完成！\n\n原片大小: %.1f MB\n压缩后大小: %.1f MB\n已节省空间: %.0f%%\n\n确认要进行原位覆盖替换以释放手机空间吗？此操作不可逆。",
                                originalSizeMb,
                                compressedSizeMb,
                                savingsPercent
                            ),
                            color = theme.textSecondary,
                            fontSize = 14.sp
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = { viewModel.confirmOverwrite(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = theme.secondary)
                        ) {
                            Text("确认覆盖", color = Color.White)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.resetState() }) {
                            Text("保留原视频(取消)", color = theme.primary)
                        }
                    },
                    containerColor = theme.surface,
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }
    }
}

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
        // Centered Chinese Title
        Text(
            text = "胶卷瘦身",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = theme.textPrimary,
            textAlign = TextAlign.Center
        )

        // Top Right Gear Icon Button
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
fun SettingsScreen(
    theme: AppTheme,
    shouldPromptOverwrite: Boolean,
    onPromptOverwriteChange: (Boolean) -> Unit,
    settingsLevel: Int,
    onSettingsLevelChange: (Int) -> Unit,
    onBackClick: () -> Unit,
    onThemeSelect: (AppTheme) -> Unit,
    debugLogs: List<String>,
    onClearDebugLogs: () -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.background)
            .padding(16.dp)
    ) {
        // Fixed Settings Header (Always stays at the top!)
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

        // Scrollable content area underneath
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
        ) {
            if (settingsLevel == 1) {
                // Level 1: Main Settings Menu
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

                // Storage & Overwrite Settings Card
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
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Diagnostic Logs Menu Entry Card
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
                // Level 2: Theme Sub-menu List
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

                        // List of Themes
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
                // Level 3: Visual Diagnostic Terminal (Nested sub-menu logs page!)
                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                val localContext = androidx.compose.ui.platform.LocalContext.current

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

                        // Retro Terminal Box (Taller height here as it has its own dedicated submenu page!)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(380.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF0D0E15)) // Deep Jet Black
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
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                            } else {
                                val terminalScrollState = rememberScrollState()
                                // Auto scroll to bottom when new logs arrive
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
                                            logLine.contains("成功") || logLine.contains("Strategy 1 成功") || logLine.contains("Strategy 2 成功") || logLine.contains("Strategy 3 成功") -> Color(0xFF10B981) // Emerald Green
                                            logLine.contains("失败") || logLine.contains("DENIED") || logLine.contains("异常") -> Color(0xFFEF4444) // Bright Red
                                            logLine.contains("Strategy") || logLine.contains("【Strategy") -> Color(0xFFF59E0B) // Amber
                                            logLine.contains("====") -> Color(0xFF3B82F6) // Electric Blue
                                            else -> Color(0xFFE5E7EB) // Off-white
                                        }
                                        Text(
                                            text = logLine,
                                            color = textColor,
                                            fontSize = 11.sp,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
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
                                        android.widget.Toast.makeText(localContext, "诊断日志已复制到剪贴板！", android.widget.Toast.LENGTH_SHORT).show()
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
    val sizeMb = video.size.toDouble() / (1024 * 1024)
    val sizeText = String.format(Locale.getDefault(), "%.1f MB", sizeMb)
    val bitrateText = String.format(Locale.getDefault(), "%.1f Mbps", video.bitrate.toDouble() / 1_000_000)

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

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetadataColumn("体积大小", sizeText, theme)
            MetadataColumn("播放时长", durationText, theme)
            MetadataColumn("原始分辨率", "${video.width} × ${video.height}", theme)
            MetadataColumn("编码码率", bitrateText, theme)
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
                // Radio button representation
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
                            val originalSizeMb = video.size.toDouble() / (1024 * 1024)
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
                        Text(
                            text = String.format(
                                Locale.getDefault(),
                                "目标参数: %s / %dp / %dfps / %.1fMbps • 预估体积: %.1f MB",
                                if (useH265) "H.265" else "H.264",
                                preset.targetHeight,
                                preset.targetFps,
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
    selectedPreset: CompressionPreset?,
    selectedVideo: VideoItem?,
    theme: AppTheme,
    onStartCompression: () -> Unit,
    onReset: () -> Unit
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
                    text = "正在利用 MediaCodec 硬件加速转码...",
                    fontSize = 14.sp,
                    color = theme.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(12.dp))

                LinearProgressIndicator(
                    progress = progress.toFloat() / 100f,
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
                Text(
                    text = "原视频已被瘦身版原位同名覆盖，相册已同步刷新。已为您释放大量手机空间！",
                    fontSize = 13.sp,
                    color = theme.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 10.dp)
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
            // Idle state
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
            // H.265 Card
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

            // H.264 Card
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
