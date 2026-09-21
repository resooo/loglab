package com.loglab.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.loglab.app.ui.components.V4SettingsBadge
import com.loglab.app.ui.components.V4SettingsCard
import com.loglab.app.ui.components.V4SettingsDivider
import com.loglab.app.ui.components.V4SettingsRow
import com.loglab.app.ui.components.V4SettingsSectionTitle
import com.loglab.app.ui.components.V4SettingsValue
import com.loglab.app.ui.components.V4Switch
import com.loglab.app.ui.components.V4TopBar
import com.loglab.app.ui.theme.V4
import kotlinx.coroutines.delay

/**
 * 设置页 —— 分组卡片样式。
 *
 * 结构（每组 = 大标题 + 白底卡片，组内行间 1px 分割线）：
 *  ① 连接：连接状态 / 配对状态 / 连接兜底操作
 *  ② 外观：深色主题 / 动态取色 / 语言
 *  ③ 诊断：上次崩溃报告 / 运行日志
 *  ④ 关于：当前版本 + 检查更新 / 关于 LogLab
 *
 * 与之前版本的差异：
 *  - 顶栏从 Material3 TopAppBar 换成 v4 的 [V4TopBar]，与其余三个 tab 页统一
 *  - 所有开关改成 [V4Switch]（iOS 风格药丸），不再用 Material3 Switch / Checkbox
 *  - 每行统一「图标 + 标题 + 副标题 + 右侧槽位」，副标题说明这一项的用途
 *  - 分隔线由 [V4SettingsDivider] 提供（左右内缩 20dp，不顶到圆角）
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
    val channelState by viewModel.channelState.collectAsState()
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

    // 运行日志预览：3 行 + 每 2 秒刷新
    var logPreview by remember { mutableStateOf(viewModel.readLog()) }
    LaunchedEffect(Unit) {
        while (true) {
            logPreview = viewModel.readLog()
            delay(2_000)
        }
    }

    // 上次崩溃报告：App 闪退时用户拿不到 logcat，这里展示出来便于反馈定位
    val crashReport = remember { CrashReporter.read(context) }
    var crashVisible by remember { mutableStateOf(crashReport != null) }

    // stringResource 不能在 onClick（非 Composable 上下文）里调用，提前取好
    val toastResetPairing = stringResource(R.string.toast_reset_pairing)
    val toastClearAddress = stringResource(R.string.toast_clear_address)
    val toastCopiedReport = stringResource(R.string.toast_copied_crash_report)

    // 「自动开启无线调试」的结果提示：ViewModel 产出一次性的 message，这里消费并弹 Toast。
    // 用 LaunchedEffect 而不是在按钮回调里直接弹 —— 因为结果是异步产生的
    // （要等 pm grant 与写设置完成），放在回调里拿不到。
    val selfGrantMessage = viewModel.message
    LaunchedEffect(selfGrantMessage) {
        if (selfGrantMessage != null) {
            Toast.makeText(context, selfGrantMessage, Toast.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }
    }

    val langLabel = when {
        AppCompatDelegate.getApplicationLocales().isEmpty -> stringResource(R.string.lang_follow_system)
        AppCompatDelegate.getApplicationLocales()[0]?.language == "en" -> stringResource(R.string.lang_en)
        else -> stringResource(R.string.lang_zh)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 顶栏：与其余三个 tab 页统一（标题 + 状态点，点状态点进连接页） ──
        V4TopBar(
            title = stringResource(R.string.tab_settings),
            statusColor = if (channelState.connected) V4.Green else V4.Muted,
            onStatusClick = onGoConnect
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            // ==================== ① 连接 ====================
            V4SettingsSectionTitle(stringResource(R.string.section_status))
            V4SettingsCard {
                // 连接状态：整行可点 → 连接页；右侧绿色「已连接」胶囊
                V4SettingsRow(
                    title = if (channelState.connected) {
                        stringResource(R.string.connected_fmt, channelState.deviceLabel.ifBlank { "ADB" })
                    } else {
                        stringResource(R.string.disconnected)
                    },
                    subtitle = if (channelState.connected) {
                        stringResource(R.string.settings_status_sub_connected)
                    } else {
                        stringResource(R.string.settings_status_sub_disconnected)
                    },
                    glyph = "⇄",
                    onClick = onGoConnect,
                    trailing = {
                        V4SettingsBadge(
                            text = if (channelState.connected) {
                                stringResource(R.string.v4_status_connected_short)
                            } else {
                                stringResource(R.string.v4_status_off_short)
                            },
                            color = if (channelState.connected) V4.Green else V4.Muted
                        )
                    }
                )
                V4SettingsDivider()
                // 配对状态：只读展示
                V4SettingsRow(
                    title = stringResource(R.string.settings_pairing),
                    subtitle = if (settings.adbPaired) {
                        stringResource(R.string.settings_pairing_sub_paired)
                    } else {
                        stringResource(R.string.settings_pairing_sub_unpaired)
                    },
                    glyph = "🔑",
                    trailing = {
                        V4SettingsBadge(
                            text = if (settings.adbPaired) {
                                stringResource(R.string.paired_short)
                            } else {
                                stringResource(R.string.not_paired_short)
                            },
                            color = if (settings.adbPaired) V4.Primary else V4.Muted
                        )
                    }
                )
                V4SettingsDivider()
                // 兜底操作：两个动作按钮
                V4SettingsRow(
                    title = stringResource(R.string.settings_fallback),
                    subtitle = stringResource(R.string.settings_fallback_sub),
                    glyph = "⟳",
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // 重新配对：断开通道 + 清配对标记后直接跳连接页走配对流程
                            V4SettingsActionButton(
                                text = stringResource(R.string.reset_pairing),
                                onClick = {
                                    viewModel.resetPairing()
                                    Toast.makeText(context, toastResetPairing, Toast.LENGTH_LONG).show()
                                    onGoConnect()
                                }
                            )
                            V4SettingsActionButton(
                                text = stringResource(R.string.clear_address_short),
                                onClick = {
                                    viewModel.clearConnectionInfo()
                                    Toast.makeText(context, toastClearAddress, Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                )
                V4SettingsDivider()
                // 自动开启无线调试：授权 + 开关一体化操作
                //
                // 为什么放在设置页：连接页的同名按钮是"诊断/修复"性质的入口，
                // 而设置页才是用户主动管理功能开关的地方。两处都提供，
                // 但这里偏"功能介绍 + 一次性授权"，连接页偏"当前连接出问题的修复手段"。
                V4SettingsRow(
                    title = stringResource(R.string.selfgrant_title),
                    subtitle = if (viewModel.selfGrantGranted) {
                        stringResource(R.string.settings_selfgrant_sub_granted)
                    } else {
                        stringResource(R.string.settings_selfgrant_sub_not_granted)
                    },
                    glyph = "📶",
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            V4SettingsBadge(
                                text = if (viewModel.selfGrantGranted) {
                                    stringResource(R.string.settings_selfgrant_badge_ok)
                                } else {
                                    stringResource(R.string.settings_selfgrant_badge_no)
                                },
                                color = if (viewModel.selfGrantGranted) V4.Green else V4.Muted
                            )
                            V4SettingsActionButton(
                                text = stringResource(R.string.selfgrant_button),
                                onClick = { viewModel.enableWirelessDebug() }
                            )
                        }
                    }
                )
            }

            // ==================== ② 外观 ====================
            V4SettingsSectionTitle(stringResource(R.string.section_appearance))
            V4SettingsCard {
                V4SettingsRow(
                    title = stringResource(R.string.dark_theme),
                    subtitle = stringResource(R.string.dark_theme_sub),
                    glyph = "☾",
                    trailing = {
                        V4Switch(
                            checked = settings.darkTheme,
                            onCheckedChange = { dark -> viewModel.update { it.copy(darkTheme = dark) } }
                        )
                    }
                )
                V4SettingsDivider()
                // 动态取色：Android 12+ 才生效，低版本置灰说明
                V4SettingsRow(
                    title = stringResource(R.string.dynamic_color),
                    subtitle = stringResource(R.string.dynamic_color_sub),
                    glyph = "◈",
                    trailing = {
                        V4Switch(
                            checked = settings.dynamicColor,
                            onCheckedChange = { on -> viewModel.update { it.copy(dynamicColor = on) } }
                        )
                    }
                )
                V4SettingsDivider()
                // 语言：点行弹出下拉菜单
                var langMenuOpen by remember { mutableStateOf(false) }
                V4SettingsRow(
                    title = stringResource(R.string.language),
                    subtitle = stringResource(R.string.language_sub),
                    glyph = "🌐",
                    onClick = { langMenuOpen = true },
                    trailing = {
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                V4SettingsValue(langLabel)
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = V4.Muted,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = langMenuOpen,
                                onDismissRequest = { langMenuOpen = false }
                            ) {
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
                                        AppCompatDelegate.setApplicationLocales(
                                            LocaleListCompat.forLanguageTags("zh")
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.lang_en)) },
                                    onClick = {
                                        langMenuOpen = false
                                        AppCompatDelegate.setApplicationLocales(
                                            LocaleListCompat.forLanguageTags("en")
                                        )
                                    }
                                )
                            }
                        }
                    }
                )
            }

            // ==================== ③ 诊断 ====================
            V4SettingsSectionTitle(stringResource(R.string.section_diagnostics))
            V4SettingsCard {
                // 上次崩溃报告：有报告才展示行，右侧「复制 / 清除」
                if (crashVisible && crashReport != null) {
                    V4SettingsRow(
                        title = stringResource(R.string.last_crash_report),
                        subtitle = stringResource(R.string.last_crash_report_sub),
                        glyph = "⚠",
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                V4SettingsActionButton(
                                    text = stringResource(R.string.copy),
                                    onClick = {
                                        clipboard.setText(AnnotatedString(crashReport))
                                        Toast.makeText(context, toastCopiedReport, Toast.LENGTH_SHORT).show()
                                    }
                                )
                                V4SettingsActionButton(
                                    text = stringResource(R.string.clear),
                                    onClick = {
                                        CrashReporter.clear(context)
                                        crashVisible = false
                                    }
                                )
                            }
                        }
                    )
                    // 崩溃堆栈：卡片内嵌的等宽正文
                    Text(
                        crashReport,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        maxLines = 8,
                        color = V4.Error,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp)
                    )
                } else {
                    V4SettingsRow(
                        title = stringResource(R.string.last_crash_report),
                        subtitle = stringResource(R.string.no_crash_report),
                        glyph = "✓",
                        trailing = {
                            V4SettingsBadge(
                                text = stringResource(R.string.v4_status_none_short),
                                color = V4.Muted
                            )
                        }
                    )
                }
                V4SettingsDivider()
                // 运行日志：3 行预览，点击进全屏查看页
                V4SettingsRow(
                    title = stringResource(R.string.run_log),
                    subtitle = logPreview.lineSequence().take(3).joinToString("\n")
                        .ifBlank { stringResource(R.string.no_log_yet) },
                    glyph = "≡",
                    onClick = onGoLogView,
                    trailing = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.cd_view_log),
                            tint = V4.Muted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
            }

            // ==================== ④ 关于 ====================
            V4SettingsSectionTitle(stringResource(R.string.section_about))
            V4SettingsCard {
                V4SettingsRow(
                    title = stringResource(R.string.current_version_fmt, viewModel.localVersion),
                    subtitle = when (val s = updateState) {
                        is UpdateUiState.UpToDate -> stringResource(R.string.up_to_date)
                        is UpdateUiState.Available -> stringResource(R.string.update_available_fmt, s.info.version)
                        is UpdateUiState.Downloading -> stringResource(R.string.downloading_fmt, s.progress)
                        is UpdateUiState.Downloaded -> stringResource(R.string.downloaded_ready)
                        is UpdateUiState.Failed -> s.message
                        else -> stringResource(R.string.check_update_hint)
                    },
                    glyph = "ⓘ",
                    trailing = {
                        V4SettingsActionButton(
                            text = if (updateState is UpdateUiState.Checking) {
                                stringResource(R.string.checking)
                            } else {
                                stringResource(R.string.check_update)
                            },
                            enabled = updateState !is UpdateUiState.Checking &&
                                updateState !is UpdateUiState.Downloading,
                            onClick = { viewModel.checkUpdate() }
                        )
                    }
                )
                V4SettingsDivider()
                V4SettingsRow(
                    title = stringResource(R.string.about_loglab),
                    subtitle = stringResource(R.string.about_desc_short),
                    glyph = "ⓛ",
                    onClick = { aboutOpen = true },
                    trailing = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = V4.Muted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
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
                Surface(shape = CircleShape, color = V4.PrimarySoft) {
                    Text(
                        "v$version",
                        style = MaterialTheme.typography.labelMedium,
                        color = V4.Primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                Text(
                    stringResource(R.string.about_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = V4.Muted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = V4.Line)

                // 功能速览
                Text(stringResource(R.string.about_features), style = MaterialTheme.typography.bodySmall, color = V4.Muted)
                Text(stringResource(R.string.about_tech), style = MaterialTheme.typography.bodySmall, color = V4.Muted)

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

                Text(stringResource(R.string.about_privacy_hint), style = MaterialTheme.typography.bodySmall, color = V4.Error)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = V4.Line)

                // 署名：本 APP 由太墟构建
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.built_by_prefix), style = MaterialTheme.typography.bodySmall, color = V4.Muted)
                    Text(
                        stringResource(R.string.taixu),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = V4.Primary,
                        modifier = Modifier.clickable { openUrl(context, "https://github.com/wkbin/taixu") }
                    )
                    Text(stringResource(R.string.built_by_suffix), style = MaterialTheme.typography.bodySmall, color = V4.Muted)
                }
            }
        }
    }

    // ---- 应用内更新对话框（GitHub Releases） ----
    when (val s = updateState) {
        is UpdateUiState.Available -> if (!updateDismissed) {
            AlertDialog(
                onDismissRequest = { updateDismissed = true },
                title = { Text(stringResource(R.string.update_found_fmt, s.info.version)) },
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
                                color = V4.Muted
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
                            color = V4.Muted
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
                text = { Text(stringResource(R.string.update_downloaded_body)) },
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

/** 设置行右侧的小动作按钮：主色淡底胶囊，比 Material3 TextButton 更贴合卡片样式 */
@Composable
private fun V4SettingsActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) V4.PrimarySoft else V4.Surface2,
        modifier = Modifier
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) V4.Primary else V4.DisabledTextResolved,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
        )
    }
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
            Text(title, style = MaterialTheme.typography.bodyMedium, color = V4.Text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = V4.Primary)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = V4.Muted
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
