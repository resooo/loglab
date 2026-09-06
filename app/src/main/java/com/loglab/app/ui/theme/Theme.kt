package com.loglab.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.loglab.app.data.model.AppSettings
import com.loglab.app.data.repository.SettingsRepository

/** 从 Compose context 向上找宿主 Activity（主题切换时要同步系统栏外观） */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 日志级别配色（终端风格，深浅色通用） */
object LogColors {
    val Verbose = Color(0xFF8A8F98)
    val Debug = Color(0xFF4A9EFF)
    val Info = Color(0xFF3DDC84)
    val Warn = Color(0xFFFFB74D)
    val Error = Color(0xFFFF6B6B)
    val Fatal = Color(0xFFFF4D6D)

    fun forPriority(priority: Char): Color = when (priority) {
        'V' -> Verbose
        'D' -> Debug
        'I' -> Info
        'W' -> Warn
        'E' -> Error
        'F' -> Fatal
        else -> Verbose
    }
}

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

val MonospaceFont = FontFamily.Monospace

/** 日志正文使用的等宽排版，字号随设置缩放 */
fun logTextStyle(fontSizeSp: Int, bold: Boolean = false) = TextStyle(
    fontFamily = MonospaceFont,
    fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
    fontSize = fontSizeSp.sp,
    lineHeight = (fontSizeSp * 1.45f).sp
)

@Composable
fun LogLabTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    // ★ 修复深色主题下状态栏"看不见"：enableEdgeToEdge() 只在 onCreate 时按系统
    // 深浅色决定状态栏图标颜色，App 内运行时切换深色主题不会跟随——系统浅色 +
    // App 深色时状态栏变成黑底黑图标。这里在主题每次重组时同步图标外观：
    // 深色主题 → 浅色图标（isAppearanceLight*=false），浅色主题 → 深色图标。
    if (!view.isInEditMode) {
        SideEffect {
            val window = context.findActivity()?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}

/** 按用户设置（深色 / 动态配色）包装主题 */
@Composable
fun AppThemeWrapper(
    settings: SettingsRepository,
    content: @Composable () -> Unit
) {
    val appSettings by settings.settings.collectAsState(initial = AppSettings())
    LogLabTheme(
        darkTheme = appSettings.darkTheme,
        dynamicColor = appSettings.dynamicColor,
        content = content
    )
}
