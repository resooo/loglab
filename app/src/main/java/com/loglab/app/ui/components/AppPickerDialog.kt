package com.loglab.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.loglab.app.R
import com.loglab.app.core.apps.AppInfo
import com.loglab.app.ui.theme.V4
import android.graphics.drawable.Drawable

/**
 * v4「选择应用进程」底部弹出层（原 AppPickerDialog 的底部层版本）。
 *
 * 设计稿变化：
 *  - 从 AlertDialog 改为 ModalBottomSheet（与搜索/筛选层统一）
 *  - 列表行：应用图标 + 应用名 + 包名（选中项主色 + ✓）
 *  - 底部 [清除选择] [完成]
 *
 * 交互调整：点行即选中（默认直接回调 onPick 并关闭），同时保留本地 selected 标记
 * 让「当前正在看谁」一眼可见；「清除选择」把包名清空。
 *
 * ★ 图标来自 [AppInfoProvider.icon]，按需读取（LazyColumn 只对可见项调用，不会
 *   一次拉几百个图标）。取不到图标时用包名首字母的占位色块，不留空白。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerSheet(
    apps: List<AppInfo>,
    loading: Boolean,
    selectedPackage: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    iconFor: (String) -> Drawable? = { null }
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }

    val filtered = remember(apps, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            apps
        } else {
            apps.filter {
                it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        containerColor = V4.Bg,
        dragHandle = null,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = 18.dp)
        ) {
            V4SheetGrip(modifier = Modifier.align(Alignment.CenterHorizontally))
            V4SheetHeader(text = stringResource(R.string.v4_picker_title))

            // 搜索框
            V4Field {
                Text(text = "⌕", fontSize = 14.sp, color = V4.Muted)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = V4.Text),
                    cursorBrush = SolidColor(V4.Primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.v4_picker_search_hint),
                                    fontSize = 13.sp,
                                    color = V4.SubtleResolved,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            inner()
                        }
                    }
                )
                if (query.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(V4.Surface2)
                            .padding(0.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "✕", fontSize = 11.sp, color = V4.Muted)
                    }
                }
            }

            // 列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
            ) {
                when {
                    loading -> item {
                        EmptyHint(stringResource(R.string.picker_loading))
                    }
                    apps.isEmpty() -> item {
                        EmptyHint(stringResource(R.string.picker_empty))
                    }
                    filtered.isEmpty() -> item {
                        EmptyHint(stringResource(R.string.picker_no_match_fmt, query.trim()))
                    }
                    else -> items(filtered, key = { it.packageName }) { app ->
                        val picked = app.packageName == selectedPackage
                        V4Field(onClick = { onPick(app.packageName) }) {
                            // 应用图标 + 应用名 + 包名
                            AppIcon(app = app, iconFor = iconFor)
                            Text(
                                text = app.label,
                                fontSize = 13.sp,
                                color = V4.Text,
                                fontWeight = if (picked) FontWeight.Medium else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Text(
                                text = app.packageName + (if (picked) " ✓" else ""),
                                fontSize = 11.sp,
                                color = if (picked) V4.Primary else V4.Muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            V4SheetActions {
                V4SheetButton(
                    text = stringResource(R.string.v4_picker_clear),
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                    primary = false
                )
                V4SheetButton(
                    text = stringResource(R.string.v4_done),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    primary = true
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(text = text, fontSize = 12.5.sp, color = V4.Muted)
    }
}

/**
 * 应用图标（26dp 圆角方块）。
 *
 * 拿不到图标时不留空白，改为「包名首字母 + 主色淡底」的占位块——
 * 列表里出现无字空白行比占位块更难辨认。
 */
@Composable
private fun AppIcon(app: AppInfo, iconFor: (String) -> Drawable?) {
    val drawable = remember(app.packageName) { iconFor(app.packageName) }
    if (drawable != null) {
        Image(
            bitmap = remember(drawable) { drawable.toBitmap(72, 72).asImageBitmap() },
            contentDescription = null,
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(7.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(V4.PrimarySoft),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = app.label.firstOrNull()?.uppercase() ?: "?",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = V4.Primary
            )
        }
    }
}
