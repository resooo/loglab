package com.loglab.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.loglab.app.ui.theme.V4

/**
 * ★ 页面重新回到前台时执行 [onResume]，用于刷新「离开期间后台写入的数据」。
 *
 * 崩溃监控由前台服务在 App 退到后台时继续跑：用户切出去闪退目标应用、
 * 再切回 LogLab 时，界面没有重组触发点，新捕获的崩溃就停留在 store 里不显示
 * ——表现就是「明明监控到了，列表却是空的，只有切到 7 天历史才看得到」。
 *
 * 这里只在 RESUMED 时刷新，不轮询、不打扰；[onResume] 里若做的是状态写入，
 * 记得在调用方判重，避免每次切回都重建列表。
 */
@Composable
fun RefreshOnResume(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * v4 布局公共组件
 * ═══════════════════════════════════════════════════════════════════════════
 * 严格对应《LogLab-布局方案v4-设计稿》里的 CSS 类，尺寸/圆角/字号逐条对齐：
 *
 *   .topbar{padding:2px 14px 6px}          → V4TopBar
 *   .sdot{width:7px;height:7px}            → V4StatusDot
 *   .hbar{padding:0 12px 8px;gap:6px}      → V4QuickActionBar
 *   .proc{height:34px;border-radius:17px}  → V4ProcessChip
 *   .hbtn{width:34px;height:34px}          → V4RoundIconButton
 *   .spacer{flex:1}                        → V4BarSpacer
 *   .fab{56dp;right:14px;bottom:14px}      → V4CaptureFab
 *   .cs-card{border-radius:16px;padding:16px 22px} → V4CenterStatusCard
 *   .menu{border-radius:14px;min-width:176px}      → V4OverflowMenu
 *   .stats{font-size:11.5px}               → V4StatsLine
 *   .sheet{border-radius:22px 22px 0 0;padding:10px 14px 18px} → V4SheetHeader / V4SheetGrip
 *   .field{border-radius:14px;padding:12px 14px}   → V4Field
 *   .opt{padding:7px 13px;border-radius:16px}      → V4OptChip
 *   .sheet-acts / .sbtn{height:44px;border-radius:22px} → V4SheetActions / V4SheetButton
 *   .tagline{font-size:11.5px}             → V4Tagline
 * ═══════════════════════════════════════════════════════════════════════════
 */

// ══════════════════════════════ 顶栏 ══════════════════════════════

/**
 * v4 极简顶栏：只有「标题 + 状态点」。
 * 状态点跟标题走，点它进连接页；顶栏不再承担任何动作按钮。
 *
 * ★ 状态栏避让：宿主 Scaffold 用的是 `contentWindowInsets = WindowInsets(0,0,0,0)`
 * （边界到边界模式），原来靠 Material3 `TopAppBar` 自带的 windowInsets 吃掉状态栏，
 * 换成自定义顶栏后必须自己消费——否则标题会顶到状态栏图标下面。
 * 这里按设计稿的 2dp 上内边距 + 状态栏高度叠加，视觉上仍是「顶栏贴顶」。
 *
 * @param onStatusClick 点击状态点的回调（一般跳连接页）；传 null 则点不动
 * @param applyStatusBarInset 二级页若已被外层处理过 inset，可传 false 关掉
 */
@Composable
fun V4TopBar(
    title: String,
    modifier: Modifier = Modifier,
    statusColor: Color = V4.Green,
    onStatusClick: (() -> Unit)? = null,
    statusEnabled: Boolean = true,
    applyStatusBarInset: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (applyStatusBarInset) {
                    Modifier.windowInsetsPadding(WindowInsets.statusBars)
                } else {
                    Modifier
                }
            )
            .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = V4.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        V4StatusDot(
            color = if (statusEnabled) statusColor else V4.Muted,
            onClick = onStatusClick
        )
    }
}

/**
 * 状态点 `.sdot{width:7px;height:7px;border-radius:50%}`。
 * 绿=已连接 / 橙=检测中 / 红=异常；可点击进连接页。
 */
