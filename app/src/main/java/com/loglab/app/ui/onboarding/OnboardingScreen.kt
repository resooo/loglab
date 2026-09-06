package com.loglab.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        Text("LogLab", style = MaterialTheme.typography.headlineMedium)
        Text(
            "在手机上直接抓取任意应用的 logcat，无需电脑、无需 READ_LOGS 权限。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text("无线 ADB 抓包（无需 root）", style = MaterialTheme.typography.titleMedium)
        StepText("1. 系统设置 → 开发者选项 → 无线调试 → 开启")
        StepText("2. 首页点顶部状态行进连接页；配对建议分屏：先把本 App 挂成小窗，再开「使用配对码配对设备」")
        StepText("3. 输入 6 位配对码 → 开始配对（端口自动识别，切走 App 会导致码刷新）")
        StepText("4. 配对成功后回到首页即可抓取，端口变化会自动修正")

        Text("隐私与安全", style = MaterialTheme.typography.titleMedium)
        Text(
            "ADB 走本机无线调试通道，日志不上传任何服务器；" +
                "日志内容可能包含敏感信息，导出/分享前请自行确认。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("开始使用") }
        OutlinedButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("跳过，稍后配置") }
    }
}

@Composable
private fun StepText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
    )
}
