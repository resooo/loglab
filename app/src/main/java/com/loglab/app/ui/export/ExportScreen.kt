package com.loglab.app.ui.export

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.loglab.app.ui.components.ChannelBar
import com.loglab.app.ui.components.EllipseTextField
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    onBack: () -> Unit = {},
    viewModel: ExportViewModel = hiltViewModel()
) {
    val channelState by viewModel.channelState.collectAsState()
    val history by viewModel.history.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var pendingFile by remember { mutableStateOf<File?>(null) }

    // MIME 决定系统保存对话框的默认扩展名：
    // octet-stream 会被文件管理器默认补成 .bin，普通日志用 text/plain（默认 .log/.txt）
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val file = pendingFile ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            scope.launch { viewModel.saveTo(uri, file) }
        }
    }

    // gzip 压缩包用对应 MIME，保证保存对话框默认给出 .gz
    val saveGzipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip")
    ) { uri ->
        val file = pendingFile ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            scope.launch { viewModel.saveTo(uri, file) }
        }
    }

    fun launchSave(file: File) {
        pendingFile = file
        if (file.name.endsWith(".gz")) {
            saveGzipLauncher.launch(file.name)
        } else {
            saveLauncher.launch(file.name)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // 二级页顶栏：带返回（导出入口已并入抓取页顶栏保存按钮）
        TopAppBar(
            title = { Text("导出日志") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
        ChannelBar(state = channelState, onReconnect = {
            scope.launch { viewModel.channelManager.autoConnect(viewModel.appSettings.value.channelPolicy) }
        })

        EllipseTextField(
            value = viewModel.packageName,
            onValueChange = viewModel::onPackageChange,
            placeholder = "留空导出全部进程",
            leadingLabel = "包名"
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                EllipseTextField(
                    value = viewModel.fileName,
                    onValueChange = viewModel::onFileNameChange,
                    placeholder = "log-20260906-143000",
                    leadingLabel = "文件名"
                )
            }
            OutlinedButton(onClick = viewModel::regenerateFileName) { Text("重置") }
        }

        EllipseTextField(
            value = viewModel.maxLines.takeIf { it > 0 }?.toString().orEmpty(),
            onValueChange = viewModel::onMaxLinesChange,
            placeholder = "0 或留空 = 全部",
            leadingLabel = "行数"
        )

        EllipseTextField(
            value = viewModel.keywordInput,
            onValueChange = viewModel::onKeywordChange,
            placeholder = "逗号分隔",
            leadingLabel = "关键词"
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = viewModel.addHeader, onCheckedChange = viewModel::onAddHeaderChange)
            Text("添加元信息头", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = viewModel.gzip, onCheckedChange = viewModel::onGzipChange)
            Text("gzip 压缩", style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = {
                scope.launch {
                    viewModel.export().onSuccess { result ->
                        pendingFile = File(result.absolutePath)
                    }
                }
            },
            enabled = !viewModel.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (viewModel.busy) "导出中…" else "导出日志") }

        viewModel.status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        pendingFile?.let { file ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { launchSave(file) },
                    modifier = Modifier.weight(1f)
                ) { Text("保存到…") }
                OutlinedButton(
                    onClick = {
                        // 兼容非 Activity context 启动，chooser 需 NEW_TASK
                        context.startActivity(
                            Intent.createChooser(viewModel.shareFile(file), "分享日志")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("分享") }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text("历史导出", style = MaterialTheme.typography.titleSmall)
        if (history.isEmpty()) {
            Text(
                "暂无导出记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            history.forEach { file ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            ExportViewModel.formatSize(file.length()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        // 兼容非 Activity context 启动，chooser 需 NEW_TASK
                        context.startActivity(
                            Intent.createChooser(viewModel.shareFile(file), "分享日志")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "分享")
                    }
                    IconButton(onClick = { viewModel.delete(file) }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除")
                    }
                }
            }
        }
        }
    }
}
