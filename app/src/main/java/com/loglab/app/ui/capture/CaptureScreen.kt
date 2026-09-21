package com.loglab.app.ui.capture

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stream
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.loglab.app.core.connect.StartupCheckResult
import com.loglab.app.core.logcat.LogBuffer
import com.loglab.app.core.logcat.LogPriority
import com.loglab.app.data.model.LogEntry
import com.loglab.app.ui.components.AppPickerSheet
import com.loglab.app.ui.components.FilterSheet
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
import com.loglab.app.ui.components.V4Spinner
import com.loglab.app.ui.components.V4StatsLine
import com.loglab.app.ui.components.V4StatusActions
import com.loglab.app.ui.components.V4StatusButton
import com.loglab.app.ui.components.V4StatusCard
import com.loglab.app.ui.components.V4StatusSubtitle
import com.loglab.app.ui.components.V4StatusTitle
import com.loglab.app.ui.components.V4TopBar
import com.loglab.app.ui.theme.V4
import com.loglab.app.core.connect.CheckPhase

/**
 * 首页（抓取页）—— v4 布局。
 *
 * 结构（自上而下）：
 *  ① 顶栏一行：标题「抓取日志」+ 状态点（点它进连接页），不再有任何动作按钮；
 *  ② 高频行一行：启动抓取开关 · 进程选择胶囊 · 搜索 · 筛选 · spacer · ⋮；
 *  ③ 统计小字 → 日志区（占满剩余高度）；
 *  ④ 右下角：主 FAB「开始」+ 上方 ⧉ / 🗑 次级按钮（通道连通后才出现）；
 *  ⑤ 底部 4 个导航（在 LogcatApp 的 Scaffold 里）。
 *
 * 搜索/筛选/进程选择都是底部弹出层，页面上不再常驻输入框。
 * 状态提示从常驻一行改为居中卡片浮层，成功类提示 4 秒后自动消失。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    onGoConnect: () -> Unit = {},
    onGoGuide: () -> Unit = {},
    onGoExport: () -> Unit = {},
    onGoSettings: () -> Unit = {},
    viewModel: CaptureViewModel = hiltViewModel()
) {
    val settings by viewModel.appSettings.collectAsState()
    val channelState by viewModel.channelState.collectAsState()
    val checkPhase by viewModel.checkPhase.collectAsState()
    val listState = rememberLazyListState()

    // 搜索：v4 里由底部层「应用」写入，用 saveable 保证旋转/重建不丢
    var search by rememberSaveable { mutableStateOf("") }
    // true=过滤（只留匹配行）；false=高亮（全留，命中处标黄）
    var filterMode by rememberSaveable { mutableStateOf(true) }
    var recentSearches by rememberSaveable { mutableStateOf(listOf<String>()) }

    var searchOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var selectedLine by remember { mutableStateOf<LogEntry?>(null) }

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // 启动抓取模式下默认只展示「启动点之后」的日志，所以显示源用 displayEntries
    val source = viewModel.displayEntries
    val displayed = remember(source, search, filterMode) {
        if (search.isBlank() || !filterMode) source
        else source.filter { it.raw.contains(search, ignoreCase = true) }
    }

    // ---- 状态点颜色：橙=检测中 / 绿=已连接 / 黄=未配对或不可达 / 红=无线调试未开 ----
    val statusColor = when {
        viewModel.startupChecking && viewModel.startupResult == null -> V4.Warn
        channelState.connected -> V4.Green
        viewModel.startupResult is StartupCheckResult.DebugOff -> V4.Error
        viewModel.startupResult is StartupCheckResult.NeedPairing -> V4.Warn
        viewModel.startupResult is StartupCheckResult.NotReachable -> V4.Warn
        else -> V4.Muted
    }

    // 回到前台时自动重跑检查：App 被切后台再回来时进程往往没被杀，
    // ViewModel 也不会重建——若不重跑，就会一直显示旧结果（例如"已连接旧端口"），
    // 而用户刚开完无线调试期望看到端口更新。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.runStartupCheck("回到前台")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 成功类提示展示 4 秒后自动消失，不再常驻
    LaunchedEffect(viewModel.startupResult) {
        val r = viewModel.startupResult ?: return@LaunchedEffect
        if (r.connected) {
            kotlinx.coroutines.delay(4_000)
            viewModel.clearStartupResult()
        }
    }
    // 通道激活后（如配对完成、重连成功），清除过期的"未配对/不可达"提示
    LaunchedEffect(channelState.connected) {
        if (channelState.connected && viewModel.startupResult?.connected == false) {
            viewModel.clearStartupResult()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── ① 顶栏：只剩标题 + 状态点 ──
        V4TopBar(
            title = stringResource(R.string.tab_capture),
            statusColor = statusColor,
            onStatusClick = onGoConnect
        )

        // ── ② 高频行：按使用频率从左到右 ──
        //   ▷ 抓启动 · 选进程胶囊 · ⌕ 搜索 · ☰ 筛选 · spacer · ⋮
        //   「抓启动」放最左：它是这个页面的第一步动作，比「选哪个进程」还靠前；
        //   同时主按钮文案是「开始」，不再和它重名。
        V4QuickActionBar {
            V4RoundIconButton(
                icon = "▷",
                label = stringResource(R.string.v4_switch_startup),
                expandable = true,
                active = viewModel.startupMode,
                onClick = { viewModel.onStartupModeChange(!viewModel.startupMode) }
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
                onClick = { filterOpen = true },
                // 高亮条件：级别高于 DEBUG，或缓冲区不是默认两项——表示筛选确实在起作用
                active = viewModel.priority > LogPriority.DEBUG ||
                    viewModel.buffers != DEFAULT_BUFFERS
            )
            V4BarSpacer()
            V4RoundIconButton(
                icon = "⋮",
                contentDescription = stringResource(R.string.v4_cd_more),
                onClick = { menuOpen = true },
                active = menuOpen
            )
        }

        // 更新横幅：启动静默检查发现新版本时出现，点击进设置页自动弹更新框
        viewModel.availableUpdate?.let { update ->
            Surface(
                onClick = onGoSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Stream,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        stringResource(R.string.update_banner, update.version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "›",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // ── ③ 统计小字 + 日志区（Box 承载浮层）──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
        ) {
            if (viewModel.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // 统计行：抓取中显示状态文案，完成后显示「最近 N 行 · 用时 Xs」
            val statsText = when {
                viewModel.busy -> stringResource(R.string.capture_running)
                viewModel.status != null -> viewModel.status!!
                viewModel.lastLines != null -> stringResource(
                    R.string.v4_stats_recent_fmt,
                    viewModel.lastLines!!,
                    formatSeconds(viewModel.lastDurationMs)
                )
                else -> ""
            }
            if (statsText.isNotEmpty()) {
                V4StatsLine(text = statsText)
            }

            // 启动抓取结果条：定位到启动点后，可一键只看启动之后的日志
            if (viewModel.startupIndex >= 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Text(
                        stringResource(R.string.startup_point_line, viewModel.startupIndex + 1) +
                            (viewModel.startupPid?.let { " · PID $it" } ?: ""),
                        fontSize = 11.sp,
                        color = V4.Muted
                    )
                    FilterChip(
                        selected = viewModel.startupOnly,
                        onClick = viewModel::toggleStartupOnly,
                        label = { Text(stringResource(R.string.startup_only_after), fontSize = 11.sp, maxLines = 1) }
                    )
                }
            }

            // 日志区 + 浮层（居中状态卡 / 右下 FAB / ⋮ 菜单）
            Box(modifier = Modifier.weight(1f)) {
                LogListView(
                    entries = displayed,
                    listState = listState,
                    fontSize = settings.fontSize,
                    monoFont = settings.monoFont,
                    highlight = search,
                    modifier = Modifier.fillMaxSize(),
                    emptyHint = stringResource(R.string.empty_capture_hint),
                    onLineClick = { entry -> selectedLine = entry },
                    copyFeedback = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                )

                // 居中状态浮层：检测中 / 未连接（日志区作为背景，失败时降到 0.28 透明）
                //
                // ★ 失败态必须分三种，引导动作各不相同（以前合并成一条「未开启」是错的）：
                //   DebugOff    无线调试开关没开 → 去系统开发者选项把开关打开
                //   NeedPairing 开关开了但没配对 → 去连接页走配对流程
                //   NotReachable 已配对却连不上 → 去连接页排查/重连
                if (viewModel.startupChecking && viewModel.startupResult == null) {
                    V4CenterStatus {
                        V4StatusCard {
                            V4Spinner()
                            V4StatusTitle(stringResource(R.string.v4_status_checking))
                            val sub = checkPhaseSubtitle(checkPhase)
                            if (sub != null) V4StatusSubtitle(sub)
                        }
                    }
                } else if (!channelState.connected) {
                    when (val r = viewModel.startupResult) {
                        is StartupCheckResult.DebugOff -> V4CenterStatus {
                            V4StatusCard {
                                Text("⚠", fontSize = 20.sp, color = V4.Error)
                                V4StatusTitle(
                                    stringResource(R.string.v4_status_debug_off),
                                    color = V4.Error
                                )
                                V4StatusSubtitle(
                                    stringResource(R.string.v4_status_debug_off_sub, MDNS_ROUNDS)
                                )
                                V4StatusActions {
                                    // 直达系统开发者选项开开关，不去连接页
                                    V4StatusButton(
                                        text = stringResource(R.string.v4_go_enable),
                                        onClick = viewModel::openDevSettings
                                    )
                                    V4StatusButton(
                                        text = stringResource(R.string.v4_retry),
                                        ghost = true,
                                        onClick = { viewModel.retryStartupCheck() }
                                    )
                                }
                            }
                        }

                        is StartupCheckResult.NeedPairing -> V4CenterStatus {
                            V4StatusCard {
                                Text("🔑", fontSize = 20.sp)
                                V4StatusTitle(stringResource(R.string.v4_status_need_pairing))
                                V4StatusSubtitle(stringResource(R.string.v4_status_need_pairing_sub))
                                V4StatusActions {
                                    // 配对流程在连接页，这里才该跳连接页
                                    V4StatusButton(
                                        text = stringResource(R.string.v4_go_pair),
                                        onClick = onGoConnect
                                    )
                                    V4StatusButton(
                                        text = stringResource(R.string.v4_retry),
                                        ghost = true,
                                        onClick = { viewModel.retryStartupCheck() }
                                    )
                                }
                            }
                        }

                        is StartupCheckResult.NotReachable -> V4CenterStatus {
                            V4StatusCard {
                                Text("⚠", fontSize = 20.sp, color = V4.Warn)
                                V4StatusTitle(
                                    stringResource(R.string.v4_status_not_reachable),
                                    color = V4.Warn
                                )
                                V4StatusSubtitle(stringResource(R.string.v4_status_not_reachable_sub))
                                V4StatusActions {
                                    V4StatusButton(
                                        text = stringResource(R.string.v4_go_connect),
                                        onClick = onGoConnect
                                    )
                                    V4StatusButton(
                                        text = stringResource(R.string.v4_retry),
                                        ghost = true,
                                        onClick = { viewModel.retryStartupCheck() }
                                    )
                                }
                            }
                        }

                        else -> Unit
                    }
                }

                // 右下角：主 FAB「开始」+ 上方 ⧉ / 🗑 次级按钮（有日志才出现）
                //
                // ★ 主按钮只在「通道已连接」或「正在抓取」时出现。
                //   未连接时它既点不动（enabled=false 走灰底灰字），又会正好压在日志区
                //   那行居中的空态提示上——白底浅灰按钮叠浅灰文字，看着就像凭空消失了。
                //   干脆不摆：未连接要看的是中间那张「去开启无线调试」的状态卡，
                //   连上之后按钮出现，就是正常的主色「开始」。
                if (channelState.connected || viewModel.busy) {
                    V4FabColumn(
                        showActions = displayed.isNotEmpty(),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(14.dp)
                    ) {
                        if (displayed.isNotEmpty()) {
                            V4FabAction(
                                icon = "⧉",
                                contentDescription = stringResource(R.string.v4_cd_copy),
                                onClick = {
                                    clipboard.setText(AnnotatedString(displayed.joinToString("\n") { it.raw }))
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.v4_toast_copied_lines_fmt, displayed.size),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                            V4FabAction(
                                icon = "🗑",
                                contentDescription = stringResource(R.string.v4_cd_clear),
                                danger = true,
                                onClick = { viewModel.clearLogs() }
                            )
                        }
                        // 主按钮：永远绘制
                        V4CaptureFab(
                            text = if (viewModel.busy) {
                                stringResource(R.string.capture_running)
                            } else {
                                stringResource(R.string.v4_fab_capture)
                            },
                            running = viewModel.busy,
                            enabled = !viewModel.busy,
                            onClick = viewModel::capture
                        )
                    }
                }

                // ⋮ 低频菜单：导出到文件 / 缓冲区 / 使用方法
                if (menuOpen) {
                    // 点空白处收起菜单（先铺一层透明遮罩，再放菜单，保证菜单在上层可点）
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
                            text = stringResource(R.string.v4_menu_export),
                            icon = "⤓",
                            onClick = {
                                menuOpen = false
                                onGoExport()
                            }
                        )
                        V4MenuSeparator()
                        V4MenuItem(
                            text = stringResource(R.string.v4_menu_buffers),
                            icon = "⊞",
                            mark = viewModel.buffers.joinToString("·") { it.value },
                            onClick = { filterOpen = true; menuOpen = false }
                        )
                        V4MenuSeparator()
                        V4MenuItem(
                            text = stringResource(R.string.v4_menu_guide),
                            icon = "?",
                            onClick = {
                                menuOpen = false
                                onGoGuide()
                            }
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

    // ── 筛选底部层 ──
    if (filterOpen) {
        FilterSheet(
            priority = viewModel.priority,
            maxLines = viewModel.maxLines,
            buffers = viewModel.buffers,
            packageName = viewModel.packageName.trim(),
            startupMode = viewModel.startupMode,
            startupTailSec = viewModel.startupTailSec,
            onDismiss = { filterOpen = false },
            onOpenProcessPicker = {
                filterOpen = false
                viewModel.showPicker(true)
            },
            onApply = { p, lines, bufs, tailSec ->
                viewModel.onPriorityChange(p)
                viewModel.onMaxLinesChange(lines.toString())
                // 缓冲区：把差异补上/去掉
                LogBuffer.entries.filter { it in BUFFER_CHOICES }.forEach { b ->
                    val want = b in bufs
                    val have = b in viewModel.buffers
                    if (want != have) viewModel.toggleBuffer(b)
                }
                viewModel.onStartupTailChange(tailSec)
                filterOpen = false
            }
        )
    }

    // ── 进程选择底部层（点行首胶囊 / 筛选层「应用」打开）──
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

    // ── 行详情底部面板 ──
    selectedLine?.let { entry ->
        LogLineSheet(
            entry = entry,
            onDismiss = { selectedLine = null },
            onCopy = { text ->
                clipboard.setText(AnnotatedString(text))
                Toast.makeText(context, R.string.v4_toast_copied_line, Toast.LENGTH_SHORT).show()
            },
            // "仅看此 Tag"：直接把 Tag 填入搜索层（前端过滤，清空关键字即可取消）
            onTagFilter = { tag ->
                search = tag
                filterMode = true
                selectedLine = null
                Toast.makeText(context, context.getString(R.string.v4_toast_copied_line), Toast.LENGTH_SHORT).show()
            }
        )
    }
}

/** MDNS 扫描轮数（与 StartupCheck.MDNS_RESCAN_WINDOW_MS 的两轮扫描对应） */
private const val MDNS_ROUNDS = 3

