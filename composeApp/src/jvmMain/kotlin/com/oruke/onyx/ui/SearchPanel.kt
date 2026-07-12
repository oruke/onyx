package com.oruke.onyx.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.SearchPanelState
import com.oruke.onyx.app.component.SearchStatus
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.fileIconKey
import com.oruke.onyx.ui.theme.resolve
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_cancel_task
import onyx.composeapp.generated.resources.action_close_search_panel
import onyx.composeapp.generated.resources.action_search
import onyx.composeapp.generated.resources.label_search_cancelled
import onyx.composeapp.generated.resources.label_search_completed_summary
import onyx.composeapp.generated.resources.label_search_completed_limited_summary
import onyx.composeapp.generated.resources.label_search_failed
import onyx.composeapp.generated.resources.label_search_idle
import onyx.composeapp.generated.resources.label_search_no_results
import onyx.composeapp.generated.resources.label_search_placeholder
import onyx.composeapp.generated.resources.label_search_running_summary
import onyx.composeapp.generated.resources.label_search_scope
import onyx.composeapp.generated.resources.label_search_title
import onyx.composeapp.generated.resources.msg_unknown_error
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** 搜索结果名称列占用的宽度权重。 */
private const val SEARCH_NAME_COLUMN_WEIGHT = 0.42f

/** 搜索结果路径列占用的宽度权重。 */
private const val SEARCH_PATH_COLUMN_WEIGHT = 0.58f

@Composable
internal fun SearchPanel(
    state: SearchPanelState,
    locationLabel: (String) -> String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onOpenResult: (VFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalOnyxPalette.current
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val canSearch = state.query.isNotBlank() && state.status != SearchStatus.RUNNING

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.surface)
            .border(1.dp, palette.outlineVariant)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SearchPanelHeader(
            state = state,
            locationLabel = locationLabel,
            focusRequester = focusRequester,
            canSearch = canSearch,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            onCancel = onCancel,
            onClose = onClose,
        )

        Text(
            text = searchSummary(state),
            color = if (state.status == SearchStatus.FAILED) palette.error else palette.mutedForeground,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        SearchResults(state, listState, locationLabel, onOpenResult)
    }
}

/**
 * 渲染搜索范围、输入框与搜索控制按钮。
 *
 * @param state 当前搜索状态。
 * @param locationLabel 位置展示名称转换函数。
 * @param focusRequester 输入框焦点请求器。
 * @param canSearch 当前是否允许开始搜索。
 * @param onQueryChange 查询文本变化回调。
 * @param onSearch 开始搜索回调。
 * @param onCancel 取消搜索回调。
 * @param onClose 关闭面板回调。
 */
@Composable
private fun SearchPanelHeader(
    state: SearchPanelState,
    locationLabel: (String) -> String,
    focusRequester: FocusRequester,
    canSearch: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().height(26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(AllIconsKeys.Actions.Find, stringResource(Res.string.label_search_title), Modifier.size(14.dp))
        Text(
            text = stringResource(Res.string.label_search_title),
            color = palette.foreground,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(Res.string.label_search_scope, locationLabel(state.rootLocation)),
            color = palette.mutedForeground,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(260.dp),
        )
        SearchInput(
            query = state.query,
            enabled = state.status != SearchStatus.RUNNING,
            focusRequester = focusRequester,
            onQueryChange = onQueryChange,
            onSubmit = { if (canSearch) onSearch() },
            onClose = { if (state.status == SearchStatus.RUNNING) onCancel() else onClose() },
            modifier = Modifier.weight(1f),
        )
        SearchToolbarButton(canSearch, onSearch, Res.string.action_search, AllIconsKeys.Actions.Find)
        if (state.status == SearchStatus.RUNNING) {
            SearchToolbarButton(true, onCancel, Res.string.action_cancel_task, AllIconsKeys.Actions.Close)
        }
        SearchToolbarButton(true, onClose, Res.string.action_close_search_panel, AllIconsKeys.Actions.Close)
    }
}

/**
 * 渲染一个搜索工具栏图标按钮。
 *
 * @param enabled 是否允许点击。
 * @param onClick 点击回调。
 * @param label 按钮文案资源。
 * @param icon Jewel 图标键。
 */
