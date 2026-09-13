package com.loglab.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loglab.app.R
import com.loglab.app.core.logcat.LogBuffer
import com.loglab.app.core.logcat.LogPriority
import com.loglab.app.ui.theme.V4

/** v4 筛选层的日志级别选项：V 全部 / D / I / W 以上 / E（语义都是「≥该级别」） */
private val LEVEL_OPTIONS = listOf(
    LogPriority.VERBOSE,
    LogPriority.DEBUG,
    LogPriority.INFO,
    LogPriority.WARN,
    LogPriority.ERROR
)

/** v4 筛选层的保留行数选项（与设计稿一致） */
private val MAX_LINES_OPTIONS = listOf(200, 1000, 5000, 20000, 100000)

/** v4 筛选层的缓冲区选项（main / system / events / crash，多选） */
private val BUFFER_OPTIONS = listOf(
    LogBuffer.MAIN,
    LogBuffer.SYSTEM,
    LogBuffer.EVENTS,
    LogBuffer.CRASH
)

/**
 * v4「筛选与抓取设置」底部弹出层。
 *
 * 结构（自上而下）：
 *  - 日志级别（单选）：V 全部 / D / I / W 以上 / E
 *  - 最多保留行数（单选）：200 / 1000 / 5000 / 20000 / 100000
 *  - 缓冲区（多选）：main / system / events / crash
 *  - 进程与其它：应用（点开进程选择层）、启动抓取后继续（秒数循环）
 *  - [重置] [完成]
 *
 * 草稿模式：所有改动先落在本地，只有点「完成」才回调，避免误触即时改配置。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    priority: LogPriority,
    maxLines: Int,
    buffers: Set<LogBuffer>,
    packageName: String,
    startupMode: Boolean,
    startupTailSec: Int,
    onDismiss: () -> Unit,
    onOpenProcessPicker: () -> Unit,
    onApply: (priority: LogPriority, maxLines: Int, buffers: Set<LogBuffer>, startupTailSec: Int) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var draftPriority by rememberSaveable { mutableStateOf(priority.name) }
    var draftMaxLines by rememberSaveable { mutableStateOf(maxLines) }
    var draftBuffers by rememberSaveable { mutableStateOf(buffers.map { it.name }.toSet()) }
    var draftTailSec by rememberSaveable { mutableStateOf(startupTailSec) }

    val selectedPriority = LogPriority.entries.firstOrNull { it.name == draftPriority } ?: LogPriority.VERBOSE
    val selectedBuffers = draftBuffers.mapNotNull { n -> LogBuffer.entries.firstOrNull { it.name == n } }.toSet()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        containerColor = V4.Bg,
        dragHandle = null,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = 18.dp)
        ) {
            V4SheetGrip(modifier = Modifier.align(Alignment.CenterHorizontally))
            V4SheetHeader(text = stringResource(R.string.v4_filter_title))

            // —— 日志级别 ——
            V4Tagline(text = stringResource(R.string.v4_filter_level))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LEVEL_OPTIONS.forEach { p ->
                    V4OptChip(
                        text = levelLabel(p),
                        selected = p == selectedPriority,
                        onClick = { draftPriority = p.name }
                    )
                }
            }

            // —— 最多保留行数 ——
            V4Tagline(text = stringResource(R.string.v4_filter_max_lines))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MAX_LINES_OPTIONS.forEach { n ->
                    V4OptChip(
                        text = n.toString(),
                        selected = n == draftMaxLines,
                        onClick = { draftMaxLines = n }
                    )
                }
            }

            // —— 缓冲区（多选）——
            V4Tagline(text = stringResource(R.string.v4_filter_buffers))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BUFFER_OPTIONS.forEach { b ->
                    val on = b in selectedBuffers
                    V4OptChip(
                        text = b.value,
                        selected = on,
                        onClick = {
                            draftBuffers = if (on) draftBuffers - b.name else draftBuffers + b.name
                        }
                    )
                }
            }

            // —— 进程与其它 ——
            V4Tagline(text = stringResource(R.string.v4_filter_process_section))
            V4Field(onClick = onOpenProcessPicker) {
                Text(
                    text = stringResource(R.string.v4_filter_app_label),
                    fontSize = 12.5.sp,
                    color = V4.Muted
                )
                V4FieldValue(
                    text = (packageName.ifBlank { stringResource(R.string.v4_filter_app_unset) }) + " ›"
                )
            }
            V4Field {
                Text(
                    text = stringResource(R.string.v4_filter_startup_continue),
                    fontSize = 12.5.sp,
                    color = V4.Text
                )
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (startupMode) {
                        V4OptChip(
                            text = "$draftTailSec " + stringResource(R.string.v4_seconds),
                            selected = false,
                            onClick = { draftTailSec = nextTailSec(draftTailSec) }
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.v4_filter_startup_off),
                            fontSize = 12.5.sp,
                            color = V4.Muted
                        )
                    }
                }
            }

            V4SheetActions {
                V4SheetButton(
                    text = stringResource(R.string.v4_reset),
                    onClick = {
                        draftPriority = LogPriority.VERBOSE.name
                        draftMaxLines = 5000
                        draftBuffers = setOf(LogBuffer.MAIN.name, LogBuffer.CRASH.name)
                        draftTailSec = 5
                    },
                    modifier = Modifier.weight(1f),
                    primary = false
                )
                V4SheetButton(
                    text = stringResource(R.string.v4_done),
                    onClick = {
                        onApply(
                            selectedPriority,
                            draftMaxLines,
                            selectedBuffers.ifEmpty { setOf(LogBuffer.MAIN) },
                            draftTailSec
                        )
                    },
                    modifier = Modifier.weight(1f),
                    primary = true
                )
            }
        }
    }
}

/** 级别按钮文案：「V 全部」「W 以上」「E」 */
private fun levelLabel(p: LogPriority): String = when (p) {
    LogPriority.VERBOSE -> "V 全部"
    LogPriority.WARN -> "W 以上"
    else -> p.letter.toString()
}

/** 启动抓取后继续的秒数循环：3 → 5 → 10 → 20 → 30 → 3 */
private fun nextTailSec(cur: Int): Int {
    val cycle = listOf(3, 5, 10, 20, 30)
    val i = cycle.indexOf(cur)
    return cycle[(i + 1) % cycle.size]
}
