package com.loglab.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.loglab.app.R
import com.loglab.app.core.report.CrashReporter
import com.loglab.app.core.update.UpdateManager
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
    onGoConnect: () -> Unit = {},
    autoCheckUpdate: Boolean = false,
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

    // 从首页更新横幅跳转过来（settings?auto=1）：进入即自动检查并弹更新框
    LaunchedEffect(autoCheckUpdate) {
        if (autoCheckUpdate) viewModel.checkUpdate()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.tab_settings)) })

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ==================== 状态（连接/配对 + 兜底操作） ====================
            SectionTitle(stringResource(R.string.section_status))
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
                        stringResource(
                            R.string.connected_fmt,
                            channelState.deviceLabel.ifBlank { "ADB" }
                        )
                    } else {
                        stringResource(R.string.disconnected)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                if (settings.adbPaired) stringResource(R.string.paired) else stringResource(R.string.unpaired),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // stringResource 不能在 onClick（非 Composable 上下文）里调用，提前取好
            val toastResetPairing = stringResource(R.string.toast_reset_pairing)
            val toastClearAddress = stringResource(R.string.toast_clear_address)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 重新配对：断开通道 + 清配对标记后直接跳连接页走配对流程
                OutlinedButton(onClick = {
                    viewModel.resetPairing()
                    Toast.makeText(context, toastResetPairing, Toast.LENGTH_LONG).show()
                    onGoConnect()
                }) { Text(stringResource(R.string.reset_pairing)) }
                OutlinedButton(onClick = {
                    viewModel.clearConnectionInfo()
                    Toast.makeText(context, toastClearAddress, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.clear_address)) }
            }

            SectionTitle(stringResource(R.string.section_appearance))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.darkTheme,
                    onCheckedChange = { dark -> viewModel.update { it.copy(darkTheme = dark) } }
                )
                Text(stringResource(R.string.dark_theme), style = MaterialTheme.typography.bodyMedium)
            }

            // 语言切换：跟随系统 / 中文 / English。
            // AppCompatDelegate.setApplicationLocales 由 AppCompat 自动持久化
            // （Manifest 已启用 autoStoreLocales），设置后 Activity 自动重建生效。
            var langMenuOpen by remember { mutableStateOf(false) }
            val currentLocales = AppCompatDelegate.getApplicationLocales()
            val langLabel = when {
                currentLocales.isEmpty -> stringResource(R.string.lang_follow_system)
                currentLocales[0]?.language == "en" -> stringResource(R.string.lang_en)
                else -> stringResource(R.string.lang_zh)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { langMenuOpen = true }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.language),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    langLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DropdownMenu(expanded = langMenuOpen, onDismissRequest = { langMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lang_follow_system)) },
                        onClick = {
                            langMenuOpen = false
                            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lang_zh)) },
                        onClick = {
                            langMenuOpen = false
                            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh"))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lang_en)) },
                        onClick = {
                            langMenuOpen = false
                            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
                        }
                    )
                }
            }

            // ==================== 诊断 ====================
            SectionTitle(stringResource(R.string.section_diagnostics))

            // 上次崩溃报告：App 闪退时用户拿不到 logcat，这里把堆栈展示出来便于反馈定位
            val crashReport = remember { CrashReporter.read(context) }
            var crashVisible by remember { mutableStateOf(crashReport != null) }
            if (crashVisible && crashReport != null) {
                val toastCopiedReport = stringResource(R.string.toast_copied_crash_report)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.last_crash_report),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(crashReport))
                        Toast.makeText(context, toastCopiedReport, Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.copy)) }
                    TextButton(onClick = {
                        CrashReporter.clear(context)
                        crashVisible = false
                    }) { Text(stringResource(R.string.clear)) }
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
                    Text(stringResource(R.string.run_log), style = MaterialTheme.typography.titleSmall)
                    Text(
                        logPreview.lineSequence().take(3).joinToString("\n").ifBlank { stringResource(R.string.no_log_yet) },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        maxLines = 3,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.cd_view_log),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ==================== 关于 ====================
            SectionTitle(stringResource(R.string.section_about))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.current_version_fmt, viewModel.localVersion),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    when (val s = updateState) {
                        is UpdateUiState.UpToDate -> Text(
                            stringResource(R.string.up_to_date),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        is UpdateUiState.Available -> Text(
                            stringResource(R.string.update_available_fmt, s.info.version),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        is UpdateUiState.Downloading -> Text(
                            stringResource(R.string.downloading_fmt, s.progress),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        is UpdateUiState.Downloaded -> Text(
                            stringResource(R.string.downloaded_ready),
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
                        if (updateState is UpdateUiState.Checking) stringResource(R.string.checking) else stringResource(R.string.check_update)
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
                    stringResource(R.string.about_loglab),
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

    // ---- 关于面板（底部弹出） ----
    if (aboutOpen) {
        val version = remember {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "1.0.0"
        }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { aboutOpen = false }, sheetState = sheetState) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 头部：图标 + 名称 + 版本徽章
                val appIcon = remember {
                    runCatching { context.packageManager.getApplicationIcon(context.packageName) }
                        .getOrNull()
                }
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon.toBitmap().asImageBitmap(),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.size(72.dp)
                    )
                }
                Text("LogLab", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        "v$version",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                Text(
                    stringResource(R.string.about_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // 功能速览
                Text(
                    stringResource(R.string.about_features),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.about_tech),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 入口行：开源仓库 / Bug 反馈
                AboutLinkRow(
                    title = stringResource(R.string.about_open_source),
                    subtitle = UpdateManager.REPO_URL.removePrefix("https://"),
                    onClick = { openUrl(context, UpdateManager.REPO_URL) }
                )
                AboutLinkRow(
                    title = stringResource(R.string.about_bug_feedback),
                    subtitle = "ibr@foxmail.com",
                    onClick = {
                        openUrl(
                            context,
                            "mailto:ibr@foxmail.com?subject=" +
                                java.net.URLEncoder.encode("LogLab 反馈（v$version）", "UTF-8")
                        )
                    }
                )

                Text(
                    stringResource(R.string.about_privacy_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // 署名：本 APP 由太墟构建
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.built_by_prefix),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.taixu),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { openUrl(context, "https://github.com/wkbin/taixu") }
                    )
                    Text(
                        stringResource(R.string.built_by_suffix),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
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
                            s.info.notes.ifBlank { stringResource(R.string.no_release_notes) },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 12
                        )
                        if (s.info.apkSize > 0) {
                            Text(
                                stringResource(R.string.apk_size_fmt, s.info.apkName, s.info.apkSize / 1024f / 1024f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.downloadUpdate(s.info) }) {
                        Text(stringResource(R.string.download_update))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { updateDismissed = true }) { Text(stringResource(R.string.later)) }
                }
            )
        }
        is UpdateUiState.Downloading -> {
            AlertDialog(
                onDismissRequest = { },
                title = { Text(stringResource(R.string.downloading_update)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LinearProgressIndicator(
                            progress = { s.progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            stringResource(R.string.download_progress_fmt, s.progress),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { updateDismissed = true }) { Text(stringResource(R.string.background_continue)) }
                }
            )
        }
        is UpdateUiState.Downloaded -> if (!updateDismissed) {
            AlertDialog(
                onDismissRequest = { },
                title = { Text(stringResource(R.string.update_downloaded_title)) },
                text = {
                    Text(
                        stringResource(R.string.update_downloaded_body)
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val tip = viewModel.installUpdate(s.file)
                        if (tip != null) Toast.makeText(context, tip, Toast.LENGTH_LONG).show()
                    }) { Text(stringResource(R.string.install)) }
                },
                dismissButton = {
                    TextButton(onClick = { updateDismissed = true }) { Text(stringResource(R.string.install_later)) }
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

/** 关于面板里的可点击入口行：标题 + 副标题，整行点击 */
@Composable
private fun AboutLinkRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 用浏览器 / 邮件 App 打开链接；目标 App 不存在时静默失败 */
private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
