package com.loglab.app.ui.connect

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.loglab.app.core.adb.NsdDiscovery
import com.loglab.app.R
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
            title = { Text(stringResource(R.string.connect_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
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
                    title = stringResource(R.string.connected_short),
                    detail = channelState.deviceLabel.ifBlank { "${settings.adbHost}:${settings.adbPort}" },
                    container = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.scan() },
                            enabled = !viewModel.scanning,
                            modifier = Modifier.weight(1f)
                        ) { Text(if (viewModel.scanning) stringResource(R.string.scanning_fmt) else stringResource(R.string.rescan)) }
                        OutlinedButton(
                            onClick = { pairOpen = !pairOpen },
                            modifier = Modifier.weight(1f)
                        ) { Text(if (pairOpen) stringResource(R.string.collapse_pairing) else stringResource(R.string.re_pair)) }
                    }
                }
            } else {
                StatusCard(
                    dotColor = Color(0xFFFFB74D),
                    title = if (settings.adbPaired) stringResource(R.string.paired_not_connected) else stringResource(R.string.not_paired_short),
                    detail = stringResource(R.string.pair_suggest_split),
                    container = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ) {
                    Step("1", stringResource(R.string.pair_step1))
                    Step("2", stringResource(R.string.pair_step2))
                    Step("3", stringResource(R.string.pair_step3))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(modifier = Modifier.weight(1.6f)) {
                            EllipseTextField(
                                value = viewModel.pairCode,
                                onValueChange = viewModel::onPairCodeChange,
                                placeholder = stringResource(R.string.pair_code_hint),
                                leadingLabel = stringResource(R.string.pair_code_label)
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            EllipseTextField(
                                value = viewModel.pairPort,
                                onValueChange = viewModel::onPairPortChange,
                                placeholder = stringResource(R.string.pair_port_hint),
                                leadingLabel = null
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.pair_port_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = viewModel::pair,
                        enabled = !viewModel.pairingInProgress,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (viewModel.pairingInProgress) stringResource(R.string.pairing_fmt) else stringResource(R.string.start_pairing)) }
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
            ) { Text(if (viewModel.scanning) stringResource(R.string.scanning_fmt) else stringResource(R.string.scan_nearby)) }
            viewModel.scanMessage?.let { CopyableText(text = it) }
            // 列表已按可用性排序（活端口在前、幽灵端口沉底），这里只负责标记
            // 「推荐」只打在第一个可用项上，避免多处高亮反而让人不知道该点哪个
            val recommendedPort = viewModel.discoveredDevices.firstOrNull { it.recommended }?.port
            viewModel.discoveredDevices.forEach { device ->
                DeviceLine(
                    // loopback 方案：连接地址恒为 127.0.0.1:端口，mDNS 的 host 不再展示为主地址
                    text = if (device.kind == NsdDiscovery.Kind.PAIRING) "${device.host}:${device.port}"
                    else "127.0.0.1:${device.port}",
                    kind = device.label + if (device.serviceName.isNotBlank()) " · ${device.serviceName}" else "",
                    clickable = device.kind != NsdDiscovery.Kind.PAIRING,
                    alive = device.alive,
                    stale = device.stale,
                    recommended = device.port == recommendedPort
                ) {
                    viewModel.chooseDevice(device)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

            // ==================== 折叠：手动填写地址 ====================
            FoldSection(stringResource(R.string.fold_manual)) {
                Text(
                    stringResource(R.string.manual_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.weight(2f)) {
                        EllipseTextField(
                            value = settings.adbHost,
                            onValueChange = { host -> viewModel.update { it.copy(adbHost = host) } },
                            placeholder = "192.168.1.5",
                            leadingLabel = stringResource(R.string.host_label)
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        EllipseTextField(
                            value = settings.adbPort.toString(),
                            onValueChange = { port ->
                                port.toIntOrNull()?.let { v -> viewModel.update { it.copy(adbPort = v) } }
                            },
                            placeholder = stringResource(R.string.port_label),
                            leadingLabel = null
                        )
                    }
                }
            }

            // ==================== 折叠：连接工具 ====================
            FoldSection(stringResource(R.string.fold_tools)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::probe, modifier = Modifier.weight(1f)) {
                        Text(if (viewModel.probing) stringResource(R.string.probing_fmt) else stringResource(R.string.probe_channel))
                    }
                    OutlinedButton(
                        onClick = { viewModel.reconnect() },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.reconnect)) }
                }
                viewModel.probeMessage?.let { CopyableText(text = it) }
            }

            // ==================== 折叠：自动开启无线调试 ====================
            // 打通「自授权限」这一步后，才能写 Settings.Global 开关无线调试，
            // 进而实现「启动 App 自动恢复连接」。机制移植自 Shizuku。
            FoldSection(stringResource(R.string.selfgrant_title)) {
                Text(
                    stringResource(R.string.selfgrant_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.grantSelfPermission() },
                    enabled = !viewModel.granting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (viewModel.granting) stringResource(R.string.selfgrant_running)
                        else stringResource(R.string.selfgrant_button)
                    )
                }
                viewModel.grantMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    CopyableText(text = it)
                }
            }

            // ==================== 折叠：ADB 公钥 ====================
            FoldSection(stringResource(R.string.fold_pubkey)) {
                viewModel.publicKey?.let { key ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = key.take(48) + "…",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        IconButton(onClick = { clipboard.setText(AnnotatedString(key)) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.cd_copy_pubkey))
                        }
                    }
                }
                OutlinedButton(
                    onClick = viewModel::installKeyToDevice,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.install_key)) }
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

/** 设备行：monospace 地址 + 类型标注 + 活性标记，点击连接 */
@Composable
private fun DeviceLine(
    text: String,
    kind: String,
    clickable: Boolean,
    /** 该端口是否探活通过；null 表示未探测（不显示标记） */
    alive: Boolean? = null,
    /** 是否处于证伪观察窗内 */
    stale: Boolean = false,
    /** 是否是推荐项（首个可用端口） */
    recommended: Boolean = false,
    onClick: () -> Unit
) {
    // 状态色：可用=主色，失效/关闭=灰，未探测=主色（保持原样）
    val dead = stale || alive == false
    val dotColor = when {
        dead -> MaterialTheme.colorScheme.outlineVariant
        else -> MaterialTheme.colorScheme.primary
    }
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
                .background(dotColor, CircleShape)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    // 失效条目整行降透明度，一眼区分「能点的」和「过期的」
                    color = if (dead) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface
                )
                // 推荐标记：只给列表里第一个可用端口打，避免多处高亮反而不知道该点哪个
                if (recommended) {
                    Text(
                        stringResource(R.string.scan_recommend_tag),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                kind,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            // 失效原因说明：让用户明白为什么这条不该点
            if (dead) {
                Text(
                    stringResource(if (stale) R.string.scan_dead_tag else R.string.scan_closed_tag),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1
                )
            }
        }
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