/** 默认缓冲区（main + crash），用于判断筛选图标是否需要高亮 */
private val DEFAULT_BUFFERS = setOf(LogBuffer.MAIN, LogBuffer.CRASH)

/** 筛选层暴露给「应用」按钮的缓冲区选项 */
private val BUFFER_CHOICES = setOf(
    LogBuffer.MAIN,
    LogBuffer.SYSTEM,
    LogBuffer.EVENTS,
    LogBuffer.CRASH
)

/** 把耗时毫秒格式化成设计稿里的「1.2s」写法 */
private fun formatSeconds(ms: Long): String {
    if (ms <= 0) return "0s"
    val s = ms / 1000.0
    return if (s < 10) String.format(java.util.Locale.US, "%.1fs", s) else "${ms / 1000}s"
}

/**
 * 把启动检查的实时阶段映射成设计稿里的副标题。
 *
 * CheckPhase 只带一个字符串资源 ID + 参数（core 层不做本地化），所以这里直接
 * 用 stringResource 解析即可——设计稿里「正在等待无线调试服务通告（第 1/3 轮）」
 * 的括号内容本身就是现有 check_phase_waiting 文案，不需要另造轮次计数。
 */
@Composable
private fun checkPhaseSubtitle(
    phase: CheckPhase?
): String? {
    val res = phase?.res ?: return null
    return runCatching {
        if (phase.args.isEmpty()) stringResource(res) else stringResource(res, *phase.args.toTypedArray())
    }.getOrNull()
}

/** 点空白处收起浮层的点击修饰符（无涟漪，避免整屏闪一下） */
private fun Modifier.tapToDismiss(onClick: () -> Unit): Modifier = composed {
    clickable(
        onClick = onClick,
        indication = null,
        interactionSource = remember { MutableInteractionSource() }
    )
}