package com.loglab.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 应用包名选择对话框：设备上应用动辄数百个，内置搜索框本地过滤，
 * 避免长列表里手动滚动查找。
 */
@Composable
fun PackagePickerDialog(
    suggestions: List<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onRefresh: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(suggestions, query) {
        val q = query.trim()
        if (q.isEmpty()) suggestions
        else suggestions.filter { it.contains(q, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择应用包名") },
        text = {
            Column {
                EllipseTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索包名或应用名…",
                    trailing = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "清除搜索")
                            }
                        }
                    }
                )
                Text(
                    "${filtered.size} / ${suggestions.size} 个应用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    if (suggestions.isEmpty()) {
                        item {
                            Text(
                                "未获取到包名列表，请确认通道可用后点击「刷新」重试",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else if (filtered.isEmpty()) {
                        item {
                            Text(
                                "没有匹配「${query.trim()}」的包名",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(filtered, key = { it }) { pkg ->
                            TextButton(
                                onClick = { onPick(pkg) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    pkg,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onRefresh) { Text("刷新") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
