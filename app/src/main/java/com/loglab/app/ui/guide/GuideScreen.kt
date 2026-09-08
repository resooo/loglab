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
import androidx.compose.ui.res.stringResource
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
            title = { Text(stringResource(R.string.guide_title)) },
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
            // ==================== 开始抓日志 ====================
            GuideTitle(stringResource(R.string.guide_sec1))
            Step(1, stringResource(R.string.guide_s1_1))
            Step(2, stringResource(R.string.guide_s1_2))
            Step(3, stringResource(R.string.guide_s1_3))
            Tip(stringResource(R.string.guide_s1_tip))

            // ==================== 分屏配对图示 ====================
            GuideTitle(stringResource(R.string.guide_sec2))
            GuideImage(R.drawable.guide_pairing_split, stringResource(R.string.guide_img1))
            GuideImage(R.drawable.guide_paired_connected, stringResource(R.string.guide_img2))

            // ==================== 日常使用 ====================
            GuideTitle(stringResource(R.string.guide_sec3))
            Step(1, stringResource(R.string.guide_s3_1))
            Step(2, stringResource(R.string.guide_s3_2))
            Step(3, stringResource(R.string.guide_s3_3))
            Tip(stringResource(R.string.guide_s3_tip))

            // ==================== 抓崩溃日志 ====================
            GuideTitle(stringResource(R.string.guide_sec4))
            Step(1, stringResource(R.string.guide_s4_1))
            Step(2, stringResource(R.string.guide_s4_2))
            Step(3, stringResource(R.string.guide_s4_3))

            // ==================== 连不上？ ====================
            GuideTitle(stringResource(R.string.guide_sec5))
            Step(1, stringResource(R.string.guide_s5_1))
            Step(2, stringResource(R.string.guide_s5_2))
            Step(3, stringResource(R.string.guide_s5_3))
            Tip(stringResource(R.string.guide_s5_tip))

            // ==================== 隐私提示 ====================
            HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
            Text(
                stringResource(R.string.guide_privacy),
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
