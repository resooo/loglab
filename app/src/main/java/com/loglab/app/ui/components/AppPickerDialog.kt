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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.loglab.app.R
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
        title = { Text(stringResource(R.string.picker_title)) },
        text = {
            Column {
                EllipseTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.picker_search_hint),
                    trailing = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_clear_search))
                            }
                        }
                    }
                )
                Text(
                    when {
                        loading -> stringResource(R.string.picker_loading)
                        else -> stringResource(R.string.picker_count_fmt, filtered.size, apps.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    if (!loading && apps.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.picker_empty),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else if (!loading && filtered.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.picker_no_match_fmt, query.trim()),
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
        confirmButton = { TextButton(onClick = onRefresh) { Text(stringResource(R.string.picker_refresh)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.picker_close)) } }
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
                if (app.foreground) Badge(stringResource(R.string.badge_fg), MaterialTheme.colorScheme.primary)
                else if (app.running) Badge(stringResource(R.string.badge_running), MaterialTheme.colorScheme.tertiary)
            }
            Text(
                app.packageName + (if (app.isSystem) stringResource(R.string.picker_system_suffix) else ""),
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
