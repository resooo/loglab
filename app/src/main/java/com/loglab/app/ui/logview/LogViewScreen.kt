package com.loglab.app.ui.logview

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.loglab.app.core.report.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LogViewViewModel @Inject constructor(
    private val logger: AppLogger
) : ViewModel() {

    var lines by mutableStateOf<List<String>>(emptyList())
        private set

    fun reload() {
        lines = logger.read().lines()
    }

    fun clear() {
        logger.clear()
        lines = emptyList()
    }

    fun text(): String = lines.joinToString("\n")
}

private val LINE_STAMP = Regex("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}) \\[(\\w+)\\] (.*)$")

/**
 * 全屏运行日志查看页（等宽、按行着色、自动滚到最新）：
 * 复制 / 分享 / 清除 收在顶栏。用于诊断"端口没更新 / 连不上 / 断流"等问题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewScreen(
    onBack: () -> Unit = {},
    viewModel: LogViewViewModel = hiltViewModel()
) {
    val lines = viewModel.lines
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.reload() }
    // 新日志到达时贴底跟随
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("运行日志") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        val text = viewModel.text()
                        if (text.isNotBlank()) {
                            clipboard.setText(AnnotatedString(text))
                            Toast.makeText(context, "已复制运行日志", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = lines.isNotEmpty()
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "复制")
                }
                IconButton(
                    onClick = {
                        val text = viewModel.text()
                        if (text.isBlank()) return@IconButton
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "LogLab 运行日志")
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        // 兼容非 Activity context 启动，chooser 与 target 均需 NEW_TASK
                        val chooser = Intent.createChooser(send, "分享运行日志")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(chooser) }
                    },
                    enabled = lines.isNotEmpty()
                ) {
                    Icon(Icons.Default.Share, contentDescription = "分享")
                }
                IconButton(
                    onClick = {
                        viewModel.clear()
                        Toast.makeText(context, "已清除", Toast.LENGTH_SHORT).show()
                    },
                    enabled = lines.isNotEmpty()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "清除")
                }
            }
        )

        if (lines.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp)
            ) {
                Text(
                    "（暂无日志）",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                items(lines) { line ->
                    LogViewLine(line)
                }
            }
        }
    }
}

/** 单行着色：时间戳淡色、[TAG] 主色、失败/错误相关行标红 */
@Composable
private fun LogViewLine(line: String) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val errorColor = Color(0xFFFF6B6B)
    val okColor = Color(0xFF3DDC84)
    val default = MaterialTheme.colorScheme.onSurface

    // 富文本构建成本高，必须缓存；key 用行内容（行不可变）
    val annotated = remember(line, muted, primary, default) {
        buildAnnotatedString {
            val match = LINE_STAMP.find(line)
            if (match != null) {
                val (stamp, tag, msg) = match.destructured
                append(stamp)
                addStyle(SpanStyle(color = muted, fontSize = 9.sp), 0, stamp.length)
                append(" [")
                val tagStart = length
                append(tag)
                addStyle(SpanStyle(color = primary, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), tagStart, length)
                append("] ")
                val msgStart = length
                append(msg)
                val color = when {
                    msg.contains("失败") || msg.contains("错误") || msg.contains("异常") || msg.contains("不可用") -> errorColor
                    msg.contains("成功") -> okColor
                    else -> default
                }
                addStyle(SpanStyle(color = color), msgStart, length)
            } else {
                append(line)
                addStyle(SpanStyle(color = muted, fontSize = 9.sp), 0, length)
            }
        }
    }

    Text(
        text = annotated,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 1.dp)
    )
}
