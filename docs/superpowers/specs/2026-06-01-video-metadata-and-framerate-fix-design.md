# 设计规约：视频高级元数据展示、60帧压缩降帧修复及原始帧率保留控制

本文档详细描述了 SlimVideo（胶卷瘦身）应用中核心功能的改进与扩展方案：
1. **视频高级元数据展示**：在原有一行元数据的基础上，另起一行展示视频帧率、视频编码、色彩位深、音频码率、声道数。
2. **60帧视频降帧修复**：解决 60 帧视频在选用 30 帧预设（如日常高清档）压缩覆盖后，在相册查看仍然是 60 帧的 Bug。
3. **保留原始帧率控制 (Capsule Switch & Settings Integration)**：
   * **主界面控制**：在“选择编码标准”下方新增“保留原始视频的帧率”胶囊开关。
   * **动态响应**：开启时，预设档位的描述、预估文件体积及目标帧率等，在 UI 上同步刷新为原始视频帧率，并且在后台执行压缩转码时，必须按照原始视频的帧率参数进行（不应用丢帧特效 `FrameDropEffect`）。
   * **齿轮设置深度整合**：在设置菜单“个性化”卡片下方新增“默认保留原始视频的帧率”胶囊开关（默认为关闭）。导入新视频时，主界面胶囊开关的初始值需与该设置选项同步。

---

## 1. 需求与设计决策

### 1.1 高级元数据展示 (UI & 提取)
* **展示形式**：在 `VideoMetadataCard` 内部，第一行元数据下方新增一条细分割线（Divider），然后以 `Row` 展示第二行 5 个参数（帧率、视频编码、色彩位深、音频码率、声道）。
* **提取路线**：为确保跨版本 Android 系统的 100% 准确性，采用 **`MediaExtractor` 与 `MediaFormat` 双流提取** 方案，并辅以 `MediaMetadataRetriever` 进行帧率计算兜底。

### 1.2 60 帧视频降帧与保留原始帧率控制
* **降帧原理与修复**：
  * Android 硬件编码器本身不具备主动丢帧重采样的能力。因此，我们在 Media3 Transformer 中应用丢帧视频特效 `FrameDropEffect.createDefaultFrameDropEffect(targetFps.toFloat())`，该特效会拦截帧并在图像处理阶段丢弃多余的帧。
  * 同时在 `VideoEncoderSettings.Builder` 中显式指定 `.setFrameRate(targetFps)`，用以优化编码器的比特率分配及速率控制算法。
* **保留原始帧率状态切换**：
  * **主界面胶囊开关 (KeepOriginalFps)**：默认初始状态为“关闭”。
  * **关闭状态 (默认)**：保留 `FrameDropEffect`，将帧率压降至预设目标帧率（如 30fps/25fps/24fps），且 UI 预设卡片中显示预设帧率（如 `30fps`）。
  * **开启状态**：**不添加 `FrameDropEffect` 丢帧特效**（确保画面流畅度完整保留），且把 `VideoEncoderSettings.Builder` 中的帧率参数设为原始视频的 `video.fps.toInt()`。同时，UI 预设描述中的帧率和压缩参数同步显示为原始视频的帧率。
* **设置项默认初始化逻辑**：
  * 在 App 设置页的“个性化”下方，新增“默认保留原始视频的帧率”全局开关，状态保存在 SharedPreferences 中，键名为 `"default_keep_fps"`。
  * 当用户在主界面导入新视频触发 `selectVideo` 时，主页面的 `keepOriginalFps` 开关状态将根据设置页中这一全局属性自动进行初始化。

---

## 2. 接口与数据流变动

### 2.1 数据模型变更

