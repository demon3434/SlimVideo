package com.example.slimvideo

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.example.slimvideo.data.CompressionPreset
import com.example.slimvideo.data.AppTheme
import com.example.slimvideo.viewmodel.CompressionState
import com.example.slimvideo.viewmodel.MainViewModel
import com.example.slimvideo.ui.components.*
import com.example.slimvideo.ui.screens.*
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
    val statusText by viewModel.compressionStatusText.collectAsState()
    val state by viewModel.compressionState.collectAsState()
    val useH265 by viewModel.useH265.collectAsState()
    val keepOriginalFps by viewModel.keepOriginalFps.collectAsState()
    val defaultKeepFps by viewModel.defaultKeepFps.collectAsState()
    val theme by viewModel.currentTheme.collectAsState()
    val shouldPromptOverwrite by viewModel.shouldPromptOverwrite.collectAsState()
    val debugLogs by viewModel.debugLogs.collectAsState()
    val tempFilesSize by viewModel.tempFilesSize.collectAsState()

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
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (e: Exception) {
                // ignore
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
            var showCancelConfirm by remember { mutableStateOf(false) }

            LaunchedEffect(showSettings) {
                if (showSettings) {
                    viewModel.calculateTempFilesSize()
                }
            }

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
                        defaultKeepFps = defaultKeepFps,
                        onDefaultKeepFpsChange = { viewModel.setDefaultKeepFps(it) },
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
                        onClearDebugLogs = { viewModel.clearDebugLogs() },
                        tempFilesSize = tempFilesSize,
                        onClearTempFiles = { viewModel.clearTempFiles() }
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
                    HeaderSection(
                        theme = theme,
                        onSettingsClick = { showSettings = true }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(scrollState),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
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

                        AnimatedVisibility(visible = video != null) {
                            Column(horizontalAlignment = Alignment.Start) {
                                FormatSelectionSection(
                                    useH265 = useH265,
                                    theme = theme,
                                    onFormatSelected = { viewModel.setUseH265(it) }
                                )

                                FpsOptionSection(
                                    keepOriginalFps = keepOriginalFps,
                                    theme = theme,
                                    onToggle = { viewModel.setKeepOriginalFps(it) }
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
                                        keepOriginalFps = keepOriginalFps,
                                        theme = theme,
                                        onPresetSelected = { viewModel.selectPreset(it) }
                                    )
                                }

                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }

                        ActionSection(
                            state = state,
                            progress = progress,
                            statusText = statusText,
                            selectedPreset = preset,
                            theme = theme,
                            onStartCompression = { viewModel.startCompression(context) },
                            onReset = { viewModel.resetState() },
                            onCancelCompression = { showCancelConfirm = true }
                        )

                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }

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

            if (showCancelConfirm) {
                AlertDialog(
                    onDismissRequest = { showCancelConfirm = false },
                    title = {
                        Text(
                            text = "确认取消转码？",
                            color = theme.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    },
                    text = {
                        Text(
                            text = "取消转码将停止当前任务，且已经生成的临时视频分段将被完全清理，此操作不可撤销。",
                            color = theme.textSecondary,
                            fontSize = 14.sp
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showCancelConfirm = false
                                viewModel.cancelCompression()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                        ) {
                            Text("确认取消", color = Color.White)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCancelConfirm = false }) {
                            Text("继续转码", color = theme.primary)
                        }
                    },
                    containerColor = theme.surface,
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }
    }
}
