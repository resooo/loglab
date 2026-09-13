package com.loglab.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loglab.app.ui.theme.V4
import com.loglab.app.R
import androidx.compose.ui.res.stringResource

/**
 * v4「搜索日志」底部弹出层。
 *
 * 设计稿要点：
 *  - 页面上不再常驻输入框，搜索改成底部层
 *  - 输入即过滤（实时），失焦不丢内容
 *  - 「匹配方式」二选一：过滤（只留命中）/ 高亮（全留标黄）
 *  - 「最近搜索」标签行，点一下直接套用
 *  - 底部 [取消] [应用]
 *
 * 注意：打开时把当前生效值拷进本地草稿，只有点「应用」才回调上去；
 * 「取消」直接丢弃草稿——这样实时过滤的预览不会污染外部状态。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    initialQuery: String,
    initialFilterMode: Boolean,
    recentQueries: List<String>,
    onDismiss: () -> Unit,
    onApply: (query: String, filterMode: Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by rememberSaveable { mutableStateOf(initialQuery) }
    var filterMode by rememberSaveable { mutableStateOf(initialFilterMode) }

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
            V4SheetHeader(text = stringResource(R.string.v4_search_title))

            // 输入框 `.field`：⌕ 前缀 + 输入 + ✕ 清除
            V4Field {
                Text(text = "⌕", fontSize = 14.sp, color = V4.Muted)
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = V4.Text),
                    cursorBrush = SolidColor(V4.Primary),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Search
                    ),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (draft.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.v4_search_placeholder),
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
                if (draft.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .clickable { draft = "" },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "✕", fontSize = 12.sp, color = V4.SubtleResolved)
                    }
                }
            }

            V4Tagline(text = stringResource(R.string.v4_search_match_mode))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                V4OptChip(
                    text = stringResource(R.string.v4_search_mode_filter),
                    selected = filterMode,
                    onClick = { filterMode = true }
                )
                V4OptChip(
                    text = stringResource(R.string.v4_search_mode_highlight),
                    selected = !filterMode,
                    onClick = { filterMode = false }
                )
            }

            if (recentQueries.isNotEmpty()) {
                V4Tagline(text = stringResource(R.string.v4_search_recent))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    recentQueries.forEach { q ->
                        V4OptChip(
                            text = q,
                            selected = draft == q,
                            onClick = { draft = q }
                        )
                    }
                }
            }

            V4SheetActions {
                V4SheetButton(
                    text = stringResource(R.string.v4_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    primary = false
                )
                V4SheetButton(
                    text = stringResource(R.string.v4_apply),
                    onClick = { onApply(draft.trim(), filterMode) },
                    modifier = Modifier.weight(1f),
                    primary = true
                )
            }
        }
    }
}

// ══════════════════════════════ 通用小件 ══════════════════════════════

/**
 * 设置行外壳 `.field{background:#fff;border-radius:14px;padding:12px 14px;margin-bottom:9px}`。
 * 内容按 Row 排布，调用方自行放 前缀 / 文案 / 尾部值。
 */
@Composable
fun V4Field(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    val base = modifier
        .fillMaxWidth()
        .padding(bottom = 9.dp)
        .clip(RoundedCornerShape(14.dp))
        .background(V4.Surface)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 14.dp, vertical = 12.dp)
    Row(
        modifier = base,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

/**
 * 可选项胶囊 `.opt{padding:7px 13px;border-radius:16px;font-size:12px}`。
 * `selected` 对应 `.opt.on`（主色底白字）。
 */
@Composable
fun V4OptChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) V4.Primary else V4.Surface,
        modifier = modifier
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) Color.White else V4.IconIdleResolved,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp)
        )
    }
}

/** 底部动作行 `.sheet-acts{display:flex;gap:9px;margin-top:13px}` */
@Composable
fun V4SheetActions(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/**
 * 底部层按钮 `.sbtn{height:44px;border-radius:22px;font-size:13.5px;font-weight:500}`。
 * `primary = true` 主按钮蓝底白字（`.sbtn.p`），否则白底灰字（`.sbtn.g`）。
 */
@Composable
fun V4SheetButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(22.dp),
        color = when {
            !enabled -> V4.DisabledResolved
            primary -> V4.Primary
            else -> V4.Surface
        },
        modifier = modifier.height(44.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = when {
                    !enabled -> V4.DisabledTextResolved
                    primary -> Color.White
                    else -> V4.IconIdleResolved
                },
                maxLines = 1
            )
        }
    }
}

/** 表单行尾的取值文字 `.field .val{font-size:12.5px;color:var(--muted);margin-left:auto}` */
@Composable
fun RowScope.V4FieldValue(text: String) {
    Text(
        text = text,
        fontSize = 12.5.sp,
        color = V4.Muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
        textAlign = androidx.compose.ui.text.style.TextAlign.End
    )
}