@Composable
fun V4StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    )
}

// ══════════════════════════════ 高频行 ══════════════════════════════

/**
 * 高频按钮行容器 `.hbar{padding:0 12px 8px;gap:6px;overflow:hidden}`。
 * 行内固定为：进程胶囊 · 若干 34dp 圆按钮 · spacer · ⋮
 */
@Composable
fun V4QuickActionBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

/** `.spacer{flex:1;min-width:0}` —— 把低频的 ⋮ 推到最右，拉开距离避免误触 */
@Composable
fun RowScope.V4BarSpacer() {
    Box(modifier = Modifier.weight(1f))
}

/**
 * 进程选择胶囊 `.proc{height:34px;padding:0 11px;border-radius:17px;max-width:112px}`。
 * 未选时 `.proc.off` 灰底显示「进程选择」；已选时主色底显示应用名。
 *
 * @param appName 已选应用名/包名；空白视为未选
 * @param highlighted 特殊高亮（如筛选层的进程选择态）——主色淡底
 */
@Composable
fun V4ProcessChip(
    appName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String,
    highlighted: Boolean = false,
    enabled: Boolean = true
) {
    val selected = appName.isNotBlank()
    val bg = when {
        highlighted -> V4.PrimarySoft
        selected -> V4.Primary
        else -> V4.Surface2
    }
    val fg = when {
        highlighted -> V4.Primary
        selected -> Color.White
        else -> V4.IconIdleResolved
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(17.dp),
        color = bg,
        modifier = modifier
            .height(34.dp)
            .widthIn(max = 112.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (selected) appName else placeholder,
                fontSize = 12.sp,
                fontWeight = if (selected || highlighted) FontWeight.Medium else FontWeight.Normal,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 34dp 圆形高频按钮 `.hbtn`。
 *
 * @param active  `.hbtn.on` —— 主色文字 + `#E8EFFF` 淡底（表示该功能已生效）
 * @param dot     右上角 5dp 小圆点（如「筛选已启用」）
 * @param count   右上角计数徽标（如「已过滤 3 条」）；与 dot 互斥，count 优先
 * @param danger  危险色（如崩溃页停止监听、清空）
 * @param solid   实心主色底（如崩溃页「开始监听」胶囊的展开态）
 * @param expandable 为 true 时按 `.hbtn.on{width:auto;border-radius:17px;padding:0 13px}` 变形为胶囊，配合 label 使用
 */
@Composable
fun V4RoundIconButton(
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    active: Boolean = false,
    dot: Boolean = false,
    count: Int = 0,
    danger: Boolean = false,
    solid: Boolean = false,
    enabled: Boolean = true,
    label: String? = null,
    expandable: Boolean = false
) {
    val pill = expandable && label != null
    val bg = when {
        solid -> V4.Primary
        active -> V4.PrimarySoft
        else -> V4.Surface
    }
    val fg = when {
        solid -> Color.White
        danger -> V4.Error
        active -> V4.Primary
        !enabled -> V4.DisabledTextResolved
        else -> V4.IconIdleResolved
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = if (pill) RoundedCornerShape(17.dp) else CircleShape,
        color = bg,
        modifier = if (pill) modifier.height(34.dp) else modifier.size(34.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = if (pill) 13.dp else 0.dp),
            contentAlignment = Alignment.Center
        ) {
            if (pill) {
                Text(
                    text = label!!,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = fg,
                    maxLines = 1
                )
            } else {
                Text(text = icon, fontSize = 15.sp, color = fg)
            }
            // 角标只在非胶囊形态下出现
            if (!pill && count > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 3.dp, end = 2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(V4.Primary)
                        .padding(horizontal = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (count > 99) "99+" else count.toString(),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1
                    )
                }
            } else if (!pill && dot) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 6.dp)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(V4.Primary)
                )
            }
        }
    }
}

// ══════════════════════════════ 统计行 ══════════════════════════════