@Composable
private fun SearchToolbarButton(
    enabled: Boolean,
    onClick: () -> Unit,
    label: org.jetbrains.compose.resources.StringResource,
    icon: org.jetbrains.jewel.ui.icon.IconKey,
) {
    val text = stringResource(label)
    ToolbarIconButton(enabled = enabled, onClick = onClick, tooltip = text) {
        Icon(key = icon, contentDescription = text, modifier = Modifier.size(13.dp))
    }
}

/**
 * 渲染搜索结果列表或空状态。
 *
 * @param state 当前搜索状态。
 * @param listState 结果列表滚动状态。
 * @param locationLabel 位置展示名称转换函数。
 * @param onOpenResult 打开结果回调。
 */
@Composable
private fun SearchResults(
    state: SearchPanelState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    locationLabel: (String) -> String,
    onOpenResult: (VFile) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Box(
        modifier = Modifier.fillMaxWidth().height(170.dp).background(palette.appBackground),
    ) {
        if (state.results.isEmpty()) {
            Text(
                text = emptySearchText(state),
                color = palette.disabledForeground,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                items(items = state.results, key = { entry -> entry.id }) { entry ->
                    SearchResultRow(
                        entry = entry,
                        locationLabel = locationLabel,
                        onOpen = { onOpenResult(entry) },
                    )
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun SearchInput(
    query: String,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalOnyxPalette.current
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .height(22.dp)
            .focusRequester(focusRequester)
            .background(palette.inputBackground, RoundedCornerShape(4.dp))
            .border(1.dp, palette.outlineVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.Enter -> {
                        onSubmit()
                        true
                    }

                    Key.Escape -> {
                        onClose()
                        true
                    }

                    else -> false
                }
            },
        enabled = enabled,
        textStyle = TextStyle(
            fontSize = 11.sp,
            color = palette.foreground,
        ),
        singleLine = true,
        cursorBrush = SolidColor(palette.accent),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.label_search_placeholder),
                        fontSize = 11.sp,
                        color = palette.disabledForeground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun SearchResultRow(
    entry: VFile,
    locationLabel: (String) -> String,
    onOpen: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val parent = entry.parentLocation ?: entry.location
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            key = if (entry.kind == VFileKind.DIRECTORY) AllIconsKeys.Nodes.Folder else fileIconKey(entry.name),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = entry.name,
            color = palette.foreground,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(SEARCH_NAME_COLUMN_WEIGHT),
        )
        Text(
            text = locationLabel(parent),
            color = palette.mutedForeground,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(SEARCH_PATH_COLUMN_WEIGHT),
        )
    }
}

@Composable
private fun searchSummary(state: SearchPanelState): String {
    return when (state.status) {
        SearchStatus.IDLE -> stringResource(Res.string.label_search_idle)
        SearchStatus.RUNNING -> stringResource(
            Res.string.label_search_running_summary,
            state.scannedEntryCount,
            state.results.size,
        )
        SearchStatus.COMPLETED -> {
            if (state.limitReached) {
                stringResource(
                    Res.string.label_search_completed_limited_summary,
                    state.results.size,
                    state.scannedEntryCount,
                )
            } else {
                stringResource(
                    Res.string.label_search_completed_summary,
                    state.results.size,
                    state.scannedEntryCount,
                )
            }
        }
        SearchStatus.FAILED -> stringResource(
            Res.string.label_search_failed,
            state.error?.resolve() ?: stringResource(Res.string.msg_unknown_error),
        )
        SearchStatus.CANCELLED -> stringResource(Res.string.label_search_cancelled, state.results.size)
    }
}

@Composable
private fun emptySearchText(state: SearchPanelState): String {
    return when (state.status) {
        SearchStatus.IDLE -> stringResource(Res.string.label_search_idle)
        SearchStatus.RUNNING -> stringResource(Res.string.label_search_running_summary, state.scannedEntryCount, 0)
        SearchStatus.COMPLETED -> {
            if (state.limitReached) {
                stringResource(
                    Res.string.label_search_completed_limited_summary,
                    state.results.size,
                    state.scannedEntryCount,
                )
            } else {
                stringResource(Res.string.label_search_no_results)
            }
        }
        SearchStatus.FAILED -> stringResource(
            Res.string.label_search_failed,
            state.error?.resolve() ?: stringResource(Res.string.msg_unknown_error),
        )
        SearchStatus.CANCELLED -> stringResource(Res.string.label_search_cancelled, state.results.size)
    }
}
