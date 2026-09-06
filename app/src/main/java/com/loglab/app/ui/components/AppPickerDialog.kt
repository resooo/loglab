package com.loglab.app.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.loglab.app.core.apps.AppInfo
import com.loglab.app.core.apps.AppInfoProvider

/**
 * 应用选择器（图标 + 应用名 + 运行状态）：
 * 列表数据来自本机 PackageManager（core/apps/AppInfoProvider），图标按需加载，
 * LazyColumn 只渲染可见项，几百个应用也不卡。
 */
@Composable
fun AppPickerDialog(
    apps: List<AppInfo>,
    loading: Boolean,
    provider: AppInfoProvider,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onRefresh: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            apps
        } else {
            apps.filter {
                it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择应用") },
        text = {
            Column {
                EllipseTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索应用名或包名…",
                    trailing = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "清除搜索")
                            }
                        }
                    }
                )
                Text(
                    when {
                        loading -> "正在读取应用列表…"
                        else -> "${filtered.size} / ${apps.size} 个应用（前台/运行中排在前）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    if (!loading && apps.isEmpty()) {
                        item {
                            Text(
                                "未获取到应用列表，请确认已连接后点「刷新」重试",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else if (!loading && filtered.isEmpty()) {
                        item {
                            Text(
                                "没有匹配「${query.trim()}」的应用",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(filtered, key = { it.packageName }) { app ->
                            AppRow(
                                app = app,
                                icon = remember(app.packageName) { provider.icon(app.packageName)?.toImageBitmap() },
                                onClick = { onPick(app.packageName) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onRefresh) { Text("刷新") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun AppRow(
    app: AppInfo,
    icon: androidx.compose.ui.graphics.ImageBitmap?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp)
            )
        } else {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {}
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (app.foreground) Badge("前台", MaterialTheme.colorScheme.primary)
                else if (app.running) Badge("运行中", MaterialTheme.colorScheme.tertiary)
            }
            Text(
                app.packageName + (if (app.isSystem) " · 系统" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier.padding(start = 6.dp)
    )
}

private fun android.graphics.drawable.Drawable.toImageBitmap(): androidx.compose.ui.graphics.ImageBitmap {
    val size = 48
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, size, size)
    draw(canvas)
    return bitmap.asImageBitmap()
}
