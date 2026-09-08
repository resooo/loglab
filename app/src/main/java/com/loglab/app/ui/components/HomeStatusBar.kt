package com.loglab.app.ui.components

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loglab.app.R
import com.loglab.app.core.connect.CheckPhase
import com.loglab.app.core.connect.StartupCheckResult

/**
 * 椭圆（胶囊）输入框：40dp 高、全圆角，替代默认 56dp 的 OutlinedTextField。
 *
 *  - [placeholder]：空内容时的灰色提示（搜索类输入）；
 *  - [leadingLabel]：前置固定标签（表单类输入，如「主机」「端口」）；
 *  - [trailing]：尾部控件（清除图标、选择按钮等）。
 */
@Composable
fun EllipseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingLabel: String? = null,
    trailing: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    val bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val hint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val fg = MaterialTheme.colorScheme.onSurface

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        textStyle = TextStyle(fontSize = 13.sp, color = fg),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp),
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg, CircleShape)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!leadingLabel.isNullOrBlank()) {
                    Text(
                        leadingLabel,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder.isNotBlank()) {
                        Text(placeholder, fontSize = 13.sp, color = hint, maxLines = 1)
                    }
                    inner()
                }
                trailing?.invoke(this)
            }
        }
    )
}

/**
 * 首页/实时页统一状态行：把「启动智能检查」与「通道状态」合并为一行可点击文字。
 *
 * 显示优先级：
 *  1. 检查中 → 橙点 + 正在检查… + 第二行实时阶段；
 *  2. 已连接 → 绿点 + 地址（点击进连接页）；
 *  3. 检查失败 → 红点 + 原因 + [去连接] [重试]；
 *  4. 其余 → 灰点 + 未连接 + [去连接]。
 *
 * 整行可点击进入连接页（按钮自身优先消费点击）。
 */
@Composable
fun HomeStatusBar(
    result: StartupCheckResult?,
    checking: Boolean,
    phase: CheckPhase,
    channelConnected: Boolean,
    channelLabel: String,
    onGoConnect: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        when {
            // 1. 检查中：显示实时阶段，不让用户误以为卡死
            checking && result == null -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Dot(Color(0xFFFFB74D), Modifier.padding(end = 8.dp))
                Column {
                    Text(stringResource(R.string.status_checking), fontSize = 13.sp, color = muted)
                    if (phase.res != null) {
                        Text(
                            stringResource(phase.res, *phase.args.toTypedArray()),
                            fontSize = 12.sp,
                            color = muted
                        )
                    }
                }
            }
            // 2. 已连接
            channelConnected -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Dot(Color(0xFF3DDC84), Modifier.padding(end = 8.dp))
                Text(
                    if (channelLabel.isNotBlank()) stringResource(R.string.connected_fmt, channelLabel)
                    else stringResource(R.string.connected_short),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text("›", fontSize = 16.sp, color = muted)
            }
            // 3. 检查失败：原因 + 动作。
            //    「无线调试未开启」单独提示，并提供「去开启」直达开发者选项
            result != null -> {
                val context = LocalContext.current
                val isDebugOff = result is StartupCheckResult.DebugOff
                val openDevSettings = remember {
                    {
                        // 优先跳开发者选项；个别 ROM 无此页面则退回系统设置
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                        Unit
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Dot(Color(0xFFFF6B6B), Modifier.padding(end = 8.dp))
                    Text(
                        stringResource(result.messageRes, *result.messageArgs.toTypedArray()),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    if (isDebugOff) {
                        TextButton(onClick = openDevSettings) {
                            Text(stringResource(R.string.status_go_enable), fontSize = 13.sp)
                        }
                    } else {
                        TextButton(onClick = onGoConnect) {
                            Text(stringResource(R.string.status_go_connect), fontSize = 13.sp)
                        }
                    }
                    if (result.needsAction) {
                        TextButton(onClick = onRetry) {
                            Text(stringResource(R.string.status_retry), fontSize = 13.sp)
                        }
                    }
                }
            }
            // 4. 未连接
            else -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Dot(Color(0xFF9AA0A6), Modifier.padding(end = 8.dp))
                Text(
                    stringResource(R.string.disconnected),
                    fontSize = 13.sp,
                    color = muted,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onGoConnect) { Text(stringResource(R.string.status_go_connect), fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .background(color, CircleShape)
    )
}
