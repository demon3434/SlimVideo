package com.example.slimvideo.data

import androidx.compose.ui.graphics.Color

enum class AppTheme(
    val id: String,
    val displayName: String,
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val primary: Color,
    val secondary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val border: Color
) {
    TECH_BLUE(
        id = "TECH_BLUE",
        displayName = "科技深空蓝",
        isDark = true,
        background = Color(0xFF0B0F19),
        surface = Color(0xFF161F30),
        primary = Color(0xFF3B82F6),
        secondary = Color(0xFF10B981),
        textPrimary = Color.White,
        textSecondary = Color(0xFF94A3B8),
        border = Color(0xFF3B82F6).copy(alpha = 0.3f)
    ),
    AURORA_GREEN(
        id = "AURORA_GREEN",
        displayName = "北极极光绿",
        isDark = true,
        background = Color(0xFF071410),
        surface = Color(0xFF0E241F),
        primary = Color(0xFF10B981),
        secondary = Color(0xFF06B6D4),
        textPrimary = Color.White,
        textSecondary = Color(0xFF86EFAC),
        border = Color(0xFF10B981).copy(alpha = 0.3f)
    ),
    LILAC_PURPLE(
        id = "LILAC_PURPLE",
        displayName = "丁香梦幻紫",
        isDark = true,
        background = Color(0xFF130F26),
        surface = Color(0xFF221A44),
        primary = Color(0xFF8B5CF6),
        secondary = Color(0xFFEC4899),
        textPrimary = Color.White,
        textSecondary = Color(0xFFC084FC),
        border = Color(0xFF8B5CF6).copy(alpha = 0.3f)
    ),
    ELEGANT_WHITE(
        id = "ELEGANT_WHITE",
        displayName = "典雅极光白",
        isDark = false,
        background = Color(0xFFF1F5F9), // Light grayish slate
        surface = Color(0xFFFFFFFF),     // Solid pure white
        primary = Color(0xFF2563EB),     // Tech blue
        secondary = Color(0xFF0D9488),   // Teal
        textPrimary = Color(0xFF0F172A),   // Deep navy slate
        textSecondary = Color(0xFF475569), // Slate gray
        border = Color(0xFFCBD5E1)       // Light border gray
    ),
    SAKURA_PINK(
        id = "SAKURA_PINK",
        displayName = "浪漫樱花粉",
        isDark = false,
        background = Color(0xFFFFF1F2), // Soft pink rose water
        surface = Color(0xFFFFFFFF),     // Pure white
        primary = Color(0xFFF43F5E),     // Soft rose red
        secondary = Color(0xFFEC4899),   // Sakura pink
        textPrimary = Color(0xFF4C0519),   // Deep maroon rose
        textSecondary = Color(0xFF9F1239), // Rose red
        border = Color(0xFFFECDD3)       // Soft pink border
    ),
    MINT_GREEN(
        id = "MINT_GREEN",
        displayName = "清新薄荷绿",
        isDark = false,
        background = Color(0xFFF0FDF4), // Soothing mint green water
        surface = Color(0xFFFFFFFF),     // Pure white
        primary = Color(0xFF10B981),     // Emerald green
        secondary = Color(0xFF0D9488),   // Teal green
        textPrimary = Color(0xFF064E3B),   // Forest dark green
        textSecondary = Color(0xFF047857), // Leaf green
        border = Color(0xFFBBF7D0)       // Mint border
    ),
    CREAM_ORANGE(
        id = "CREAM_ORANGE",
        displayName = "温暖奶香橙",
        isDark = false,
        background = Color(0xFFFFF7ED), // Cream orange glow
        surface = Color(0xFFFFFFFF),     // Pure white
        primary = Color(0xFFF97316),     // Warm orange
        secondary = Color(0xFFF59E0B),   // Amber yellow
        textPrimary = Color(0xFF431407),   // Dark chocolate orange
        textSecondary = Color(0xFF7C2D12), // Deep burnt orange
        border = Color(0xFFFFEDD5)       // Cream border
    )
}
