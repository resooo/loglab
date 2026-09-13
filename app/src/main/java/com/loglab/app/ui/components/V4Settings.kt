package com.loglab.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loglab.app.ui.theme.V4

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * 设置页专用卡片组件
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 参照的分组卡片样式：
 *
 *   ┌ 分组标题（17sp 粗体，卡片外）          ── V4SettingsSectionTitle
 *   │
 *   │  ╭────────────────────────────────╮
 *   │  │ [图标] 主标题            (开关) │   ── V4SettingsCard + V4SettingsRow
 *   │  │        副标题                  │
 *   │  ├────────────────────────────────┤   ← 行间 1px 内缩分割线
 *   │  │ [图标] 主标题            (开关) │
 *   │  │        副标题                  │
 *   │  ╰────────────────────────────────╯
 *
 * 关键视觉点：
 *  - 卡片：白底、20dp 大圆角、极小阴影，行内上下 18dp 留白
 *  - 图标：38dp 圆角方块（10dp 圆角）+ 主色淡底 + 主色线性图标
 *  - 主标题 15sp Medium / 副标题 12.5sp muted，两行间距 4dp
 *  - 右侧开关：iOS 风格药丸（50×30dp），开启主色、关闭灰
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** 分组标题：卡片外，17sp 粗体，大间距 */
@Composable
fun V4SettingsSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        color = V4.Text,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 22.dp, bottom = 10.dp)
    )
}

/** 卡片容器：白底 20dp 圆角，行与行之间自带 1px 内缩分割线 */
@Composable
fun V4SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = V4.Surface,
        shadowElevation = 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

/** 卡片内行间分割线：左右各内缩 20dp，避免顶到圆角 */
@Composable
fun V4SettingsDivider() {
    HorizontalDivider(
        color = V4.Line,
        thickness = 1.dp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp)
    )
}

/**
 * 设置项图标：38dp 圆角方块 + 主色淡底 + 主色字符图标。
 *
 * 用字符图标（emoji / 符号）而不是 material-icons，是为了不引新依赖，
 * 同时保证与 v4 其它组件（⌕ ☰ ⋮ 等）的视觉语言一致。
 */
@Composable
fun V4SettingsIcon(glyph: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(V4.PrimarySoft),
        contentAlignment = Alignment.Center
    ) {
        Text(text = glyph, fontSize = 17.sp, color = V4.Primary)
    }
}

/**
 * 通用设置行：[图标] + 主标题/副标题 + 右侧槽位。
 *
 * @param trailing 右侧内容（开关 / 当前值文字 / 箭头…）；null 表示右侧留空
 * @param onClick  整行点击；为 null 时该行不可点（即只有右侧开关能操作）
 */
@Composable
fun V4SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    glyph: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (glyph != null) V4SettingsIcon(glyph)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = V4.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 12.5.sp,
                    color = V4.Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing?.invoke()
    }
}

/** 右侧当前值文字（如语言「中文」、字号「13sp」） */
@Composable
fun V4SettingsValue(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = V4.Primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.widthIn(max = 120.dp)
    )
}

/**
 * iOS 风格药丸开关：50×30dp 轨道，26dp 圆钮，带滑动与变色动画。
 *
 * 没用 Material3 的 `Switch`——它的尺寸/形状跟设计稿差异较大（轨道更矮、
 * 有描边），这里按设计稿自己画一个更省事，颜色也能直接吃 V4 令牌。
 *
 * @param enabled 传入 false 表示禁用（灰色，仍显示当前状态）
 */
@Composable
fun V4Switch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val trackColor by animateColorAsState(
        targetValue = when {
            !enabled -> V4.DisabledResolved
            checked -> V4.Primary
            else -> V4.SwitchOff
        },
        animationSpec = tween(180),
        label = "v4-switch-track"
    )
    // 圆钮在轨道内左右各留 2dp
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = tween(180),
        label = "v4-switch-thumb"
    )
    Box(
        modifier = modifier
            .size(width = 50.dp, height = 30.dp)
            .clip(CircleShape)
            .background(trackColor)
            .then(
                if (enabled) {
                    Modifier.clickable { onCheckedChange(!checked) }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(26.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

/** 只读的状态胶囊（如「已连接」），用于右侧展示不可切换的状态 */
@Composable
fun V4SettingsBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.14f),
        modifier = modifier
    ) {
        Text(
            text = text,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            color = color,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
