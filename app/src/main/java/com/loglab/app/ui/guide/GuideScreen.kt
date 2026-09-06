package com.loglab.app.ui.guide

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loglab.app.R

/**
 * 使用方法介绍页（静态）：突出重点、少即是多。
 * 入口：首页顶栏「?」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(onBack: () -> Unit = {}) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("使用方法") },
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
            // ==================== 开始抓日志 ====================
            GuideTitle("开始抓日志 · 三步")
            Step(1, "打开「设置 → 开发者选项 → 无线调试」，保持开启")
            Step(2, "点顶部状态行进连接页；配对建议分屏：多任务里长按本 App →「自由窗口」挂成小窗，再在主屏幕开「使用配对码配对设备」")
            Step(3, "照着主屏幕的码在小窗里输入 → 开始配对（端口自动识别，配对只需一次）")
            Tip("注意：配对期间切走 App 或关闭弹窗，配对码和端口都会刷新——用分屏小窗可以避免。")

            // ==================== 分屏配对图示 ====================
            GuideTitle("分屏配对 · 图示")
            GuideImage(R.drawable.guide_pairing_split, "① 右侧小窗里输配对码，主屏的配对弹窗保持打开")
            GuideImage(R.drawable.guide_paired_connected, "② 配对成功自动连接；之后日常抓日志不用再配对")

            // ==================== 日常使用 ====================
            GuideTitle("日常使用（配对完成后）")
            Step(1, "打开「无线调试」开关")
            Step(2, "打开本 App —— 自动连接，端口变了也会自动识别，不用手动改")
            Step(3, "抓完日志：⋮ 复制全部 / 💾 保存成文件")
            Tip("「无线调试」是个开关：不用时可以关掉，下次要用再打开即可。")

            // ==================== 抓崩溃日志 ====================
            GuideTitle("抓应用崩溃日志")
            Step(1, "切到底部「崩溃」页，点「开始监控崩溃」")
            Step(2, "去打开那个会闪退的应用，让它崩溃一次")
            Step(3, "回到「崩溃」页，崩溃记录和堆栈自动出现，可复制、分享")

            // ==================== 连不上？ ====================
            GuideTitle("连不上？按顺序试")
            Step(1, "确认「无线调试」开关是打开的（最常见原因）")
            Step(2, "首页点状态行 → 连接设备 → 「重新扫描」")
            Step(3, "还不行：展开「手动填写地址」，照着无线调试主界面填 IP:端口")
            Tip("首次连接必须先配对一次；配对后换端口不用重新配对。")

            // ==================== 隐私提示 ====================
            HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
            Text(
                "提示：抓取的日志可能包含敏感信息（账号、地址等），分享给别人前请自行确认。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}

@Composable
private fun GuideTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 10.dp)
    )
}

/** 配置截图 + 一句话说明 */
@Composable
private fun GuideImage(resId: Int, caption: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Image(
            painter = painterResource(resId),
            contentDescription = caption,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
        )
        Text(
            caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "$n",
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(start = 10.dp)
                .padding(top = 1.dp)
        )
    }
}

@Composable
private fun Tip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                MaterialTheme.shapes.medium
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}
