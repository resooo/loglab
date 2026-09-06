package com.loglab.app.ui.tail

import android.widget.Toast
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.loglab.app.core.logcat.LogBuffer
import com.loglab.app.core.logcat.LogPriority
import com.loglab.app.data.model.LogEntry
import com.loglab.app.ui.components.EllipseTextField
import com.loglab.app.ui.components.FilterDropdown
import com.loglab.app.ui.components.HomeStatusBar
import com.loglab.app.ui.components.LogLineSheet
import com.loglab.app.ui.components.LogListView
import com.loglab.app.ui.components.PackagePickerDialog
import androidx.compose.animation.AnimatedVisibility

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
    var filtersOpen by remember { mutableStateOf(false) }
    var selectedLine by remember { mutableStateOf<LogEntry?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // 匹配结果全量保留（「复制全部」使用）
    val matched = remember(lines, search) {
        if (search.isBlank()) lines else lines.filter { it.raw.contains(search, ignoreCase = true) }
    }
    // 渲染层只显示最近 TAIL_DISPLAY_LIMIT 条并倒序（配合 reverseLayout 贴底跟随）：
    // 数据层 5000 条全量裁剪对 UI 无感，同时把 LazyColumn 的 diff 规模压低，
    // 高频日志下不再卡顿/闪退
    val displayed = remember(matched) { matched.takeLast(TAIL_DISPLAY_LIMIT).asReversed() }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- 顶栏：标题 + 速率小字 + 筛选 ----
        TopAppBar(
            title = {
                Column {
                    Text("实时")
                    Text(
                        "已捕获 ${displayed.size} 行 · %.1f 行/秒".format(tailState.ratePerSecond),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            actions = {
                IconButton(onClick = { if (!tailState.running) filtersOpen = true }) {
                    Icon(Icons.Default.FilterList, contentDescription = "更多筛选")
                }
            }
        )

        // ---- 一行状态（与首页一致，整行可点击进连接页） ----
        HomeStatusBar(
            result = null,
            checking = false,
            phase = "",
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
                ) { Text(if (tailState.running) "跟踪中…" else "开始跟踪", fontSize = 14.sp, maxLines = 1) }
                IconButton(
                    onClick = viewModel::togglePause,
                    enabled = tailState.running,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        if (viewModel.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (viewModel.paused) "继续" else "暂停",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = viewModel::stop,
                    enabled = tailState.running,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "停止", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(matched.joinToString("\n") { it.raw }))
                        Toast.makeText(context, "已复制 ${matched.size} 行", Toast.LENGTH_SHORT).show()
                    },
                    enabled = matched.isNotEmpty(),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "复制全部", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(
                    onClick = viewModel::clear,
                    enabled = lines.isNotEmpty(),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "清空", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 工具行：搜索 + 进程 + 级别 + 缓冲区，一行放下
            var procOpen by remember(viewModel.packageName.isNotBlank()) {
                mutableStateOf(viewModel.packageName.isNotBlank())
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                EllipseTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = "搜索实时日志…",
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    onClick = { procOpen = !procOpen },
                    shape = CircleShape,
                    color = if (viewModel.packageName.isNotBlank()) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }
                ) {
                    Text(
                        viewModel.packageName.trim().take(10).ifBlank { "进程" } + if (procOpen) " ▾" else " ▸",
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
                FilterDropdown(
                    label = viewModel.buffer.value,
                    options = LogBuffer.entries.toList(),
                    optionLabel = { it.value },
                    isSelected = { viewModel.buffer == it },
                    onSelect = viewModel::onBufferChange
                )
            }

            // 进程展开行：点「进程 ▸」后才出现
            AnimatedVisibility(visible = procOpen) {
                EllipseTextField(
                    value = viewModel.packageName,
                    onValueChange = viewModel::onPackageChange,
                    placeholder = "包名，留空=全部进程",
                    trailing = {
                        IconButton(onClick = { viewModel.showPicker(true) }) {
                            Icon(
                                Icons.Default.Apps,
                                contentDescription = "选择包名",
                                modifier = Modifier.heightIn(max = 20.dp)
                            )
                        }
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
                emptyHint = if (tailState.running) "等待日志输出…" else "点击「开始跟踪」",
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
                Text("更多筛选", style = MaterialTheme.typography.titleMedium)
                EllipseTextField(
                    value = viewModel.keywordInput,
                    onValueChange = viewModel::onKeywordChange,
                    placeholder = "关键词，逗号分隔",
                    leadingLabel = "关键词"
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
                Toast.makeText(context, "已复制该行日志", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (viewModel.pickerVisible) {
        PackagePickerDialog(
            suggestions = viewModel.packageSuggestions,
            onDismiss = { viewModel.showPicker(false) },
            onPick = viewModel::pickPackage,
            onRefresh = viewModel::refreshPackages
        )
    }
}
