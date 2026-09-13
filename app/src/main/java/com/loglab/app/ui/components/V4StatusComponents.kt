package com.loglab.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loglab.app.ui.theme.V4

// ══════════════════════════════ 居中状态卡 ══════════════════════════════

/**
 * 居中状态浮层容器 `.center-status{inset:0;center;pointer-events:none;z-index:2}`。
 *
 * 日志区照常铺满，这层只负责把卡片摆到正中间；`pointerEvents=none` 的语义用
 * 「容器不消费点击」来还原——空白处点击会穿透到下面的日志列表，只有卡片本身
 * （`.cs-card`）能点。
 */
@Composable
fun V4CenterStatus(
    modifier: Modifier = Modifier,
    /** 卡片外区域是否拦截点击；false 时空白点击穿透到日志区 */
    blockOutside: Boolean = false,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (blockOutside) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
                    .then(Modifier)
            )
        }
        content()
    }
}

/** 卡片外壳 `.cs-card`：16dp 圆角白底 + 柔和投影 */
@Composable
fun V4StatusCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = V4.Surface,
        shadowElevation = 6.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) { content() }
    }
}

/**
 * 检测中 spinner `.spin{width:22px;height:22px;border:2.5px}`。
 * 用 rotate 无限动画模拟 CSS 的旋转边框。
 */
@Composable
fun V4Spinner(
    modifier: Modifier = Modifier,
    color: Color = V4.Primary,
    trackColor: Color = V4.Surface2,
    sizeDp: Int = 22
) {
    val transition = rememberInfiniteTransition(label = "v4-spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "v4-spin-angle"
    )
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .rotate(angle)
            .clip(CircleShape)
            .background(trackColor)
    ) {
        // 上半弧用主色块 + 圆角模拟 border-top-color 的观感
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height((sizeDp / 2).dp)
                .clip(RoundedCornerShape(topStart = (sizeDp / 2).dp, topEnd = (sizeDp / 2).dp))
                .background(color)
        )
        // 中心挖白，形成环形
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size((sizeDp - 5).dp)
                .clip(CircleShape)
                .background(V4.Surface)
        )
    }
}

/** 卡片标题 `.cs-title{font-size:13.5px;font-weight:500}` */
@Composable
fun V4StatusTitle(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = V4.Text
) {
    Text(
        text = text,
        fontSize = 13.5.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        textAlign = TextAlign.Center,
        modifier = modifier
    )
}

/** 卡片副标题 `.cs-sub{font-size:11.5px;color:var(--muted)}` */
@Composable
fun V4StatusSubtitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 11.5.sp,
        color = V4.Muted,
        textAlign = TextAlign.Center,
        modifier = modifier
    )
}

/** 卡片动作行 `.cs-actions{display:flex;gap:6px;margin-top:2px}` */
@Composable
fun V4StatusActions(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/**
 * 卡片文字按钮 `.cs-btn{font-size:12.5px;font-weight:500;padding:6px 12px;border-radius:16px}`。
 * `ghost = true` 对应 `.cs-btn.ghost`（次要灰字）。
 */
@Composable
fun V4StatusButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    ghost: Boolean = false,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        modifier = modifier
    ) {
        Text(
            text = text,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = when {
                !enabled -> V4.DisabledTextResolved
                ghost -> V4.Muted
                else -> V4.Primary
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

// ══════════════════════════════ ⋮ 溢出菜单 ══════════════════════════════

/**
 * ⋮ 菜单容器 `.menu{border-radius:14px;min-width:176px;padding:6px;z-index:12}`。
 *
 * 调用方负责把它摆到顶栏右下（高频行下方），一般用 `Box` + `Modifier.align(TopEnd)`。
 */
@Composable
fun V4OverflowMenu(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = V4.Surface,
        shadowElevation = 10.dp,
        modifier = modifier.widthIn(min = 176.dp)
    ) {
        Column(modifier = Modifier.padding(6.dp), content = content)
    }
}

/**
 * 菜单项 `.mitem{padding:10px 12px;border-radius:10px;font-size:13px}`。
 *
 * @param icon  左侧图标字符 `.mitem .mi`（18dp 宽、muted 色）
 * @param mark  右侧状态文字 `.mitem .mk`（如「关」「main·system」）
 */
@Composable
fun V4MenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    mark: String? = null,
    danger: Boolean = false,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (icon != null) {
                Text(
                    text = icon,
                    fontSize = 15.sp,
                    color = if (enabled) V4.Muted else V4.DisabledTextResolved,
                    modifier = Modifier.widthIn(min = 18.dp),
                    textAlign = TextAlign.Center
                )
            }
            Text(
                text = text,
                fontSize = 13.sp,
                color = when {
                    !enabled -> V4.DisabledTextResolved
                    danger -> V4.Error
                    else -> V4.Text
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (mark != null) {
                Text(
                    text = mark,
                    fontSize = 11.sp,
                    color = V4.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                )
            }
        }
    }
}

/** 菜单分隔线 `.msep{height:1px;background:var(--line);margin:5px 8px}` */
@Composable
fun V4MenuSeparator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .height(1.dp)
            .background(V4.Line)
    )
}
