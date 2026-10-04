package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.filesystem.VfsBreadcrumb
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

// ── Address bar ────────────────────────────────────────────────────────────

/**
 * 显示面包屑或可编辑路径，确认后通过面板 API 导航。
 *
 * @param location 当前 VFS 位置。
 * @param onActivate 激活所属面板。
 * @param onOpenLocation 导航请求。
 * @param buildBreadcrumbs 统一位置服务提供的面包屑。
 * @param onEditingChange 编辑状态变化回调。
 */
@Composable
internal fun HybridAddressBar(
    location: String,
    onActivate: () -> Unit,
    onOpenLocation: (String) -> Unit,
    buildBreadcrumbs: (String) -> List<VfsBreadcrumb>,
    onEditingChange: (Boolean) -> Unit = {},
) {
    var editing by remember { mutableStateOf(false) }
    var draftValue by remember(location) {
        mutableStateOf(TextFieldValue(location, TextRange(location.length)))
    }

    LaunchedEffect(editing) {
        onEditingChange(editing)
    }

    LaunchedEffect(location) {
        if (!editing) draftValue = TextFieldValue(location, TextRange(location.length))
    }

    if (editing) {
        val focusRequester = remember { FocusRequester() }
        // 保护标志：首次组合时 onFocusChanged 会以 isFocused=false 触发，
        // 必须等获得过焦点之后才响应失焦退出。
        var hasFocused by remember { mutableStateOf(false) }

        BasicTextField(
            value = draftValue,
            onValueChange = { draftValue = it },
            modifier = Modifier
                .fillMaxWidth()
                .background(LocalOnyxPalette.current.inputBackground, RoundedCornerShape(4.dp))
                .border(1.dp, LocalOnyxPalette.current.accent, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        hasFocused = true
                    } else if (hasFocused && editing) {
                        // 获得过焦点后才响应失焦 → 退出编辑
                        editing = false
                        draftValue = TextFieldValue(location, TextRange(location.length))
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter -> {
                            val submittedLocation = draftValue.text
                            editing = false
                            onActivate()
                            onOpenLocation(submittedLocation)
                            true
                        }

                        Key.Escape -> {
                            editing = false
                            draftValue = TextFieldValue(location, TextRange(location.length))
                            true
                        }

                        else -> false
                    }
                },
            textStyle = TextStyle(color = LocalOnyxPalette.current.foreground, fontSize = 12.sp),
            singleLine = true,
        )

        // 进入编辑模式时自动聚焦并全选
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            draftValue = TextFieldValue(location, TextRange(0, location.length))
        }
    } else {
        BreadcrumbAddressBar(
            location = location,
            onActivate = onActivate,
            onEdit = {
                draftValue = TextFieldValue(location, TextRange(0, location.length))
                editing = true
            },
            onOpenLocation = onOpenLocation,
            buildBreadcrumbs = buildBreadcrumbs,
        )
    }
}

/**
 * 显示优先露出末级路径的面包屑，单击空白进入编辑。
 *
 * @param location 当前 VFS 位置。
 * @param onActivate 激活所属面板。
 * @param onEdit 进入文本编辑。
 * @param onOpenLocation 请求进入某级路径。
 * @param buildBreadcrumbs 统一面包屑构建能力。
 */
@Composable
internal fun BreadcrumbAddressBar(
    location: String,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onOpenLocation: (String) -> Unit,
    buildBreadcrumbs: (String) -> List<VfsBreadcrumb>,
) {
    val scrollState = rememberScrollState()
    val breadcrumbs = remember(location) { buildBreadcrumbs(location) }
    val maxScroll = scrollState.maxValue

    LaunchedEffect(location, maxScroll) {
        scrollState.scrollTo(maxScroll)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LocalOnyxPalette.current.inputBackground, RoundedCornerShape(4.dp))
            .horizontalScroll(scrollState)
            .clickable { onActivate(); onEdit() }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        breadcrumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Icon(
                    key = AllIconsKeys.General.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = crumb.label,
                color = if (index == breadcrumbs.lastIndex) {
                    LocalOnyxPalette.current.foreground
                } else {
                    LocalOnyxPalette.current.mutedForeground
                },
                fontSize = 12.sp,
                fontWeight = if (index == breadcrumbs.lastIndex) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clickable {
                    onActivate()
                    onOpenLocation(crumb.location)
                },
            )
        }
    }
}
