package com.loglab.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loglab.app.data.model.LogEntry
import com.loglab.app.ui.theme.LogColors
import kotlinx.coroutines.launch

/**
 * 日志列表：等宽字体、按级别着色、支持关键词高亮。
 *
 * 布局与跟随策略（参考 LogFox 的显示方式）：
 *  - 默认单行省略显示，点击该行展开/收起全文——行高统一让快速滑动流畅不闪退；
 *    传入 [onLineClick] 时点击改为回调（用于弹出底部详情面板），行内不再展开；
 *  - [reverseLayout]（实时跟踪用）：列表反转，最新日志固定在视觉底部。
 *    新日志插入 index 0 时视图天然贴底跟随，无需 scrollToItem，
 *    老日志裁剪发生在视口上方远处——彻底避开「滚动位置 vs 头部删除」的竞态闪退；
 *  - 正序模式（一次性抓取用）：保持在底部附近时自动贴底跟随；
 *  - 翻看历史时暂停跟随，出现「↓」按钮一键回最新。
 *
 * 复制：长按单行复制；[copyFeedback] 用于 Toast 反馈。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogListView(
    entries: List<LogEntry>,
    listState: LazyListState,
    fontSize: Int,
    monoFont: Boolean,
    highlight: String = "",
    autoScroll: Boolean = false,
    modifier: Modifier = Modifier,
    emptyHint: String = "暂无日志",
    reverseLayout: Boolean = false,
    onLineClick: ((LogEntry) -> Unit)? = null,
    copyFeedback: (String) -> Unit = {}
) {
    val clipboard = LocalClipboardManager.current

    if (entries.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(emptyHint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        }
        return
    }

    // 用户是否停在「最新日志」附近
    val atBottom by remember(reverseLayout) {
        derivedStateOf {
            val info = listState.layoutInfo
            if (reverseLayout) {
                // 反转：最新日志在 index 0（视觉底部），可见项里出现 index<=1 即视为贴底
                val minVisible = info.visibleItemsInfo.minOfOrNull { it.index }
                minVisible == null || minVisible <= 1
            } else {
                // 正序：可见最后一项在倒数 2 项内视为贴底
                val last = info.visibleItemsInfo.lastOrNull()
                last == null || last.index >= info.totalItemsCount - 2
            }
        }
    }

    // 正序列表需要主动贴底滚动；反转列表新日志插入 index 0 天然跟随，无需滚动
    LaunchedEffect(entries.size, autoScroll) {
        if (!reverseLayout && autoScroll && atBottom && entries.isNotEmpty()) {
            val viewport = listState.layoutInfo.viewportEndOffset
            listState.scrollToItem(
                (entries.size - 1).coerceAtLeast(0),
                scrollOffset = viewport
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            reverseLayout = reverseLayout,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(entries, key = { it.id }) { entry ->
                var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
                LogLine(
                    entry = entry,
                    fontSize = fontSize,
                    monoFont = monoFont,
                    highlight = highlight,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    onLongClick = {
                        clipboard.setText(AnnotatedString(entry.raw))
                        copyFeedback("已复制该行日志")
                    },
                    onClick = {
                        if (onLineClick != null) onLineClick(entry)
                        else expanded = !expanded
                    }
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f),
                    thickness = 0.5.dp
                )
            }
        }

        // 翻看历史时提供一键回最新（实时跟踪时尤其有用）
        if (autoScroll && !atBottom) {
            val scope = rememberCoroutineScope()
            SmallFloatingActionButton(
                onClick = {
                    scope.launch {
                        if (reverseLayout) {
                            listState.scrollToItem(0)
                        } else {
                            val viewport = listState.layoutInfo.viewportEndOffset
                            listState.scrollToItem(
                                (entries.size - 1).coerceAtLeast(0),
                                scrollOffset = viewport
                            )
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
            ) {
                Text("↓", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogLine(
    entry: LogEntry,
    fontSize: Int,
    monoFont: Boolean,
    highlight: String,
    maxLines: Int,
    onLongClick: () -> Unit,
    onClick: () -> Unit
) {
    val family = if (monoFont) FontFamily.Monospace else FontFamily.Default
    val levelColor = LogColors.forPriority(entry.priority)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary

    // 富文本构建成本高（逐字符定位高亮区间），必须缓存：
    // 否则每次重组都重建，大列表快速滑动时主线程海量分配，造成卡顿甚至闪退。
    // key 含 id（内容唯一）+ 主题相关色值，保证任何依赖变化时正确重建。
    val annotated = remember(entry.id, fontSize, monoFont, highlight, muted, levelColor, primary) {
        buildAnnotatedString {
            if (entry.timestamp.isNotEmpty()) {
                append(entry.timestamp)
                addStyle(SpanStyle(color = muted, fontSize = (fontSize - 1).sp), 0, entry.timestamp.length)
                append("  ")
            }
            val prefixLength = length
            if (entry.parsed) {
                val level = "[${entry.priority}]"
                append(level)
                addStyle(SpanStyle(color = levelColor, fontSize = fontSize.sp), prefixLength, prefixLength + level.length)
                append(" ")
                val tagStart = length
                append(entry.tag)
                addStyle(
                    SpanStyle(color = primary, fontSize = fontSize.sp),
                    tagStart,
                    tagStart + entry.tag.length
                )
                append(": ")
                append(entry.message)
            } else {
                append(entry.raw.substringAfter(entry.timestamp))
            }

            if (highlight.isNotBlank()) {
                val text = toString()
                var index = text.indexOf(highlight, ignoreCase = true)
                while (index >= 0) {
                    addStyle(
                        SpanStyle(background = Color(0x66FFD54F), color = Color.Black),
                        index,
                        index + highlight.length
                    )
                    index = text.indexOf(highlight, index + highlight.length, ignoreCase = true)
                }
            }
        }
    }

    Text(
        text = annotated,
        fontFamily = family,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 1.35f).sp,
        maxLines = maxLines,
        overflow = if (maxLines == 1) TextOverflow.Ellipsis else TextOverflow.Visible,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 2.dp)
    )
}
