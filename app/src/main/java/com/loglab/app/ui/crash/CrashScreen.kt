package com.loglab.app.ui.crash

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.composed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import com.loglab.app.R
import com.loglab.app.core.crash.CrashEvent
import com.loglab.app.ui.components.V4CaptureFab
import com.loglab.app.ui.components.V4FabAction
import com.loglab.app.ui.components.V4FabColumn
import com.loglab.app.ui.components.V4MenuItem
import com.loglab.app.ui.components.V4MenuSeparator
import com.loglab.app.ui.components.V4OverflowMenu
import com.loglab.app.ui.components.V4QuickActionBar
import com.loglab.app.ui.components.RefreshOnResume
import com.loglab.app.ui.components.V4RoundIconButton
import com.loglab.app.ui.components.V4StatsLine
import com.loglab.app.ui.components.V4TopBar
import com.loglab.app.ui.theme.V4

/**
 * 崩溃监控页 —— v4 布局。
 *
 * 结构：
 *  ① 顶栏：标题「崩溃监控」+ 状态点（监控中红点）；
 *  ② 高频行：⟳ 读取历史 · ☰ · spacer · ⋮；
 *  ③ 统计行「今天 · 3 条记录」→ 记录列表（紧凑多行）；
 *  ④ 右下角：主 FAB「开始 / 停止」+ 上方 ⧉ / 🗑 次级按钮（有记录才出现）；
 *  ⑤ ⋮ 低频菜单：读取历史 / 分享全部 / 时间范围 / 清空。
 *
 * 记录行改用 v4 的扁平样式：应用名 + 右侧红色时间 / 红色「类型 · 摘要」/ 灰色包名。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashScreen(viewModel: CrashViewModel = hiltViewModel()) {
    val events by viewModel.events.collectAsState()
    val monitoring by viewModel.monitoring.collectAsState()
    val message by viewModel.message.collectAsState()
    val messageIsError by viewModel.messageIsError.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf(false) }
    var selectedEvent by remember { mutableStateOf<CrashEvent?>(null) }

    val shownEvents = viewModel.filteredEvents
    val shareText = stringResource(R.string.share)

    // ★ 回到前台时同步后台服务期间捕获的崩溃。
    //   监控跑在前台服务里，用户切出去闪退目标应用再切回来时界面没有重组触发点，
    //   新记录会停在 store 里不显示。这里强制重算一次过滤结果并刷新列表锚点。
    RefreshOnResume { viewModel.refresh() }

    // 右下角主 FAB：未监控时显示「开始」二字（用户要求），监控中显示「停止」
    val fabText = if (monitoring) {
        stringResource(R.string.v4_fab_stop)
    } else {
        stringResource(R.string.v4_crash_monitor_fab)
    }
    val copiedLinesText = stringResource(R.string.v4_toast_copied_lines_fmt, shownEvents.size)
    val clearedText = stringResource(R.string.crash_cleared)

    /** 清空记录：数据层 clear() 会立刻把 events 置空，列表随即重算（filteredEvents 是 derivedStateOf） */
    fun doClear() {
        viewModel.clear()
        selectedEvent = null
        Toast.makeText(context, clearedText, Toast.LENGTH_SHORT).show()
    }
    val rangeLabel = if (viewModel.range == CrashRange.TODAY) {
        stringResource(R.string.crash_range_today)
    } else {
        stringResource(R.string.crash_range_7d)
    }
    // 时间范围两个页签：菜单里按顺序渲染
    val crashRangeEntries = CrashRange.entries

    Column(modifier = Modifier.fillMaxSize()) {
        // ── ① 顶栏：标题 + 状态点 ──
        V4TopBar(
            title = stringResource(R.string.tab_crash),
            statusColor = if (monitoring) V4.Error else V4.Muted,
            statusEnabled = monitoring
        )

        // ── ② 高频行：按使用频率从左到右 ──
        //   ⟳ 读取历史 · ☰ 菜单 · spacer · ⋮
        //   ★ 左上角的「开始监听 / 停止监听」胶囊已按用户要求移除——
        //     监控的启停统一由右下角主 FAB 负责，避免同一功能在页面上出现两遍。
        //   复制 / 清空已下沉到右下角 FAB 上方；「读取历史」在 ⋮ 菜单里保留一份。
        V4QuickActionBar {
            // 读取历史：没开监控之前崩过的记录靠它补抓，是最常用的动作
            V4RoundIconButton(
                icon = "⟳",
                contentDescription = stringResource(R.string.cd_read_history),
                onClick = { viewModel.readHistory() }
            )
            V4RoundIconButton(
                icon = "☰",
                contentDescription = stringResource(R.string.cd_filter),
                onClick = { menuOpen = true }
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
            V4RoundIconButton(
                icon = "⋮",
                contentDescription = stringResource(R.string.more),
                active = menuOpen,
                onClick = { menuOpen = true }
            )
        }

        // ── ③ 统计行 + 记录列表 ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
        ) {
            V4StatsLine(
                text = if (viewModel.range == CrashRange.TODAY) {
                    stringResource(R.string.v4_stats_crash_fmt, shownEvents.size)
                } else {
                    stringResource(R.string.v4_stats_crash_range_fmt, rangeLabel, shownEvents.size)
                }
            )

            message?.let { msg ->
                Text(
                    msg,
                    fontSize = 11.5.sp,
                    color = if (messageIsError) V4.Error else V4.Primary,
                    modifier = Modifier
                        .padding(bottom = 6.dp)
                        .clickable { viewModel.store.setMessage(null) }
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                when {
                    // 空态：居中提示 + 三步引导
                    !monitoring && shownEvents.isEmpty() -> Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            stringResource(R.string.crash_start),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = V4.Text
                        )
                        Column(
                            modifier = Modifier.padding(top = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(stringResource(R.string.crash_guide_1), fontSize = 11.5.sp, color = V4.Muted)
                            Text(stringResource(R.string.crash_guide_2), fontSize = 11.5.sp, color = V4.Muted)
                            Text(stringResource(R.string.crash_guide_3), fontSize = 11.5.sp, color = V4.Muted)
                            Text(
                                stringResource(R.string.crash_guide_history),
                                fontSize = 11.5.sp,
                                color = V4.Primary
                            )
                        }
                    }

                    // items 带 key：新记录插入后 LazyColumn 能正确复用/重建可见项。
                    // key 里混入 viewModel.listAnchor（回到前台刷新时递增），
                    // 覆盖「近7天页签下新记录插在列表中段」时的重绘盲区。
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        val anchor = viewModel.listAnchor
                        if (monitoring) {
                            item(key = "hint-$anchor") {
                                Text(
                                    stringResource(R.string.crash_monitor_hint),
                                    fontSize = 11.5.sp,
                                    color = V4.Muted,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            }
                        }
                        if (shownEvents.isEmpty()) {
                            item(key = "empty-$anchor") {
                                Text(
                                    if (monitoring) {
                                        stringResource(R.string.crash_empty_monitoring)
                                    } else {
                                        stringResource(R.string.crash_empty_list)
                                    },
                                    fontSize = 11.5.sp,
                                    color = V4.Muted,
                                    modifier = Modifier.padding(vertical = 24.dp)
                                )
                            }
                        }
                        itemsIndexed(
                            items = shownEvents,
                            key = { _, e -> "$anchor|${e.time}|${e.packageName}|${e.type}" }
                        ) { index, event ->
                            CrashRow(
                                event = event,
                                icon = viewModel.icon(event.packageName),
                                appLabel = viewModel.appLabel(event),
                                onClick = { selectedEvent = event }
                            )
                            if (index < shownEvents.size - 1) {
                                HorizontalDivider(color = V4.Line, thickness = 1.dp)
                            }
                        }
                    }
                }

                // 右下角：主 FAB「监听」+ 上方 ⧉ / 🗑 次级按钮（有记录才出现）
                V4FabColumn(
                    showActions = shownEvents.isNotEmpty(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(14.dp)
                ) {
                    if (shownEvents.isNotEmpty()) {
                        V4FabAction(
                            icon = "⧉",
                            contentDescription = stringResource(R.string.cd_copy_all),
                            onClick = {
                                clipboard.setText(AnnotatedString(shownEvents.joinToString("\n") { it.stack }))
                                Toast.makeText(context, copiedLinesText, Toast.LENGTH_SHORT).show()
                            }
                        )
                        V4FabAction(
                            icon = "🗑",
                            contentDescription = stringResource(R.string.cd_clear_logs),
                            danger = true,
                            onClick = { doClear() }
                        )
                    }
                    // 主按钮：永远绘制
                    V4CaptureFab(
                        text = fabText,
                        running = monitoring,
                        onClick = { if (monitoring) viewModel.stop() else viewModel.start() }
                    )
                }

                // ⋮ 菜单：读取历史 / 分享 / 时间范围 / 清空
                if (menuOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .tapToDismiss { menuOpen = false }
                    )
                    V4OverflowMenu(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 0.dp)
                    ) {
                        V4MenuItem(
                            text = stringResource(R.string.cd_read_history),
                            icon = "⟳",
                            onClick = {
                                menuOpen = false
                                viewModel.readHistory()
                            }
                        )
                        V4MenuItem(
                            text = shareText,
                            icon = "⤴",
                            enabled = events.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                viewModel.shareAll()
                            }
                        )
                        V4MenuSeparator()
                        crashRangeEntries.forEach { r ->
                            V4MenuItem(
                                text = if (r == CrashRange.TODAY) {
                                    stringResource(R.string.crash_range_today)
                                } else {
                                    stringResource(R.string.crash_range_7d)
                                },
                                icon = "◷",
                                mark = if (viewModel.range == r) "✓" else null,
                                onClick = { viewModel.onRangeChange(r); menuOpen = false }
                            )
                        }
                        V4MenuSeparator()
                        V4MenuItem(
                            text = stringResource(R.string.crash_menu_diagnostics),
                            icon = "ⓘ",
                            onClick = {
                                menuOpen = false
                                viewModel.shareDiagnostics()
                            }
                        )
                        V4MenuSeparator()
                        V4MenuItem(
                            text = stringResource(R.string.crash_menu_clear),
                            icon = "🗑",
                            danger = true,
                            enabled = events.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                doClear()
                            }
                        )
                    }
                }
            }
        }
    }

    // ── 崩溃详情底部面板：完整堆栈 + 复制 ──
    selectedEvent?.let { event ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val copiedText = stringResource(R.string.crash_copied)
        ModalBottomSheet(
            onDismissRequest = { selectedEvent = null },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    event.displayPackage,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = V4.Text
                )
                Text(
                    "${event.type} · ${event.time}",
                    fontSize = 11.5.sp,
                    color = V4.Error
                )
                Text(
                    event.summary,
                    fontSize = 11.5.sp,
                    color = V4.Muted
                )
                Text(
                    event.stack,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = V4.Text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                )
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(event.stack))
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.crash_copy_stack)) }
            }
        }
    }
}

