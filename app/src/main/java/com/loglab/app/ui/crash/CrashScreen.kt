package com.loglab.app.ui.crash

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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

/**
 * 应用崩溃监控页（小白向，LogFox 简洁化）：
 * 顶栏收纳读取历史/分享/清空；空态居中大按钮 + 三步引导；
 * 记录为紧凑两行卡片，点击弹底部面板看完整堆栈。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashScreen(viewModel: CrashViewModel = hiltViewModel()) {
    val events by viewModel.events.collectAsState()
    val monitoring by viewModel.monitoring.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf(false) }
    var selectedEvent by remember { mutableStateOf<CrashEvent?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- 顶栏：开始/历史/分享/清空 ----
        TopAppBar(
            title = { Text(stringResource(R.string.tab_crash)) },
            actions = {
                if (monitoring) {
                    IconButton(onClick = viewModel::stop) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_stop_monitor), tint = MaterialTheme.colorScheme.error)
                    }
                } else {
                    IconButton(onClick = viewModel::start) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.cd_start_monitor))
                    }
                }
                IconButton(onClick = viewModel::readHistory) {
                    Icon(Icons.Default.History, contentDescription = stringResource(R.string.cd_read_history))
                }
                IconButton(onClick = viewModel::shareAll, enabled = events.isNotEmpty()) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share))
                }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // stringResource 不能在 onClick（非 Composable 上下文）里调用，提前取好
                    val clearedText = stringResource(R.string.crash_cleared)
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.crash_menu_clear)) },
                        onClick = {
                            menuOpen = false
                            viewModel.clear()
                            selectedEvent = null
                            Toast.makeText(context, clearedText, Toast.LENGTH_SHORT).show()
                        },
                        enabled = events.isNotEmpty(),
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }
                    )
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ---- 一行监控状态 ----
            Row(
                modifier = Modifier.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (monitoring) Color(0xFFFF6B6B) else Color(0xFF8A8F98),
                            CircleShape
                        )
                )
                Text(
                    if (monitoring) stringResource(R.string.crash_monitoring) else stringResource(R.string.crash_not_monitoring),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            message?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (msg.contains("失败") || msg.contains("中断") || msg.contains("无法"))
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { viewModel.store.setMessage(null) }
                )
            }

            // ---- 时间范围筛选：今天 / 近7天 ----
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.loglab.app.ui.crash.CrashRange.entries.forEach { r ->
                    FilterChip(
                        selected = viewModel.range == r,
                        onClick = { viewModel.onRangeChange(r) },
                        label = { Text(
                            if (r == CrashRange.TODAY) stringResource(R.string.crash_range_today)
                            else stringResource(R.string.crash_range_7d),
                            fontSize = 12.sp
                        ) }
                    )
                }
            }

            // ---- 主区域 ----
            Box(modifier = Modifier.weight(1f)) {
                val shownEvents = viewModel.filteredEvents
                when {
                    // 空态：居中大按钮 + 三步引导（小白更直观，已确认保留）
                    !monitoring && shownEvents.isEmpty() -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 48.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Button(
                            onClick = viewModel::start,
                            modifier = Modifier.heightIn(min = 56.dp)
                        ) {
                            Text(stringResource(R.string.crash_start), fontSize = 17.sp)
                        }
                        Spacer(Modifier.width(4.dp))
                        Column(
                            modifier = Modifier.padding(top = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(stringResource(R.string.crash_guide_1), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.crash_guide_2), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.crash_guide_3), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                stringResource(R.string.crash_guide_history),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    // 监控中：状态说明 + 记录列表
                    else -> Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (monitoring) {
                            Text(
                                stringResource(R.string.crash_monitor_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = viewModel::stop,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(stringResource(R.string.crash_stop)) }
                        }
                        Text(
                            stringResource(R.string.crash_count, shownEvents.size),
                            style = MaterialTheme.typography.titleSmall
                        )
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (shownEvents.isEmpty()) {
                                item {
                                    Text(
                                        if (monitoring) stringResource(R.string.crash_empty_monitoring) else stringResource(R.string.crash_empty_list),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 24.dp)
                                    )
                                }
                            }
                            items(shownEvents.size) { index ->
                                val event = shownEvents[index]
                                CrashRow(
                                    event = event,
                                    icon = viewModel.icon(event.packageName),
                                    appLabel = viewModel.appLabel(event),
                                    onClick = { selectedEvent = event }
                                )
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f),
                                    thickness = 0.5.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- 崩溃详情底部面板：完整堆栈 + 复制 ----
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
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${event.type} · ${event.time}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    event.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    event.stack,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
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

/** 紧凑两行崩溃卡片：行1 图标+应用名/包名+时间，行2 类型+摘要 */
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
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 应用图标：取不到时显示圆角占位（Native 崩溃等解析不到包名的场景）
        if (icon != null) {
            Image(
                bitmap = remember(icon) { icon.toBitmap(64, 64).asImageBitmap() },
                contentDescription = stringResource(R.string.cd_app_icon),
                modifier = Modifier.size(36.dp)
            )
        } else {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("?", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // 有应用名显示应用名，包名作副标题省略；都没有显示未知应用
                    appLabel ?: event.displayPackage,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    event.time,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                // 摘要与类型相同时只显示一个，避免「Java 崩溃 · Java 崩溃」
                if (event.summary.isNotBlank() && event.summary != event.type) {
                    "${event.type} · ${event.summary}"
                } else {
                    event.type
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 应用名与包名不同时补一行包名，便于定位具体应用
            if (appLabel != null && event.packageName != null && appLabel != event.packageName) {
                Text(
                    event.displayPackage,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
