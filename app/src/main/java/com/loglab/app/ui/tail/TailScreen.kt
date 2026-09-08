package com.loglab.app.ui.tail

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.loglab.app.R
import com.loglab.app.core.connect.CheckPhase
import com.loglab.app.core.logcat.LogBuffer
import com.loglab.app.core.logcat.LogPriority
import com.loglab.app.data.model.LogEntry
import com.loglab.app.ui.components.AppPickerDialog
import com.loglab.app.ui.components.EllipseTextField
import com.loglab.app.ui.components.FilterDropdown
import com.loglab.app.ui.components.HomeStatusBar
import com.loglab.app.ui.components.LogLineSheet
import com.loglab.app.ui.components.LogListView

/** 实时页渲染层最多显示的行数（数据层全量保留，复制/导出不受影响） */
private const val TAIL_DISPLAY_LIMIT = 1000

/**
 * 实时跟踪页（与首页同款紧凑布局）：
 * 顶栏（含速率小字）+ 状态行 + 控制行（开始/暂停/停止/复制/清空）+
 * 工具行（搜索 + 进程 + 级别 + 缓冲区）+ 日志流；点行弹底部详情面板。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var search by remember { mutableStateOf("") }
    // true=过滤（只留匹配行）；false=高亮（全留，命中处标黄）
    var filterMode by remember { mutableStateOf(true) }
    var filtersOpen by remember { mutableStateOf(false) }
    var selectedLine by remember { mutableStateOf<LogEntry?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    // stringResource 不能在 onClick / 回调（非 Composable 上下文）里调用，提前取好
    val copiedLineText = stringResource(R.string.tail_copied_line)

    // 匹配结果全量保留（「复制全部」使用）
    val matched = remember(lines, search, filterMode) {
        if (search.isBlank() || !filterMode) lines
        else lines.filter { it.raw.contains(search, ignoreCase = true) }
    }
    val copiedLinesText = stringResource(R.string.tail_copied_lines_fmt, matched.size)
    // 渲染层只显示最近 TAIL_DISPLAY_LIMIT 条并倒序（配合 reverseLayout 贴底跟随）：
    // 数据层 5000 条全量裁剪对 UI 无感，同时把 LazyColumn 的 diff 规模压低，
    // 高频日志下不再卡顿/闪退
    val displayed = remember(matched) { matched.takeLast(TAIL_DISPLAY_LIMIT).asReversed() }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- 顶栏：标题 + 速率小字 + 筛选 ----
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.tab_tail))
                    Text(
                        stringResource(R.string.tail_rate_fmt, displayed.size, tailState.ratePerSecond),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            actions = {
                IconButton(onClick = { if (!tailState.running) filtersOpen = true }) {
                    Icon(Icons.Default.FilterList, contentDescription = stringResource(R.string.cd_filter))
                }
            }
        )

        // ---- 一行状态（与首页一致，整行可点击进连接页） ----
        HomeStatusBar(
            result = null,
            checking = false,
            phase = CheckPhase(),
            channelConnected = channelState.connected,
            channelLabel = channelState.deviceLabel,
            onGoConnect = onGoConnect,
            onRetry = {}
        )

        // ---- 控制行 + 工具行 + 日志流 ----
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 控制行：开始（44dp 缩短）+ 暂停/停止/复制/清空小图标
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Button(
                    onClick = viewModel::start,
                    enabled = !tailState.running,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                ) {
                    Text(
                        if (tailState.running) stringResource(R.string.tail_running)
                        else stringResource(R.string.tail_start),
                        fontSize = 14.sp,
                        maxLines = 1
                    )
                }
                IconButton(
                    onClick = viewModel::togglePause,
                    enabled = tailState.running,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        if (viewModel.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (viewModel.paused) stringResource(R.string.tail_resume) else stringResource(R.string.tail_pause),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = viewModel::stop,
                    enabled = tailState.running,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.tail_stop), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(matched.joinToString("\n") { it.raw }))
                        Toast.makeText(context, copiedLinesText, Toast.LENGTH_SHORT).show()
                    },
                    enabled = matched.isNotEmpty(),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.cd_copy_all), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(
                    onClick = viewModel::clear,
                    enabled = lines.isNotEmpty(),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.tail_clear), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 工具行：搜索 + 进程 + 级别 + 缓冲区，一行放下
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                EllipseTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = stringResource(R.string.tail_search_hint),
                    modifier = Modifier.weight(1f)
                )
                // 与首页一致：点「进程」直接弹应用选择器（v1.8.2 起不再展开输入行）
                Surface(
                    onClick = { viewModel.showPicker(true) },
                    shape = CircleShape,
                    color = if (viewModel.packageName.isNotBlank()) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }
                ) {
                    Text(
                        viewModel.packageName.trim().take(10).ifBlank { stringResource(R.string.process) },
                        fontSize = 11.sp,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                    )
                }
                FilterDropdown(
                    label = "≥${viewModel.priority.letter}",
                    options = LogPriority.entries.toList(),
                    optionLabel = { "${it.letter} ${it.label}" },
                    isSelected = { viewModel.priority == it },
                    onSelect = viewModel::onPriorityChange
                )
            }

            // ---- 快捷芯片行（横向滚动）：只看错误 / 缓冲区多选 / 匹配模式 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = viewModel.errorsOnly,
                    onClick = viewModel::toggleErrorsOnly,
                    label = { Text(stringResource(R.string.errors_only), fontSize = 11.sp, maxLines = 1) }
                )
                LogBuffer.entries.forEach { buffer ->
                    FilterChip(
                        selected = buffer in viewModel.buffers,
                        onClick = { viewModel.toggleBuffer(buffer) },
                        label = { Text(buffer.value, fontSize = 11.sp, maxLines = 1) }
                    )
                }
                FilterChip(
                    selected = !filterMode,
                    onClick = { filterMode = !filterMode },
                    label = {
                        Text(
                            if (filterMode) stringResource(R.string.search_as_filter)
                            else stringResource(R.string.search_as_highlight),
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                )
            }

            // 日志区占据剩余全部空间；往回翻看历史时自动暂停跟随；点行弹底部详情面板
            LogListView(
                entries = displayed,
                listState = listState,
                fontSize = settings.fontSize,
                monoFont = settings.monoFont,
                highlight = search,
                autoScroll = true,
                modifier = Modifier.weight(1f),
                emptyHint = if (tailState.running) stringResource(R.string.tail_waiting) else stringResource(R.string.tail_tap_start),
                reverseLayout = true,
                onLineClick = { entry -> selectedLine = entry },
                copyFeedback = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            )
        }
    }

    // ---- 筛选底部面板（低频项）：关键词 ----
    if (filtersOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { filtersOpen = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.cd_filter), style = MaterialTheme.typography.titleMedium)
                EllipseTextField(
                    value = viewModel.keywordInput,
                    onValueChange = viewModel::onKeywordChange,
                    placeholder = stringResource(R.string.tail_keywords_hint),
                    leadingLabel = stringResource(R.string.tail_keywords_label)
                )
                Text(
                    stringResource(R.string.tail_keywords_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // ---- 行详情底部面板 ----
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

    if (viewModel.pickerVisible) {
        AppPickerDialog(
            apps = viewModel.apps,
            loading = viewModel.appsLoading,
            provider = viewModel.appInfoProvider,
            onDismiss = { viewModel.showPicker(false) },
            onPick = viewModel::pickPackage,
            onRefresh = viewModel::refreshApps
        )
    }
}