/**
 * v4 扁平崩溃记录行：
 *  行1：应用名（12.5sp 粗体） + 右侧红色时间（11sp）
 *  行2：红色「类型 · 摘要」（11sp）
 *  行3：灰色包名（10sp，仅当应用名与包名不同）
 */
@Composable
private fun CrashRow(
    event: CrashEvent,
    icon: android.graphics.drawable.Drawable?,
    appLabel: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 应用图标：拿不到真实图标时用「应用名首字母 + 主色淡底」占位
        // ★ remember 的 key 必须是「包名」而不是 Drawable 实例：
        //   ViewModel 每次重组都会重新调用 icon()，拿到的是新对象，
        //   用 Drawable 当 key 会导致位图每帧重建（闪烁 + 掉帧）。
        AppRowIcon(
            packageName = event.packageName,
            icon = icon,
            fallbackLabel = appLabel
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // 有应用名显示应用名，包名作副标题省略；都没有显示未知应用
                    appLabel ?: event.displayPackage,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = V4.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    event.time,
                    fontSize = 11.sp,
                    color = V4.Error
                )
            }
            Text(
                // 摘要与类型相同时只显示一个，避免「Java 崩溃 · Java 崩溃」
                if (event.summary.isNotBlank() && event.summary != event.type) {
                    "${event.type} · ${event.summary}"
                } else {
                    event.type
                },
                fontSize = 11.sp,
                color = V4.Error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 应用名与包名不同时补一行包名，便于定位具体应用
            if (appLabel != null && event.packageName != null && appLabel != event.packageName) {
                Text(
                    event.displayPackage,
                    fontSize = 10.sp,
                    color = V4.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 崩溃记录行左侧的 32dp 应用图标。
 *
 * 三条渲染路径都覆盖到：
 *  1. 拿到真实图标 → 圆角裁剪绘制；
 *  2. 拿不到（应用已卸载、包名没解析出来）→ 应用名首字母 + 主色淡底占位；
 *  3. 连应用名都没有 → 中性「?」占位。
 *
 * 位图按 [ICON_PX] 解码并 remember(packageName) 缓存：尺寸取 32dp 在 xxhdpi 下
 * 的像素量级，足够清晰又不会因原图过大而每次都做无谓的缩放。
 */
@Composable
private fun AppRowIcon(
    packageName: String?,
    icon: android.graphics.drawable.Drawable?,
    fallbackLabel: String?
) {
    val shape = RoundedCornerShape(8.dp)
    val bitmap = remember(packageName, icon) {
        icon?.let { runCatching { it.toBitmap(ICON_PX, ICON_PX).asImageBitmap() }.getOrNull() }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.cd_app_icon),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(32.dp)
                .clip(shape)
        )
        return
    }
    val glyph = fallbackLabel?.trim()?.firstOrNull()?.uppercase()
    Surface(
        shape = shape,
        color = if (glyph != null) V4.PrimarySoft else V4.Surface2,
        modifier = Modifier
            .padding(top = 2.dp)
            .size(32.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = glyph ?: "?",
                color = if (glyph != null) V4.Primary else V4.Muted,
                fontSize = 13.sp,
                fontWeight = if (glyph != null) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

/** 行图标解码尺寸：32dp 在 xxhdpi(3x) 下的像素量级 */
private const val ICON_PX = 96

/** 点空白处收起浮层的点击修饰符（无涟漪，避免整屏闪一下） */
private fun Modifier.tapToDismiss(onClick: () -> Unit): Modifier = composed {    clickable(
        onClick = onClick,
        indication = null,
        interactionSource = remember { MutableInteractionSource() }
    )
}
