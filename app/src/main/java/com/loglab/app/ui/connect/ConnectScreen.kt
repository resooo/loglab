package com.loglab.app.ui.connect

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.loglab.app.core.adb.NsdDiscovery
import com.loglab.app.core.channel.ChannelType
import com.loglab.app.ui.components.CopyableText
import com.loglab.app.ui.components.EllipseTextField
/**
 * 连接设备页：所有「连接」相关操作收敛于此。
 *  - 状态卡：已连接（绿）/ 未连接（含配对三步引导，配对端口自动识别）；
 *  - 扫描设备列表：点击即连，明确标注端口类型；
 *  - 折叠区：手动填地址 / 连接工具 / ADB 公钥。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    onBack: () -> Unit = {},
    viewModel: ConnectViewModel = hiltViewModel()
) {
    val settings by viewModel.appSettings.collectAsState()
    val channelState by viewModel.channelState.collectAsState()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) { viewModel.loadPublicKey() }
    LaunchedEffect(Unit) { viewModel.scan() }

    // 已连接时默认收起「重新配对」输入区
    var pairOpen by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("连接设备") },
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ==================== 状态卡 ====================
            if (channelState.connected && channelState.type == ChannelType.ADB) {
                StatusCard(
                    dotColor = Color(0xFF3DDC84),
                    title = "已连接",
                    detail = channelState.deviceLabel.ifBlank { "${settings.adbHost}:${settings.adbPort}" },
                    container = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.scan() },
                            enabled = !viewModel.scanning,
                            modifier = Modifier.weight(1f)
                        ) { Text(if (viewModel.scanning) "扫描中…" else "重新扫描") }
                        OutlinedButton(
                            onClick = { pairOpen = !pairOpen },
                            modifier = Modifier.weight(1f)
                        ) { Text(if (pairOpen) "收起配对" else "重新配对") }
                    }
                }
            } else {
                StatusCard(
                    dotColor = Color(0xFFFFB74D),
                    title = if (settings.adbPaired) "已配对，但当前未连接" else "尚未配对",
                    detail = "配对建议用「分屏/小窗」：全程不切换应用，配对码不会被系统刷新",
                    container = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ) {
                    Step("1", "先把本 App 挂成小窗：多任务界面长按本 App →「自由窗口」（或从侧边栏拖出）")
                    Step("2", "在主屏幕进「开发者选项 → 无线调试 → 使用配对码配对设备」")
                    Step("3", "照着主屏幕的码在下面输入 → 开始配对（配对弹窗保持打开）")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(modifier = Modifier.weight(1.6f)) {
                            EllipseTextField(
                                value = viewModel.pairCode,
                                onValueChange = viewModel::onPairCodeChange,
                                placeholder = "配对码，例如 739 512",
                                leadingLabel = "配对码"
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            EllipseTextField(
                                value = viewModel.pairPort,
                                onValueChange = viewModel::onPairPortChange,
                                placeholder = "端口(可选)",
                                leadingLabel = null
                            )
                        }
                    }
                    Text(
                        "端口通常自动识别；扫不到时直接填配对弹窗上显示的端口（例如 43185）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = viewModel::pair,
                        enabled = !viewModel.pairingInProgress,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (viewModel.pairingInProgress) "配对中…" else "开始配对") }
                }
            }
            viewModel.pairingMessage?.let {
                CopyableText(text = it, color = MaterialTheme.colorScheme.primary)
            }

            // ==================== 扫描 + 设备列表 ====================
            OutlinedButton(
                onClick = { viewModel.scan() },
                enabled = !viewModel.scanning,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (viewModel.scanning) "扫描中…" else "扫描附近的设备") }
            viewModel.scanMessage?.let { CopyableText(text = it) }
            viewModel.discoveredDevices.forEach { device ->
                DeviceLine(
                    text = "${device.host}:${device.port}",
                    kind = device.label + if (device.serviceName.isNotBlank()) " · ${device.serviceName}" else "",
                    clickable = device.kind != NsdDiscovery.Kind.PAIRING
                ) {
                    viewModel.chooseDevice(device)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

            // ==================== 折叠：手动填写地址 ====================
            FoldSection("手动填写地址（扫描不到时用）") {
                Text(
                    "填「无线调试」主界面显示的 IP:端口（与配对端口不同，端口每次开关会变化）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.weight(2f)) {
                        EllipseTextField(
                            value = settings.adbHost,
                            onValueChange = { host -> viewModel.update { it.copy(adbHost = host) } },
                            placeholder = "192.168.1.5",
                            leadingLabel = "主机"
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        EllipseTextField(
                            value = settings.adbPort.toString(),
                            onValueChange = { port ->
                                port.toIntOrNull()?.let { v -> viewModel.update { it.copy(adbPort = v) } }
                            },
                            placeholder = "端口",
                            leadingLabel = null
                        )
                    }
                }
            }

            // ==================== 折叠：连接工具 ====================
            FoldSection("连接工具") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::probe, modifier = Modifier.weight(1f)) {
                        Text(if (viewModel.probing) "探测中…" else "探测通道")
                    }
                    OutlinedButton(
                        onClick = { viewModel.reconnect() },
                        modifier = Modifier.weight(1f)
                    ) { Text("重新连接") }
                }
                viewModel.probeMessage?.let { CopyableText(text = it) }
            }

            // ==================== 折叠：ADB 公钥 ====================
            FoldSection("ADB 公钥（高级）") {
                viewModel.publicKey?.let { key ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = key.take(48) + "…",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        IconButton(onClick = { clipboard.setText(AnnotatedString(key)) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "复制公钥")
                        }
                    }
                }
                OutlinedButton(
                    onClick = viewModel::installKeyToDevice,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("写入设备 adb_keys（需 root）") }
            }

            Text(" ", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 状态卡：色点 + 标题 + 说明 + 自定义内容 */
@Composable
private fun StatusCard(
    dotColor: Color,
    title: String,
    detail: String,
    container: Color,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(container, MaterialTheme.shapes.medium)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(dotColor, CircleShape)
            )
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun Step(n: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            n,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp, top = 1.dp)
        )
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/** 设备行：monospace 地址 + 类型标注，点击连接 */
@Composable
private fun DeviceLine(
    text: String,
    kind: String,
    clickable: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (clickable) Modifier.clickable(onClick = onClick) else Modifier
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(
                    if (clickable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    CircleShape
                )
        )
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        )
        Text(
            kind,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** 折叠分组：▸/▾ 标题，点击展开内容 */
@Composable
private fun FoldSection(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (open) "▾" else "▸",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        AnimatedVisibility(visible = open) {
            Column(
                modifier = Modifier.padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                content()
            }
        }
    }
}