/** `.stats{font-size:11.5px;color:var(--muted);padding:0 4px 5px}` */
@Composable
fun V4StatsLine(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        fontSize = 11.5.sp,
        color = V4.Muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, bottom = 5.dp)
    )
}

// ══════════════════════════════ FAB ══════════════════════════════

/**
 * 右下角悬浮主操作 `.fab{56dp;right:14px;bottom:14px}`。
 * 三态：抓取（主色）/ 停止（红色）/ 禁用（灰 `.fab.dis`）。
 *
 * 调用方需把它放进日志区的 Box 里并 `Modifier.align(Alignment.BottomEnd)`。
 *
 * @param text     按钮文字；留空表示由调用方在外层 `if` 里控制是否绘制
 */
@Composable
fun V4CaptureFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String = "",
    running: Boolean = false,
    enabled: Boolean = true
) {
    val bg = when {
        !enabled -> V4.DisabledResolved
        running -> V4.Error
        else -> V4.Primary
    }
    val fg = if (!enabled) V4.DisabledTextResolved else Color.White
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = bg,
        modifier = modifier.size(56.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = fg,
                maxLines = 1
            )
        }
    }
}

/**
 * FAB 上方的次级圆形按钮（复制 / 清空）。
 *
 * 比主 FAB 小一圈（40dp），白底 + 阴影，与主按钮形成主次层级。
 * 由 [V4FabColumn] 控制「有日志才显示」。
 */
@Composable
fun V4FabAction(
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    danger: Boolean = false,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = V4.Surface,
        shadowElevation = 4.dp,
        modifier = modifier.size(40.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = icon,
                fontSize = 15.sp,
                color = when {
                    !enabled -> V4.DisabledTextResolved
                    danger -> V4.Error
                    else -> V4.IconIdleResolved
                }
            )
        }
    }
}

/**
 * 右下角悬浮按钮列：主 FAB 在下，复制 / 清空小按钮依次叠在其上方。
 *
 * ★ [showActions] 只控制**次级按钮**（列在前面那些 40dp 小圆钮）的显隐，
 *   主 FAB（[content] 的最后一项）永远绘制。
 *
 *   这里踩过坑：最初把门控套在整个 content 上，结果是「没日志 → 整列消失 →
 *   连主按钮都没了」，而空列表恰恰是最需要主按钮的时候。主按钮是这个页面的
 *   入口动作，不能跟次级按钮绑在一起。
 *
 * 调用方负责把整列摆到日志区的右下角（`Modifier.align(Alignment.BottomEnd)`）。
 */
@Composable
fun V4FabColumn(
    showActions: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        content()
    }
}

// ══════════════════════════════ 居中状态卡 ══════════════════════════════

/** 跨行小工具：把若干 chip / 按钮按 6dp 间距平铺，可自动换行 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun V4OptRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

/**
 * 底部弹出层顶部的抓手条 `.sheet-grip{width:34px;height:4px}`。
 * 用 ModalBottomSheet 时代替它自带的拖拽条，视觉上与设计稿一致。
 */
@Composable
fun V4SheetGrip(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(top = 10.dp, bottom = 12.dp)
            .size(width = 34.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(V4.DisabledResolved)
    )
}

/** 弹出层标题 `.sheet-h{font-size:14px;font-weight:600;margin-bottom:12px}` */
@Composable
fun V4SheetHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = V4.Text,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    )
}

/** 分节小标签 `.tagline{font-size:11.5px;color:var(--muted);margin:4px 0 9px}` */
@Composable
fun V4Tagline(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 11.5.sp,
        color = V4.Muted,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 9.dp)
    )
}

// ══════════════════════════════ 分级小工具 ══════════════════════════════

/** 供外部按需使用的圆形图标底（如自定义尺寸） */
@Composable
fun V4CircleIconSurface(
    size: Dp,
    background: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** 供外部拼装用的竖向容器（sheet 内容统一 14dp 左右留白） */
@Composable
fun V4SheetColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 0.dp),
        content = content
    )
}
