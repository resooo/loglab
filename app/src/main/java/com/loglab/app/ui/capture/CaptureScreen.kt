package com.loglab.app.ui.capture

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import com.loglab.app.ui.components.CopyableText
import com.loglab.app.ui.components.EllipseTextField
import com.loglab.app.ui.components.FilterDropdown
import com.loglab.app.ui.components.HomeStatusBar
import com.loglab.app.ui.components.LogLineSheet
import com.loglab.app.ui.components.LogListView
import com.loglab.app.ui.components.PackagePickerDialog

/**
 * 首页（抓取页）：主按钮 C 位 + 状态一行 + 配置可见。
 *  - 状态行：启动智能检查与通道状态合并为一行，整行可点击 → 连接页；
 *  - 主按钮：状态行正下方，未连接时禁用变灰；
 *  - 配置摘要：当前抓取配置以芯片展示，点击打开筛选面板；
 *  - 顶栏「?」：使用方法介绍页。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CaptureScreen(
    onGoConnect: () -> Unit = {},
    onGoGuide: () -> Unit = {},
    onGoExport: () -> Unit = {},
    viewModel: CaptureViewModel = hiltViewModel()
) {
    val settings by viewModel.appSettings.collectAsState()
    val channelState by viewModel.channelState.collectAsState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var search by remember { mutableStateOf("") }
    var filtersOpen by remember { mutableStateOf(false) }
    var selectedLine by remember { mutableStateOf<LogEntry?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val displayed = remember(viewModel.entries, search) {
        if (search.isBlank()) viewModel.entries
        else viewModel.entries.filter { it.raw.contains(search, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- 顶栏：使用方法 + 筛选 + 导出（复制/清空在主按钮行，⋮ 菜单已删）----
        TopAppBar(
            title = { Text("抓取") },
            actions = {
                IconButton(onClick = onGoGuide) {
                    Icon(Icons.Default.HelpOutline, contentDescription = "使用方法")
                }
                IconButton(onClick = { filtersOpen = true }) {
                    Icon(Icons.Default.FilterList, contentDescription = "更多筛选")
                }
                IconButton(onClick = onGoExport) {
                    Icon(Icons.Default.SaveAlt, contentDescription = "导出日志")
                }
            }
        )

        // ---- 状态行：检查条 + 通道条合一，整行可点击，点击进连接页 ----
        val checkPhase by viewModel.checkPhase.collectAsState()
        HomeStatusBar(
            result = viewModel.startupResult,
            checking = viewModel.startupChecking,
            phase = checkPhase,
            channelConnected = channelState.connected,
            channelLabel = channelState.deviceLabel,
            onGoConnect = onGoConnect,
            onRetry = { viewModel.runStartupCheck("手动重试") }
        )

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
        androidx.compose.runtime.LaunchedEffect(viewModel.startupResult) {
            val r = viewModel.startupResult ?: return@LaunchedEffect
            if (r.connected) {
                kotlinx.coroutines.delay(4_000)
                viewModel.clearStartupResult()
            }
        }
        // 通道激活后（如配对完成、重连成功），清除过期的"未配对/不可达"提示
        androidx.compose.runtime.LaunchedEffect(channelState.connected) {
            if (channelState.connected && viewModel.startupResult?.connected == false) {
                viewModel.clearStartupResult()
            }
        }

        // ---- 主按钮 + 配置摘要 + 搜索 + 日志流 ----
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // ---- 主按钮行：开始抓取（缩短）+ 复制 / 清空小图标 ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Button(
                    onClick = viewModel::capture,
                    enabled = !viewModel.busy && channelState.connected,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                ) {
                    Text(
                        if (viewModel.busy) "抓取中…" else "开始抓取日志",
                        fontSize = 14.sp,
                        maxLines = 1
                    )
                }
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(displayed.joinToString("\n") { it.raw }))
                        Toast.makeText(context, "已复制 ${displayed.size} 行", Toast.LENGTH_SHORT).show()
                    },
                    enabled = displayed.isNotEmpty(),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "复制全部",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { viewModel.clearLogs() },
                    enabled = viewModel.entries.isNotEmpty(),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "清空日志",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (viewModel.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            viewModel.status?.let {
                CopyableText(text = it)
            }

            // ---- 工具行：搜索 + 进程 + 级别 + 行数，一行放下（缓冲区在「更多筛选」里）----
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
                    placeholder = "搜索日志…",
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
                    label = "${viewModel.maxLines}",
                    options = listOf(200, 1000, 5000, 20000, 100000),
                    optionLabel = { "$it 行" },
                    isSelected = { viewModel.maxLines == it },
                    onSelect = { viewModel.onMaxLinesChange(it.toString()) }
                )
            }

            // 进程展开行：点「进程 ▸」后才出现，不占常驻空间
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

            // 日志区占据剩余全部空间；点行弹底部详情面板
            LogListView(
                entries = displayed,
                listState = listState,
                fontSize = settings.fontSize,
                monoFont = settings.monoFont,
                highlight = search,
                modifier = Modifier.weight(1f),
                emptyHint = "连接成功后，点上方按钮即可抓取日志\n首次使用点右上角 ? 查看使用方法",
                onLineClick = { entry -> selectedLine = entry },
                copyFeedback = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            )
        }
    }

    // ---- 筛选底部面板（低频高级项）：Tag / 关键词 / 抓取前清空 ----
    // 行数 / 缓冲区 / 级别已收进开始按钮下的下拉菜单；包名在进程行展开输入
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

                // 缓冲区：低频配置，从首页工具行收进这里
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("缓冲区", style = MaterialTheme.typography.labelMedium)
                    LogBuffer.entries.forEach { buffer ->
                        FilterChip(
                            selected = viewModel.buffer == buffer,
                            onClick = { viewModel.onBufferChange(buffer) },
                            label = { Text(buffer.value) }
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        EllipseTextField(
                            value = viewModel.tagInput,
                            onValueChange = viewModel::onTagInputChange,
                            placeholder = "Tag 过滤，如 flutter",
                            leadingLabel = "Tag"
                        )
                    }
                    OutlinedButton(onClick = { viewModel.addTagFilter() }) { Text("添加") }
                }

                if (viewModel.tagFilters.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        viewModel.tagFilters.forEach { filter ->
                            InputChip(
                                selected = true,
                                onClick = { viewModel.removeTagFilter(filter) },
                                label = { Text("${filter.tag}:${filter.priority.letter}") },
                                trailingIcon = {
                                    Icon(Icons.Default.Clear, contentDescription = "移除", modifier = Modifier.padding(end = 2.dp))
                                }
                            )
                        }
                    }
                }

                EllipseTextField(
                    value = viewModel.keywordInput,
                    onValueChange = viewModel::onKeywordChange,
                    placeholder = "关键词，逗号分隔，如 error,timeout",
                    leadingLabel = "关键词"
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = viewModel.clearFirst, onCheckedChange = viewModel::onClearFirstChange)
                    Text("抓取前清空缓冲区", style = MaterialTheme.typography.bodyMedium)
                }
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
            },
            // "仅看此 Tag"：直接把 Tag 填入结果搜索框（前端过滤，点 × 即可取消）
            onTagFilter = { tag ->
                search = tag
                selectedLine = null
                Toast.makeText(context, "已在结果中过滤：$tag", Toast.LENGTH_SHORT).show()
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

/**
 * 胶囊下拉筛选已提取为公共组件：见 ui/components/FilterDropdown.kt
 */
