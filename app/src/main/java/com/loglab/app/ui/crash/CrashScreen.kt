package com.loglab.app.ui.crash

import android.widget.Toast
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
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
            title = { Text("崩溃") },
            actions = {
                if (monitoring) {
                    IconButton(onClick = viewModel::stop) {
                        Icon(Icons.Default.Delete, contentDescription = "停止监控", tint = MaterialTheme.colorScheme.error)
                    }
                } else {
                    IconButton(onClick = viewModel::start) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "开始监控")
                    }
                }
                IconButton(onClick = viewModel::readHistory) {
                    Icon(Icons.Default.History, contentDescription = "读取历史崩溃")
                }
                IconButton(onClick = viewModel::shareAll, enabled = events.isNotEmpty()) {
                    Icon(Icons.Default.Share, contentDescription = "分享")
                }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("清空记录") },
                        onClick = {
                            menuOpen = false
                            viewModel.clear()
                            selectedEvent = null
                            Toast.makeText(context, "已清空", Toast.LENGTH_SHORT).show()
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
                    if (monitoring) "正在监控应用崩溃" else "未开启监控",
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

            // ---- 主区域 ----
            Box(modifier = Modifier.weight(1f)) {
                when {
                    // 空态：居中大按钮 + 三步引导（小白更直观，已确认保留）
                    !monitoring && events.isEmpty() -> Column(
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
                            Text("开始监控崩溃", fontSize = 17.sp)
                        }
                        Spacer(Modifier.width(4.dp))
                        Column(
                            modifier = Modifier.padding(top = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("1. 点「开始监控崩溃」", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("2. 去打开会闪退的应用，让它崩溃一次", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("3. 回到这里，崩溃自动出现在列表", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "崩溃发生在监控之前也没关系，点顶栏 ⏱ 可补抓历史崩溃",
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
                                "去打开会闪退的应用，让它崩溃一次；崩溃发生后回到本页即可看到。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = viewModel::stop,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("停止监控") }
                        }
                        Text(
                            "已捕获 ${events.size} 个崩溃",
                            style = MaterialTheme.typography.titleSmall
                        )
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (events.isEmpty()) {
                                item {
                                    Text(
                                        if (monitoring) "还没有捕获到崩溃，去复现吧" else "还没有崩溃记录",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 24.dp)
                                    )
                                }
                            }
                            items(events.size) { index ->
                                val event = events[index]
                                CrashRow(
                                    event = event,
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
                        Toast.makeText(context, "已复制堆栈", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("复制堆栈") }
            }
        }
    }
}

/** 紧凑两行崩溃卡片：行1 包名+时间，行2 类型+摘要 */
@Composable
private fun CrashRow(
    event: CrashEvent,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                event.displayPackage,
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
    }
}
