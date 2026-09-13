package com.loglab.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 椭圆（胶囊）输入框：40dp 高、全圆角，替代默认 56dp 的 OutlinedTextField。
 *
 *  - [placeholder]：空内容时的灰色提示（搜索类输入）；
 *  - [leadingLabel]：前置固定标签（表单类输入，如「主机」「端口」）；
 *  - [trailing]：尾部控件（清除图标、选择按钮等）。
 *
 * v4 布局下首页/实时页不再使用它（搜索已改为底部弹出层 + V4Field），
 * 但连接页、导出页、实时页关键词层仍在用。
 */
@Composable
fun EllipseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingLabel: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
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
