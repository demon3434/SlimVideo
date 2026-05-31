package com.example.slimvideo.data

enum class CompressionPreset(
    val id: String,
    val presetName: String,
    val description: String,
    val targetWidth: Int,
    val targetHeight: Int,
    val targetFps: Int,
    val targetBitrateBps: Long,
    val coefficient: Double
) {
    DAILY_HD(
        id = "DAILY_HD",
        presetName = "日常高清档",
        description = "手机看最划算！既保证了画面清晰，又砍掉了 80% 的臃肿体积。",
        targetWidth = 1920,
        targetHeight = 1080,
        targetFps = 30,
        targetBitrateBps = 4500000L,
        coefficient = 0.573
    ),
    EXTREME_SPACE(
        id = "EXTREME_SPACE",
        presetName = "极致省空间档",
        description = "空间拯救者！画面会有一点点变糊。最适合存网课，20分钟只需300MB。",
        targetWidth = 1920,
        targetHeight = 1080,
        targetFps = 30,
        targetBitrateBps = 2000000L,
        coefficient = 0.268
    ),
    STANDARD_PORTABLE(
        id = "STANDARD_PORTABLE",
        presetName = "标清便携档",
        description = "普通清晰度。画面在大屏发虚但在手机看刚刚好。适合保存纯记录视频。",
        targetWidth = 1280,
        targetHeight = 720,
        targetFps = 25,
        targetBitrateBps = 1200000L,
        coefficient = 0.170
    ),
    LIMIT_PRESERVE(
        id = "LIMIT_PRESERVE",
        presetName = "极限留存档",
        description = "能看清发生了什么就行！有明显颗粒感（类似DVD），适合纯备份用。",
        targetWidth = 854,
        targetHeight = 480,
        targetFps = 24,
        targetBitrateBps = 600000L,
        coefficient = 0.096
    );

    /**
     * Checks whether this preset can be activated for the given source video parameters.
     * Activation logic matches the development specifications:
     * - sourceWidth >= targetWidth (we support both portrait and landscape orientation by checking max dimension)
     * - sourceBitrateBps > targetBitrateBps
     */
    fun isEligible(sourceWidth: Int, sourceHeight: Int, sourceBitrateBps: Long): Boolean {
        if (this == LIMIT_PRESERVE) return true
        val sourceMaxDim = maxOf(sourceWidth, sourceHeight)
        val targetMaxDim = maxOf(targetWidth, targetHeight)
        return sourceMaxDim >= targetMaxDim && sourceBitrateBps > targetBitrateBps
    }

    /**
     * Estimates the compressed file size in Megabytes (MB).
     * Formula: sizeMb = durationSec * coefficient (with 1.8x multiplier for H.264)
     */
    fun estimateSizeMb(durationSec: Long, useH265: Boolean = true): Double {
        val baseSize = durationSec * coefficient
        return if (useH265) baseSize else baseSize * 1.8
    }
}
