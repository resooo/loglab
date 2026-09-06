package com.loglab.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.loglab.app.data.model.AppSettings
import com.loglab.app.data.repository.SettingsRepository

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
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
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
