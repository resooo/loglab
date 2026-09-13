package com.loglab.app.ui.tail

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.composed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.loglab.app.R
import com.loglab.app.data.model.LogEntry
import com.loglab.app.ui.components.AppPickerSheet
import com.loglab.app.ui.components.EllipseTextField
import com.loglab.app.ui.components.LogLineSheet
import com.loglab.app.ui.components.LogListView
import com.loglab.app.ui.components.SearchSheet
import com.loglab.app.ui.components.V4BarSpacer
import com.loglab.app.ui.components.V4CaptureFab
import com.loglab.app.ui.components.V4CenterStatus
import com.loglab.app.ui.components.V4FabAction
import com.loglab.app.ui.components.V4FabColumn
import com.loglab.app.ui.components.V4MenuItem
import com.loglab.app.ui.components.V4MenuSeparator
import com.loglab.app.ui.components.V4OverflowMenu
import com.loglab.app.ui.components.V4ProcessChip
import com.loglab.app.ui.components.V4QuickActionBar
import com.loglab.app.ui.components.V4RoundIconButton
import com.loglab.app.ui.components.V4StatsLine
import com.loglab.app.ui.components.V4StatusActions
import com.loglab.app.ui.components.V4StatusButton
import com.loglab.app.ui.components.V4StatusCard
import com.loglab.app.ui.components.V4StatusSubtitle
import com.loglab.app.ui.components.V4StatusTitle
import com.loglab.app.ui.components.V4TopBar
import com.loglab.app.ui.theme.V4

/** 实时页渲染层最多显示的行数（数据层全量保留，复制/导出不受影响） */
private const val TAIL_DISPLAY_LIMIT = 1000

