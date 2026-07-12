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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_command_palette_empty
import onyx.composeapp.generated.resources.label_command_palette_placeholder
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun CommandPalettePopup(
    items: List<CommandPaletteItem>,
    onExecute: (OnyxCommand) -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var query by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableIntStateOf(0) }
    val filteredItems = remember(items, query) {
        val queryTokens = commandPaletteQueryTokens(query)
        if (queryTokens.isEmpty()) {
            items
        } else {
            items.filter { item -> item.matchesQuery(queryTokens) }
        }
    }

    fun executeSelected() {
        val item = filteredItems.getOrNull(selectedIndex) ?: return
        if (item.enabled) {
            onExecute(item.command)
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    LaunchedEffect(filteredItems.size, query) {
        selectedIndex = selectedIndex.coerceIn(0, (filteredItems.size - 1).coerceAtLeast(0))
    }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onClose,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(430.dp)
                .border(1.dp, palette.outline, RoundedCornerShape(6.dp))
                .background(palette.floatingSurface, RoundedCornerShape(6.dp))
                .padding(8.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        return@onPreviewKeyEvent false
                    }
                    when (event.key) {
                        Key.Escape -> {
                            onClose()
                            true
                        }

                        Key.Enter -> {
                            executeSelected()
                            true
                        }

                        Key.DirectionDown -> {
                            selectedIndex = (selectedIndex + 1).coerceAtMost((filteredItems.size - 1).coerceAtLeast(0))
                            true
                        }

                        Key.DirectionUp -> {
                            selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                            true
                        }

                        else -> false
                    }
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CommandPaletteSearchField(
                query = query,
                focusRequester = focusRequester,
                onQueryChange = { nextQuery ->
                    query = nextQuery
                    selectedIndex = 0
                },
            )

            CommandPaletteResults(
                items = filteredItems,
                selectedIndex = selectedIndex,
                listState = listState,
                onExecute = onExecute,
            )
        }
    }
}

/**
 * 渲染命令面板搜索输入框。
 *
 * @param query 当前查询文本。
 * @param focusRequester 弹窗打开后使用的焦点请求器。
 * @param onQueryChange 查询文本变化回调。
 */
@Composable
private fun CommandPaletteSearchField(
    query: String,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .focusRequester(focusRequester)
            .background(palette.inputBackground, RoundedCornerShape(4.dp))
            .border(1.dp, palette.outlineVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        textStyle = TextStyle(fontSize = 12.sp, color = palette.foreground),
        singleLine = true,
        cursorBrush = SolidColor(palette.accent),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.label_command_palette_placeholder),
                        fontSize = 12.sp,
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

/**
 * 渲染命令面板过滤结果和滚动条。
 *
 * @param items 当前过滤结果。
 * @param selectedIndex 键盘选中的结果索引。
 * @param listState 结果列表滚动状态。
 * @param onExecute 执行命令回调。
 */
@Composable
private fun CommandPaletteResults(
    items: List<CommandPaletteItem>,
    selectedIndex: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onExecute: (OnyxCommand) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(palette.surface),
    ) {
        if (items.isEmpty()) {
            Text(
                text = stringResource(Res.string.label_command_palette_empty),
                color = palette.disabledForeground,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            ) {
                itemsIndexed(items) { index, item ->
                    CommandPaletteRow(
                        item = item,
                        selected = index == selectedIndex,
                        onExecute = { onExecute(item.command) },
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

private fun commandPaletteQueryTokens(query: String): List<String> {
    return query
        .trim()
        .lowercase()
        .split(Regex("\\s+"))
        .filter { token -> token.isNotBlank() }
}

private fun CommandPaletteItem.matchesQuery(tokens: List<String>): Boolean {
    val searchableText = listOfNotNull(
        label,
        command.name,
        shortcut,
        shortcut?.replace("+", ""),
    ).joinToString(" ").lowercase()
    return tokens.all { token -> searchableText.contains(token) }
}

@Composable
private fun CommandPaletteRow(
    item: CommandPaletteItem,
    selected: Boolean,
    onExecute: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val contentColor = if (item.enabled) palette.foreground else palette.disabledForeground
    val background = when {
        selected && item.enabled -> palette.rowHoverBackground
        selected -> palette.inactiveSelectionBackground
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(background)
            .clickable(enabled = item.enabled, onClick = onExecute)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            key = item.iconKey,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = item.label,
            color = contentColor,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val shortcut = item.shortcut
        if (shortcut != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = shortcut,
                color = palette.mutedForeground,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