修改 [VideoItem.kt](file:///e:/home/lyc/Documents/code/SlimVideo/app/src/main/java/com/example/slimvideo/data/VideoItem.kt)，为 `VideoItem` 增加 5 个参数字段：

```kotlin
data class VideoItem(
    val uri: Uri,
    val displayName: String,
    val size: Long,          // 字节大小
    val durationMs: Long,    // 毫秒时长
    val width: Int,
    val height: Int,
    val bitrate: Long,       // 整体比特率 (bps)
    val path: String?,
    // 新增字段
    val fps: Double,         // 帧率，如 60.0
    val codec: String,       // 编码，如 "H.264" / "H.265"
    val bitDepth: Int,       // 色彩位深，如 8 / 10
    val audioBitrate: Long,  // 音频码率 (bps)
    val audioChannels: Int   // 声道数，如 2
)
```

### 2.2 状态流设计与持久化 (ViewModel 侧)

在 [MainViewModel.kt](file:///e:/home/lyc/Documents/code/SlimVideo/app/src/main/java/com/example/slimvideo/viewmodel/MainViewModel.kt) 中引入以下新状态流及持久化管理：

```kotlin
// 1. 设置菜单中的“默认保留原始帧率”全局配置
private val _defaultKeepFps = MutableStateFlow(false)
val defaultKeepFps: StateFlow<Boolean> = _defaultKeepFps.asStateFlow()

// 2. 主界面上的“保留原始视频的帧率”当前配置状态
private val _keepOriginalFps = MutableStateFlow(false)
val keepOriginalFps: StateFlow<Boolean> = _keepOriginalFps.asStateFlow()

init {
    // 初始化时从 SharedPreferences 加载默认保留原始帧率的全局开关值
    val savedDefaultKeepFps = sharedPreferences.getBoolean("default_keep_fps", false)
    _defaultKeepFps.value = savedDefaultKeepFps
    _keepOriginalFps.value = savedDefaultKeepFps
}

// 供设置页调用的 Setter
fun setDefaultKeepFps(value: Boolean) {
    _defaultKeepFps.value = value
    sharedPreferences.edit().putBoolean("default_keep_fps", value).apply()
}

// 供主页调用的 Setter
fun setKeepOriginalFps(value: Boolean) {
    _keepOriginalFps.value = value
}
```

并且在 `selectVideo` 结束并解析出 `VideoItem` 实例后，将主页的配置状态根据默认配置进行重置：
```kotlin
_keepOriginalFps.value = _defaultKeepFps.value
```

在 `startCompression` 时，将该状态作为输入数据传入后台 Worker：
```kotlin
val inputData = Data.Builder()
    .putString(VideoCompressionWorker.KEY_VIDEO_URI, video.uri.toString())
    .putString(VideoCompressionWorker.KEY_PRESET_ID, preset.id)
    .putBoolean(VideoCompressionWorker.KEY_USE_H265, _useH265.value)
    .putBoolean(VideoCompressionWorker.KEY_KEEP_ORIGINAL_FPS, _keepOriginalFps.value) // 传递保留原始帧率配置
    .build()
```

### 2.3 UI 呈现变更 (MainActivity 侧)

1. **元数据面板双行显示**：
   在 `VideoMetadataCard` 中，将“编码码率”标题替换为“视频码率”。引入细分割线 `Divider`，并以紧凑的 `MetadataColumn` 另起一行，以 Options A 结构展示第二行高级参数：帧率、视频编码、色彩位深、音频码率、声道。

2. **新增“保留原始帧率”胶囊开关组件**：
   在 `FormatSelectionSection`（选择编码标准）下方，新增一个精致的胶囊切换条，其设计语言遵循原有的 App 主题色：
   ```kotlin
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
               // 选项：降帧至预设档位 (默认选项)
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
                       colors = RadioButtonDefaults.colors(selectedColor = theme.secondary)
                   )
                   Spacer(modifier = Modifier.width(6.dp))
                   Column {
                       Text("智能降帧", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = theme.textPrimary)
                       Text("省空间 • 推荐", fontSize = 10.sp, color = theme.secondary)
                   }
               }

               // 选项：保留原始帧率
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
                       colors = RadioButtonDefaults.colors(selectedColor = theme.secondary)
                   )
                   Spacer(modifier = Modifier.width(6.dp))
                   Column {
                       Text("保留原帧率", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = theme.textPrimary)
                       Text("极度丝滑", fontSize = 10.sp, color = theme.textSecondary)
                   }
               }
           }
       }
   }
   ```

3. **预设列表的动态刷新**：
   在 `PresetSelectionGrid` 渲染每个预设档位底部的“目标参数”标签时：
   * 若 `keepOriginalFps == true`，将描述文本和目标帧率设为原始视频的帧率（即 `video.fps.toInt()`）。
   * 否则，正常显示预设内部的目标帧率（即 `preset.targetFps`）。

4. **设置面板新增默认保留原帧率开关**：
   在 `SettingsScreen` 的“个性化” Card 之下，添加一个独立的圆角配置框：
   ```kotlin
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
                   Text("默认保留原始视频帧率", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = theme.textPrimary)
                   Text("开启时，导入视频将默认选择“保留原帧率”；关闭时，默认采用“智能降帧”以最大限度压缩文件体积。", fontSize = 12.sp, color = theme.textSecondary, modifier = Modifier.padding(top = 4.dp, end = 8.dp))
               }
               Switch(
                   checked = defaultKeepFps,
                   onCheckedChange = onDefaultKeepFpsChange,
                   colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = theme.secondary)
               )
           }
       }
   }
   ```

### 2.4 视频转码降帧逻辑深度兼容变动

修改 [VideoCompressionWorker.kt](file:///e:/home/lyc/Documents/code/SlimVideo/app/src/main/java/com/example/slimvideo/worker/VideoCompressionWorker.kt)：

1. **常数定义**：
   ```kotlin
   const val KEY_KEEP_ORIGINAL_FPS = "INPUT_KEEP_ORIGINAL_FPS"
   ```
2. **提取帧率与配置参数**：
   * 首先从 inputData 中读取 `keepOriginalFps` 配置：
     ```kotlin
     val keepOriginalFps = inputData.getBoolean(KEY_KEEP_ORIGINAL_FPS, false)
     ```
   * 接着在利用 `MediaMetadataRetriever` 获取源视频长宽的同时，顺便提取原视频的实际帧率，用作没有应用丢帧时告知编码器的参数：
     ```kotlin
     var originalFps = 30
     try {
         retriever.setDataSource(context, uri)
         // 尝试直接获取源帧率，若版本不支持或读出为空，则提取帧数利用时长进行计算
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
     } catch (e: Exception) {
         android.util.Log.e("VideoCompressionWorker", "Failed to retrieve original fps", e)
     }
     ```
3. **动态应用丢帧策略与编码器帧率**：
   * **目标帧率计算**：
     ```kotlin
     val finalFps = if (keepOriginalFps) originalFps else preset.targetFps
     ```
   * **编码器配置**：
     ```kotlin
     val videoEncoderSettings = VideoEncoderSettings.Builder()
         .setBitrate(targetBitrate)
         .setFrameRate(finalFps) // 使用 finalFps 告知硬件编码器
         .build()
     ```
   * **特效链路组装 (Attempt 1)**：
     ```kotlin
     val presentation = Presentation.createForWidthAndHeight(
         finalWidth,
         finalHeight,
         Presentation.LAYOUT_SCALE_TO_FIT
     )
     
     // 仅在 keepOriginalFps == false 时添加 FrameDropEffect
     val videoEffectsList = mutableListOf<Effect>(presentation)
     if (!keepOriginalFps) {
         val frameDropEffect = FrameDropEffect.createDefaultFrameDropEffect(preset.targetFps.toFloat())
         videoEffectsList.add(frameDropEffect)
     }
     
     val effects = Effects(
         emptyList(),
         videoEffectsList
     )
     ```

---

## 3. 验证方案

### 3.1 单元测试与仪器化测试
* 运行打包脚本或本地调试，观察 `selectVideo` 在获取具有 60fps 帧率、HEVC 10-bit、双声道音轨等特性的原始测试视频后，调试面板 (Settings -> 路径解析诊断日志) 是否完整无误地输出各项高级数据。

### 3.2 手动测试用例
1. **测试用例 1：元数据完整性校验**
   * **动作**：导入一个包含音频轨道、1080p@60fps 的 HDR H.265 原生视频。
   * **预期结果**：元数据面板第二行正确显示：`60 fps`、`H.265`、`10 bit`、`128 kbps` (视音频具体流信息而定)、`双声道`。
2. **测试用例 2：降帧有效性校验 (开关关闭)**
   * **动作**：导入一个 60 帧视频，主界面“保留原始帧率”开关保持 **关闭**，选中 **“日常高清档”** (预设目标 30 帧)，开始压缩转码并确认原位覆盖。
   * **预期结果**：转码覆盖完成后，打开手机系统相册，查看该视频的信息，帧率应已成功降为 `30 fps`，且视频画面播放流畅无跳帧、声画完全同步。
3. **测试用例 3：保留原始帧率校验 (开关开启)**
   * **动作**：导入一个 60 帧视频，主界面将“保留原始帧率”开关切换为 **开启**。此时观察下方 4 档预设的目标参数文案，帧率应已刷新并显示为 `60fps`。选中 **“日常高清档”** (预设目标 30 帧)，开始压缩转码并确认原位覆盖。
   * **预期结果**：转码覆盖完成后，打开手机系统相册，查看该视频的信息，帧率仍然完美保留为 `60 fps`。
4. **测试用例 4：设置默认状态切换联动校验**
   * **动作**：进入齿轮设置菜单，在“个性化”下方的“视频帧率默认配置”中将“默认保留原始视频帧率”全局开关切换为 **开启**。返回主界面导入新视频。
   * **预期结果**：新视频加载完成后，主界面的“保留原始视频帧率”胶囊开关应初始化处于 **开启** 状态。
