package com.loglab.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
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
    val Info = Color(0xFF1FA95E)
    val Warn = Color(0xFFD98A16)
    val Error = Color(0xFFE5484D)
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

/**
 * v4 布局设计令牌 —— 与《LogLab-布局方案v4-设计稿》里的 CSS 变量一一对应。
 *
 * 这些值刻意不走 MaterialTheme.colorScheme：v4 的灰底、胶囊、浮层需要精确复刻
 * 设计稿（--bg/--surface/--surface-2/--primary/--line/...），动态取色会把它们
 * 染成壁纸色，反而偏离设计。深浅色两套值分开维护。
 */
object V4 {
    // —— 浅色（设计稿原始值）——
    val BgLight = Color(0xFFF7F7F9)
    val SurfaceLight = Color(0xFFFFFFFF)
    val Surface2Light = Color(0xFFF0F1F4)
    val TextLight = Color(0xFF1A1C1E)
    val MutedLight = Color(0xFF8A8F99)
    val PrimaryLight = Color(0xFF3B6EF6)
    val LineLight = Color(0xFFE3E5E9)

    // —— 深色（同构映射，保持对比层级）——
    val BgDark = Color(0xFF131417)
    val SurfaceDark = Color(0xFF1D1F24)
    val Surface2Dark = Color(0xFF2A2D33)
    val TextDark = Color(0xFFE9EAEC)
    val MutedDark = Color(0xFF8A8F99)
    val PrimaryDark = Color(0xFF6B93FF)
    val LineDark = Color(0xFF2E3138)

    // —— 语义色（深浅通用，与设计稿一致）——
    val Error = Color(0xFFE5484D)
    val Green = Color(0xFF3DDC84)
    val Warn = Color(0xFFFFB74D)

    // —— 非语义固定色 ——
    /** 高频行图标默认色 `.hbtn{color:#5A6070}` */
    val IconIdle = Color(0xFF5A6070)
    /** `.hbtn.on` 高亮底 */
    val PrimarySoftLight = Color(0xFFE8EFFF)
    val PrimarySoftDark = Color(0xFF23304D)
    /** FAB 禁用态 */
    val Disabled = Color(0xFFD9DBE1)
    val DisabledText = Color(0xFF969BA5)
    /** 开关关闭态轨道（比 Disabled 深一点，避免和禁用态混淆） */
    val SwitchOffLight = Color(0xFFD1D3D9)
    val SwitchOffDark = Color(0xFF3A3E46)
    /** 次级小标签默认色（选择器行尾包名等） */
    val Subtle = Color(0xFFC6CAD2)

    /**
     * ★ 深浅色判定不读 isSystemInDarkTheme()，而是读 App 自己的深色开关。
     *
     * 这里踩过坑：原来用 isSystemInDarkTheme()，但 App 允许在设置页单独开深色——
     * 系统浅色 + App 深色时，MaterialTheme 走了深色 colorScheme（背景是深的），
     * 而这些令牌却返回浅色值（Text = #1A1C1E 深灰字）→ 深灰字压深色底，
     * 顶栏标题、分组标题、空态提示全部看不见。
     *
     * 用 CompositionLocal 显式传入当前生效的深浅，保证令牌与 MaterialTheme 永远一致。
     */
    internal val LocalV4Dark = staticCompositionLocalOf { false }

    private val dark: Boolean @Composable get() = LocalV4Dark.current

    val Bg: Color @Composable get() = if (dark) BgDark else BgLight
    val Surface: Color @Composable get() = if (dark) SurfaceDark else SurfaceLight
    val Surface2: Color @Composable get() = if (dark) Surface2Dark else Surface2Light
    val Text: Color @Composable get() = if (dark) TextDark else TextLight
    val Muted: Color @Composable get() = if (dark) MutedDark else MutedLight
    val Primary: Color @Composable get() = if (dark) PrimaryDark else PrimaryLight
    val Line: Color @Composable get() = if (dark) LineDark else LineLight
    val PrimarySoft: Color @Composable get() = if (dark) PrimarySoftDark else PrimarySoftLight
    val SwitchOff: Color @Composable get() = if (dark) SwitchOffDark else SwitchOffLight

    /**
     * 高频行图标默认色 `.hbtn{color:#5A6070}` —— 设计稿只给了浅色值。
     * 深色下原样使用会在深底上"糊"掉（对比度过低），所以深色单独提亮。
     */
    val IconIdleResolved: Color @Composable get() = if (dark) Color(0xFFB9BDC6) else IconIdle

    /** 禁用态也分深浅：浅色的 #D9DBE1 在深底上过亮，会像"可用按钮" */
    val DisabledResolved: Color @Composable get() = if (dark) Color(0xFF3A3E46) else Disabled
    val DisabledTextResolved: Color @Composable get() = if (dark) Color(0xFF6B7280) else DisabledText

    /** 选择器行尾包名等次级小标签 */
    val SubtleResolved: Color @Composable get() = if (dark) Color(0xFF8A8F99) else Subtle
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
        content = {
            // ★ 把「当前是否深色」显式下发给 V4 令牌：令牌与 colorScheme 必须同源，
            //   否则会出现「深色背景 + 深色文字」这种看不见的組合。
            CompositionLocalProvider(V4.LocalV4Dark provides darkTheme) {
                content()
            }
        }
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
