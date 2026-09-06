package com.loglab.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle

/**
 * 可复制文本：点击整段复制到剪贴板并 Toast 提示（用于错误信息、状态栏等一次性信息）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CopyableText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Text(
        text = text,
        style = style,
        color = color,
        modifier = modifier.combinedClickable(
            onClick = {
                clipboard.setText(AnnotatedString(text))
                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            },
            onLongClick = {
                clipboard.setText(AnnotatedString(text))
                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
        )
    )
}
