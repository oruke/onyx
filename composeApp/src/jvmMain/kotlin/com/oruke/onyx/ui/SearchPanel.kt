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
import androidx.compose.ui.graphics.Color
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
        Row(
            modifier = Modifier.fillMaxWidth().height(26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                key = AllIconsKeys.Actions.Find,
                contentDescription = stringResource(Res.string.label_search_title),
                modifier = Modifier.size(14.dp),
            )
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
                onSubmit = {
                    if (canSearch) {
                        onSearch()
                    }
                },
                onClose = {
                    if (state.status == SearchStatus.RUNNING) {
                        onCancel()
                    } else {
                        onClose()
                    }
                },
                modifier = Modifier.weight(1f),
            )
            ToolbarIconButton(
                enabled = canSearch,
                onClick = onSearch,
                tooltip = stringResource(Res.string.action_search),
            ) {
                Icon(
                    key = AllIconsKeys.Actions.Find,
                    contentDescription = stringResource(Res.string.action_search),
                    modifier = Modifier.size(13.dp),
                )
            }
            if (state.status == SearchStatus.RUNNING) {
                ToolbarIconButton(
                    enabled = true,
                    onClick = onCancel,
                    tooltip = stringResource(Res.string.action_cancel_task),
                ) {
                    Icon(
                        key = AllIconsKeys.Actions.Close,
                        contentDescription = stringResource(Res.string.action_cancel_task),
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            ToolbarIconButton(
                enabled = true,
                onClick = onClose,
                tooltip = stringResource(Res.string.action_close_search_panel),
            ) {
                Icon(
                    key = AllIconsKeys.Actions.Close,
                    contentDescription = stringResource(Res.string.action_close_search_panel),
                    modifier = Modifier.size(13.dp),
                )
            }
        }

        Text(
            text = searchSummary(state),
            color = if (state.status == SearchStatus.FAILED) Color(0xFFD74E4E) else palette.mutedForeground,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .background(palette.appBackground),
        ) {
            if (state.results.isEmpty()) {
                Text(
                    text = emptySearchText(state),
                    color = palette.disabledForeground,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                ) {
                    items(
                        items = state.results,
                        key = { entry -> entry.id },
                    ) { entry ->
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
            modifier = Modifier.weight(0.42f),
        )
        Text(
            text = locationLabel(parent),
            color = palette.mutedForeground,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.58f),
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
