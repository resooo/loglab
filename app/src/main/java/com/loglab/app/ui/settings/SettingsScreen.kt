package com.loglab.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.loglab.app.core.report.CrashReporter
import com.loglab.app.ui.components.CopyableText
import kotlinx.coroutines.delay

/**
 * 设置页：状态（连接/配对 + 兜底操作） / 外观 / 诊断 / 关于。
 * 连接设备的日常操作在「连接设备」页（首页点状态行进入），这里只做状态展示与兜底重置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onGoLogView: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.appSettings.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // 「关于」弹窗
    var aboutOpen by remember { mutableStateOf(false) }
    // 更新对话框「稍后/后台继续」：仅关闭当前弹窗；状态一变（如下载完成）重新允许弹出
    var updateDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(updateState) { updateDismissed = false }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("设置") })

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ==================== 状态（连接/配对 + 兜底操作） ====================
            SectionTitle("状态")
            val channelState by viewModel.channelState.collectAsState()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (channelState.connected) Color(0xFF3DDC84) else Color(0xFF9AA0A6),
                            CircleShape
                        )
                )
                Text(
                    if (channelState.connected) {
                        "已连接 · ${channelState.deviceLabel.ifBlank { "ADB" }}"
                    } else {
                        "未连接"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                if (settings.adbPaired) "已配对（配对密钥保存在本机）" else "未配对",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    viewModel.resetPairing()
                    Toast.makeText(context, "已重置配对状态：回首页点状态行 → 连接页重新配对", Toast.LENGTH_LONG).show()
                }) { Text("重新配对") }
                OutlinedButton(onClick = {
                    viewModel.clearConnectionInfo()
                    Toast.makeText(context, "已清除连接地址，下次连接将重新扫描", Toast.LENGTH_SHORT).show()
                }) { Text("清除连接地址") }
            }

            SectionTitle("外观")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.darkTheme,
                    onCheckedChange = { dark -> viewModel.update { it.copy(darkTheme = dark) } }
                )
                Text("深色主题", style = MaterialTheme.typography.bodyMedium)
            }

            // ==================== 诊断 ====================
            SectionTitle("诊断")

            // 上次崩溃报告：App 闪退时用户拿不到 logcat，这里把堆栈展示出来便于反馈定位
            val crashReport = remember { CrashReporter.read(context) }
            var crashVisible by remember { mutableStateOf(crashReport != null) }
            if (crashVisible && crashReport != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "上次崩溃报告",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(crashReport))
                        Toast.makeText(context, "已复制崩溃报告", Toast.LENGTH_SHORT).show()
                    }) { Text("复制") }
                    TextButton(onClick = {
                        CrashReporter.clear(context)
                        crashVisible = false
                    }) { Text("清除") }
                }
                Text(
                    crashReport,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    maxLines = 8,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // 运行日志：默认 3 行预览，点击进入全屏查看页
            var logPreview by remember { mutableStateOf(viewModel.readLog()) }
            LaunchedEffect(Unit) {
                while (true) {
                    logPreview = viewModel.readLog()
                    delay(2_000)
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onGoLogView)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("运行日志", style = MaterialTheme.typography.titleSmall)
                    Text(
                        logPreview.lineSequence().take(3).joinToString("\n").ifBlank { "（暂无日志）" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        maxLines = 3,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "查看运行日志",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ==================== 关于 ====================
            SectionTitle("关于")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "当前版本 ${viewModel.localVersion}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    when (val s = updateState) {
                        is UpdateUiState.UpToDate -> Text(
                            "已是最新版本",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        is UpdateUiState.Available -> Text(
                            "发现新版本 ${s.info.version}，点「检查更新」查看详情",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        is UpdateUiState.Downloading -> Text(
                            "下载中 ${s.progress}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        is UpdateUiState.Downloaded -> Text(
                            "更新包已就绪，点「检查更新」可安装",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        is UpdateUiState.Failed -> Text(
                            s.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        else -> {}
                    }
                }
                OutlinedButton(
                    onClick = { viewModel.checkUpdate() },
                    enabled = updateState !is UpdateUiState.Checking &&
                        updateState !is UpdateUiState.Downloading
                ) {
                    Text(
                        if (updateState is UpdateUiState.Checking) "检查中…" else "检查更新"
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { aboutOpen = true }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "关于 LogLab",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(" ", style = MaterialTheme.typography.bodySmall)
        }
    }

    // ---- 关于弹窗 ----
    if (aboutOpen) {
        val version = remember {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "1.0.0"
        }
        AlertDialog(
            onDismissRequest = { aboutOpen = false },
            title = { Text("LogLab") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("版本 $version", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "内嵌 ADB 无线调试的日志抓取工具：无需 root、无需电脑、无需数据线，" +
                            "手机直接抓取手机日志。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "主要功能：日志抓取、实时跟踪、一键导出、应用崩溃监控。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "技术说明：内嵌 ADB 协议实现（Kadb TLS 无线配对），支持 ADB 直连与 HostBridge 双通道。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "提示：抓取的日志可能包含敏感信息，分享前请自行确认。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { aboutOpen = false }) { Text("确定") }
            }
        )
    }

    // ---- 应用内更新对话框（GitHub Releases） ----
    when (val s = updateState) {
        is UpdateUiState.Available -> if (!updateDismissed) {
            AlertDialog(
                onDismissRequest = { updateDismissed = true },
                title = { Text("发现新版本 ${s.info.version}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            s.info.notes.ifBlank { "（无更新说明）" },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 12
                        )
                        if (s.info.apkSize > 0) {
                            Text(
                                "安装包 ${s.info.apkName}（%.1f MB）".format(s.info.apkSize / 1024f / 1024f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.downloadUpdate(s.info) }) {
                        Text("下载更新")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { updateDismissed = true }) { Text("稍后") }
                }
            )
        }
        is UpdateUiState.Downloading -> {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("正在下载更新") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LinearProgressIndicator(
                            progress = { s.progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "${s.progress}%  ·  下载完成后可返回此处安装",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { updateDismissed = true }) { Text("后台继续") }
                }
            )
        }
        is UpdateUiState.Downloaded -> if (!updateDismissed) {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("更新包已下载") },
                text = {
                    Text(
                        "点击「安装」拉起系统安装器。若提示无法安装，请先在系统设置中允许 LogLab「安装未知应用」。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val tip = viewModel.installUpdate(s.file)
                        if (tip != null) Toast.makeText(context, tip, Toast.LENGTH_LONG).show()
                    }) { Text("安装") }
                },
                dismissButton = {
                    TextButton(onClick = { updateDismissed = true }) { Text("稍后再装") }
                }
            )
        }
        else -> {}
    }
}

/** LogFox 式分组标题：粗体 + 上方留白 + 分割线 */
@Composable
private fun SectionTitle(text: String) {
    HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp)
    )
}
