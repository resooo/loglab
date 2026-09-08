package com.loglab.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loglab.app.R
import com.loglab.app.data.model.LogEntry
import com.loglab.app.ui.theme.LogColors

/**
 * 点某一行日志弹出的底部面板（LogFox 式）：
 * 完整原文（等宽、可滚动）+ 复制 + 仅看此 Tag。
 *
 * [onTagFilter] 为 null 时不显示「仅看此 Tag」（如实时页没有 Tag 过滤功能）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogLineSheet(
    entry: LogEntry,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onTagFilter: ((String) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 头部：级别字母（按级别着色）+ Tag
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (entry.parsed) {
                    Text(
                        "${entry.priority}",
                        color = LogColors.forPriority(entry.priority),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        entry.tag,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 1
                    )
                }
            }

            // 完整原文
            Text(
                entry.raw,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            )

            // 操作
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onCopy(entry.raw) },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.log_copy_raw)) }
                if (onTagFilter != null && entry.parsed && entry.tag.isNotBlank()) {
                    OutlinedButton(
                        onClick = { onTagFilter(entry.tag) },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.log_filter_tag)) }
                }
            }
        }
    }
}