/**
 * 实时跟踪页 —— v4 布局（与首页同一规则）。
 *
 * 结构：
 *  ① 顶栏：标题「实时跟踪」+ 状态点；
 *  ② 高频行：⏸/▶ 暂停 · ⏹ 停止 · 进程胶囊 · ⌕ · ☰ · spacer · ⋮；
 *  ③ 统计行「显示 1000 行 · 12 行/秒」→ 日志区；
 *  ④ 右下角：主 FAB「停止」+ 上方 ⧉ / 🗑 次级按钮（有日志才出现）；
 *  ⑤ 底部导航（Scaffold 提供）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TailScreen(
    onGoConnect: () -> Unit = {},
    viewModel: TailViewModel = hiltViewModel()
) {
    val settings by viewModel.appSettings.collectAsState()
    val channelState by viewModel.channelState.collectAsState()
    val lines by viewModel.lines.collectAsState()
    val tailState by viewModel.tailState.collectAsState()
    val listState = rememberLazyListState()

    var search by rememberSaveable { mutableStateOf("") }
    var filterMode by rememberSaveable { mutableStateOf(true) }
    var recentSearches by rememberSaveable { mutableStateOf(listOf<String>()) }
    var searchOpen by remember { mutableStateOf(false) }
    var keywordsOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var selectedLine by remember { mutableStateOf<LogEntry?>(null) }

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    // stringResource 不能在 onClick / 回调（非 Composable 上下文）里调用，提前取好
    val copiedLineText = stringResource(R.string.tail_copied_line)
    val runningText = stringResource(R.string.tail_running)
    val fabCaptureText = stringResource(R.string.v4_fab_capture)

    // 匹配结果全量保留（「复制全部」使用）
    val matched = remember(lines, search, filterMode) {
        if (search.isBlank() || !filterMode) lines
        else lines.filter { it.raw.contains(search, ignoreCase = true) }
    }
    val copiedLinesText = stringResource(R.string.tail_copied_lines_fmt, matched.size)
    // 渲染层只显示最近 TAIL_DISPLAY_LIMIT 条并倒序（配合 reverseLayout 贴底跟随）
    val displayed = remember(matched) { matched.takeLast(TAIL_DISPLAY_LIMIT).asReversed() }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── ① 顶栏：标题 + 状态点 ──
        V4TopBar(
            title = stringResource(R.string.tab_tail),
            statusColor = if (channelState.connected) V4.Green else V4.Error,
            onStatusClick = onGoConnect
        )

        // ── ② 高频行：按使用频率从左到右 ──
        //   ⏸/▶ 暂停 · ⏹ 停止 · 进程胶囊 · ⌕ 搜索 · ☰ 关键词 · spacer · ⋮
        //   暂停/停止提到最左：这两个是实时页真正的即时操作，比选进程更常用。
        V4QuickActionBar {
            V4RoundIconButton(
                icon = if (viewModel.paused) "▶" else "⏸",
                contentDescription = if (viewModel.paused) {
                    stringResource(R.string.tail_resume)
                } else {
                    stringResource(R.string.tail_pause)
                },
                active = viewModel.paused,
                enabled = tailState.running,
                onClick = viewModel::togglePause
            )
            V4RoundIconButton(
                icon = "⏹",
                contentDescription = stringResource(R.string.tail_stop),
                danger = tailState.running,
                enabled = tailState.running,
                onClick = viewModel::stop
            )
            V4ProcessChip(
                appName = viewModel.packageName.trim(),
                onClick = { viewModel.showPicker(true) },
                placeholder = stringResource(R.string.v4_process_placeholder)
            )
            V4RoundIconButton(
                icon = "⌕",
                contentDescription = stringResource(R.string.v4_cd_search),
                onClick = { searchOpen = true },
                active = search.isNotBlank(),
                dot = search.isNotBlank()
            )
            V4RoundIconButton(
                icon = "☰",
                contentDescription = stringResource(R.string.v4_cd_filter),
                onClick = { keywordsOpen = true }
            )
            V4BarSpacer()
            V4RoundIconButton(
                icon = "⋮",
                contentDescription = stringResource(R.string.v4_cd_more),
                onClick = { menuOpen = true },
                active = menuOpen
            )
        }

        // ── ③ 统计行 + 日志区 ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
        ) {
            V4StatsLine(
                text = stringResource(
                    R.string.v4_stats_tail_fmt,
                    displayed.size,
                    tailState.ratePerSecond
                )
            )

            Box(modifier = Modifier.weight(1f)) {
                LogListView(
                    entries = displayed,
                    listState = listState,
                    fontSize = settings.fontSize,
                    monoFont = settings.monoFont,
                    highlight = search,
                    autoScroll = true,
                    modifier = Modifier.fillMaxSize(),
                    emptyHint = if (tailState.running) {
                        stringResource(R.string.tail_waiting)
                    } else {
                        stringResource(R.string.tail_tap_start)
                    },
                    reverseLayout = true,
                    onLineClick = { entry -> selectedLine = entry },
                    copyFeedback = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                )

                // 未启动时的居中提示（设计稿同一套浮层）
                if (!tailState.running && displayed.isEmpty()) {
                    V4CenterStatus {
                        V4StatusCard {
                            V4StatusTitle(stringResource(R.string.tail_tap_start))
                            V4StatusSubtitle(stringResource(R.string.v4_status_no_channel))
                            V4StatusActions {
                                V4StatusButton(
                                    text = stringResource(R.string.tail_start),
                                    onClick = viewModel::start
                                )
                            }
                        }
                    }
                }

                // 右下角：主 FAB + 上方 ⧉ / 🗑 次级按钮（有日志才出现）
                V4FabColumn(
                    showActions = lines.isNotEmpty(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(14.dp)
                ) {
                    if (lines.isNotEmpty()) {
                        V4FabAction(
                            icon = "⧉",
                            contentDescription = stringResource(R.string.cd_copy_all),
                            onClick = {
                                clipboard.setText(AnnotatedString(matched.joinToString("\n") { it.raw }))
                                Toast.makeText(context, copiedLinesText, Toast.LENGTH_SHORT).show()
                            }
                        )
                        V4FabAction(
                            icon = "🗑",
                            contentDescription = stringResource(R.string.cd_clear_logs),
                            danger = true,
                            onClick = { viewModel.clear() }
                        )
                    }
                    // 主按钮：永远绘制
                    V4CaptureFab(
                        text = if (tailState.running) {
                            stringResource(R.string.v4_fab_stop)
                        } else {
                            fabCaptureText
                        },
                        running = tailState.running,
                        enabled = tailState.running || channelState.connected,
                        onClick = { if (tailState.running) viewModel.stop() else viewModel.start() }
                    )
                }

                if (menuOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .tapToDismiss { menuOpen = false }
                    )
                    V4OverflowMenu(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 12.dp)
                    ) {
                        V4MenuItem(
                            text = stringResource(R.string.v4_menu_guide),
                            icon = "?",
                            onClick = { menuOpen = false }
                        )
                    }
                }
            }
        }
    }

    // ── 搜索底部层 ──
    if (searchOpen) {
        SearchSheet(
            initialQuery = search,
            initialFilterMode = filterMode,
            recentQueries = recentSearches,
            onDismiss = { searchOpen = false },
            onApply = { q, mode ->
                search = q
                filterMode = mode
                if (q.isNotBlank()) {
                    recentSearches = (listOf(q) + recentSearches.filter { it != q }).take(3)
                }
                searchOpen = false
            }
        )
    }

    // ── 关键词底部层（低频项）──
    if (keywordsOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { keywordsOpen = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.cd_filter))
                EllipseTextField(
                    value = viewModel.keywordInput,
                    onValueChange = viewModel::onKeywordChange,
                    placeholder = stringResource(R.string.tail_keywords_hint),
                    leadingLabel = stringResource(R.string.tail_keywords_label)
                )
                Text(
                    stringResource(R.string.tail_keywords_note),
                    fontSize = 12.sp,
                    color = V4.Muted
                )
            }
        }
    }

    // ── 行详情底部面板 ──
    selectedLine?.let { entry ->
        LogLineSheet(
            entry = entry,
            onDismiss = { selectedLine = null },
            onCopy = { text ->
                clipboard.setText(AnnotatedString(text))
                Toast.makeText(context, copiedLineText, Toast.LENGTH_SHORT).show()
            }
        )
    }

    // ── 进程选择底部层 ──
    if (viewModel.pickerVisible) {
        AppPickerSheet(
            apps = viewModel.apps,
            loading = viewModel.appsLoading,
            selectedPackage = viewModel.packageName.trim(),
            onDismiss = { viewModel.showPicker(false) },
            onPick = { pkg ->
                viewModel.pickPackage(pkg)
                viewModel.showPicker(false)
            },
            onClear = {
                viewModel.onPackageChange("")
                viewModel.showPicker(false)
            },
            iconFor = { pkg -> viewModel.iconFor(pkg) }
        )
    }
}

/** 点空白处收起浮层的点击修饰符（无涟漪，避免整屏闪一下） */
private fun Modifier.tapToDismiss(onClick: () -> Unit): Modifier = composed {
    clickable(
        onClick = onClick,
        indication = null,
        interactionSource = remember { MutableInteractionSource() }
    )
}
