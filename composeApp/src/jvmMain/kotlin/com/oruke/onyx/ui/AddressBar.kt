package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.oruke.onyx.app.filesystem.VfsBreadcrumb
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import kotlinx.coroutines.delay
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_breadcrumb_collapsed_ancestors
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

// ── Address bar ────────────────────────────────────────────────────────────

/** 地址栏路径建议防抖延迟。 */
private const val ADDRESS_SUGGESTION_DEBOUNCE_MS = 100L

/**
 * 显示面包屑或可编辑路径，确认后通过面板 API 导航。
 *
 * 支持按标签页隔离编辑态、`Ctrl+L` / `Alt+D` 快捷键直达编辑、临时失焦保留未提交草稿，
 * 以及输入过程中的本地目录与历史位置自动补全。
 *
 * @param location 当前 VFS 位置。
 * @param onActivate 激活所属面板。
 * @param onOpenLocation 导航请求。
 * @param buildBreadcrumbs 统一位置服务提供的面包屑。
 * @param onEditingChange 编辑状态变化回调。
 * @param tabId 所属标签页标识，切换标签时自动退出编辑态避免草稿串页。
 * @param focusRequestId 递增触发进入编辑并全选路径的外部聚焦请求编号。
 * @param knownLocations 收藏、最近访问和当前面板已知目录位置，用于路径自动补全。
 * @param suggestLocations 异步路径自动补全提供器。
 */
@Composable
internal fun HybridAddressBar(
    location: String,
    onActivate: () -> Unit,
    onOpenLocation: (String) -> Unit,
    buildBreadcrumbs: (String) -> List<VfsBreadcrumb>,
    onEditingChange: (Boolean) -> Unit = {},
    tabId: String = "",
    focusRequestId: Int = 0,
    knownLocations: List<String> = emptyList(),
    suggestLocations: (suspend (String, List<String>) -> List<String>)? = null,
) {
    var editing by remember(tabId) { mutableStateOf(false) }
    var preservedDraft by remember(tabId, location) { mutableStateOf<String?>(null) }
    var draftValue by remember(tabId, location) {
        mutableStateOf(TextFieldValue(location, TextRange(location.length)))
    }
    var lastHandledFocusRequestId by remember { mutableIntStateOf(focusRequestId) }

    fun enterEditMode() {
        val initialText = preservedDraft ?: location
        draftValue = TextFieldValue(initialText, TextRange(0, initialText.length))
        editing = true
        onEditingChange(true)
    }

    fun exitEditMode() {
        editing = false
        onEditingChange(false)
    }

    LaunchedEffect(tabId, editing) {
        onEditingChange(editing)
    }
    DisposableEffect(Unit) {
        onDispose { onEditingChange(false) }
    }

    LaunchedEffect(focusRequestId) {
        if (focusRequestId > lastHandledFocusRequestId) {
            lastHandledFocusRequestId = focusRequestId
            enterEditMode()
        }
    }

    LaunchedEffect(location) {
        if (!editing) {
            preservedDraft = null
            draftValue = TextFieldValue(location, TextRange(location.length))
        }
    }

    if (editing) {
        AddressBarEditor(
            location = location,
            draftValue = draftValue,
            knownLocations = knownLocations,
            suggestLocations = suggestLocations,
            onDraftChange = { nextValue ->
                if (!editing) return@AddressBarEditor
                val textChanged = nextValue.text != draftValue.text
                draftValue = nextValue
                if (textChanged) {
                    preservedDraft = nextValue.text.takeIf { it != location }
                }
            },
            onSubmit = { submittedLocation ->
                preservedDraft = null
                exitEditMode()
                onActivate()
                onOpenLocation(submittedLocation)
            },
            onCancel = {
                preservedDraft = null
                exitEditMode()
                draftValue = TextFieldValue(location, TextRange(location.length))
            },
            onBlur = {
                if (!editing) return@AddressBarEditor
                // 临时切窗或点到外部时退出编辑态，但保留未提交的修改草稿，避免再次点回重打。
                preservedDraft = draftValue.text.takeIf { it.isNotBlank() && it != location }
                exitEditMode()
            },
        )
    } else {
        BreadcrumbAddressBar(
            location = location,
            onActivate = onActivate,
            onEdit = ::enterEditMode,
            onOpenLocation = onOpenLocation,
            buildBreadcrumbs = buildBreadcrumbs,
        )
    }
}

/**
 * 渲染编辑态地址输入框及非抢焦自动补全建议弹层。
 */
@Composable
private fun AddressBarEditor(
    location: String,
    draftValue: TextFieldValue,
    knownLocations: List<String>,
    suggestLocations: (suspend (String, List<String>) -> List<String>)?,
    onDraftChange: (TextFieldValue) -> Unit,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    onBlur: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    // 保护标志：首次组合时 onFocusChanged 会以 isFocused=false 触发，必须等获得过焦点之后才响应失焦；
    // 同时在显式提交或按 Esc 取消卸载输入框时屏蔽随之而来的失焦与选区折叠回调，避免旧草稿反向覆写 preservedDraft。
    var hasFocused by remember { mutableStateOf(false) }
    var editEnded by remember { mutableStateOf(false) }
    var editorWidthPx by remember { mutableIntStateOf(0) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var highlightedIndex by remember { mutableIntStateOf(-1) }

    val submitSafely: (String) -> Unit = { chosen ->
        editEnded = true
        onSubmit(chosen)
    }
    val cancelSafely: () -> Unit = {
        editEnded = true
        onCancel()
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(draftValue.text, knownLocations, suggestLocations) {
        val text = draftValue.text.trim()
        if (text.isEmpty() || suggestLocations == null) {
            suggestions = emptyList()
            highlightedIndex = -1
            return@LaunchedEffect
        }
        delay(ADDRESS_SUGGESTION_DEBOUNCE_MS)
        val loaded = suggestLocations(text, knownLocations)
        suggestions = loaded
        highlightedIndex = highlightedIndex.takeIf { it in loaded.indices } ?: -1
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { size -> editorWidthPx = size.width },
    ) {
        BasicTextField(
            value = draftValue,
            onValueChange = { next ->
                if (editEnded) return@BasicTextField
                onDraftChange(next)
                highlightedIndex = -1
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("address-bar-input")
                .background(palette.inputBackground, RoundedCornerShape(4.dp))
                .border(1.dp, palette.accent, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        hasFocused = true
                    } else if (hasFocused && !editEnded) {
                        onBlur()
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            val chosen = suggestions.getOrNull(highlightedIndex) ?: draftValue.text
                            submitSafely(chosen)
                            true
                        }

                        Key.Escape -> {
                            cancelSafely()
                            true
                        }

                        Key.DirectionDown -> {
                            if (suggestions.isNotEmpty()) {
                                highlightedIndex = (highlightedIndex + 1).coerceAtMost(suggestions.lastIndex)
                                true
                            } else {
                                false
                            }
                        }

                        Key.DirectionUp -> {
                            if (suggestions.isNotEmpty()) {
                                highlightedIndex = if (highlightedIndex <= 0) {
                                    suggestions.lastIndex
                                } else {
                                    highlightedIndex - 1
                                }
                                true
                            } else {
                                false
                            }
                        }

                        Key.Tab -> {
                            val candidate = suggestions.getOrNull(highlightedIndex)
                                ?: suggestions.firstOrNull()
                            if (candidate != null) {
                                onDraftChange(TextFieldValue(candidate, TextRange(candidate.length)))
                                highlightedIndex = -1
                                true
                            } else {
                                false
                            }
                        }

                        else -> false
                    }
                },
            textStyle = TextStyle(color = palette.foreground, fontSize = LocalOnyxAppearance.current.listFontSize),
            cursorBrush = SolidColor(palette.accent),
            singleLine = true,
        )

        if (suggestions.isNotEmpty()) {
            val popupWidthDp = with(density) { editorWidthPx.toDp() }.coerceAtLeast(240.dp)
            AddressSuggestionsPopup(
                suggestions = suggestions,
                highlightedIndex = highlightedIndex,
                widthDp = popupWidthDp,
                onSelect = submitSafely,
            )
        }
    }
}

/**
 * 渲染贴合地址栏下方的自动补全建议弹层（非抢焦，不影响输入框持续键入）。
 *
 * @param suggestions 匹配的路径候选列表。
 * @param highlightedIndex 当前键盘高亮的候选下标。
 * @param widthDp 弹窗渲染宽度。
 * @param onSelect 点击某项候选路径的回调。
 */
@Composable
private fun AddressSuggestionsPopup(
    suggestions: List<String>,
    highlightedIndex: Int,
    widthDp: androidx.compose.ui.unit.Dp,
    onSelect: (String) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val shape = RoundedCornerShape(6.dp)
    Popup(
        popupPositionProvider = BelowAnchorPositionProvider,
        properties = PopupProperties(focusable = false),
    ) {
        Column(
            modifier = Modifier
                .width(widthDp)
                .testTag("address-bar-suggestions")
                .background(palette.floatingSurface, shape)
                .border(1.dp, palette.outline, shape)
                .padding(vertical = 4.dp),
        ) {
            suggestions.forEachIndexed { index, path ->
                val interactionSource = remember(path) { MutableInteractionSource() }
                val isHovered by interactionSource.collectIsHoveredAsState()
                val isSelected = index == highlightedIndex || isHovered
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .hoverable(interactionSource)
                        .background(if (isSelected) palette.rowHoverBackground else Color.Transparent)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onSelect(path) },
                        )
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        key = AllIconsKeys.Nodes.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = path,
                        color = palette.foreground,
                        fontSize = LocalOnyxAppearance.current.listFontSize,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 将地址栏自动补全弹层锚定到输入框正下方并保持在窗口范围内。 */
private object BelowAnchorPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val belowY = anchorBounds.bottom + 2
        val aboveY = anchorBounds.top - popupContentSize.height - 2
        val y = if (belowY + popupContentSize.height <= windowSize.height || aboveY < 0) {
            belowY.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        } else {
            aboveY
        }
        return IntOffset(x, y)
    }
}

/**
 * 根据可用宽度计算应折叠到 `...` 菜单的前导祖先面包屑下标和直接展示的尾部面包屑下标。
 *
 * 始终优先保留末级当前目录并从右向左尽可能展示相邻上级目录，避免出现半截字符左裁切。
 *
 * @param labelWidthsPx 各级面包屑文字测量宽度（像素）。
 * @param availableWidthPx 面包屑内容区可用总宽度（像素）。
 * @param separatorWidthPx 相邻两级之间分隔图标及间距占宽（像素）。
 * @param overflowChipWidthPx 折叠触发按钮 `...` 及紧随其后的分隔符占宽（像素）。
 * @return Pair(已折叠前导下标列表, 可见下标列表)。
 */
internal fun selectVisibleBreadcrumbIndices(
    labelWidthsPx: List<Float>,
    availableWidthPx: Float,
    separatorWidthPx: Float,
    overflowChipWidthPx: Float,
): Pair<List<Int>, List<Int>> {
    if (labelWidthsPx.isEmpty()) return emptyList<Int>() to emptyList()
    if (labelWidthsPx.size == 1 || availableWidthPx <= 0f) {
        return emptyList<Int>() to labelWidthsPx.indices.toList()
    }
    val totalWidth = labelWidthsPx.sum() + separatorWidthPx * (labelWidthsPx.size - 1)
    if (totalWidth <= availableWidthPx) {
        return emptyList<Int>() to labelWidthsPx.indices.toList()
    }

    // 至少保留末级目录，并从右向左贪心保留能完整放下的相邻上级目录。
    var firstVisibleIndex = labelWidthsPx.lastIndex
    var usedWidth = overflowChipWidthPx + labelWidthsPx.last()
    for (index in labelWidthsPx.lastIndex - 1 downTo 1) {
        val candidateWidth = usedWidth + separatorWidthPx + labelWidthsPx[index]
        if (candidateWidth <= availableWidthPx) {
            usedWidth = candidateWidth
            firstVisibleIndex = index
        } else {
            break
        }
    }
    val collapsed = (0 until firstVisibleIndex).toList()
    val visible = (firstVisibleIndex..labelWidthsPx.lastIndex).toList()
    return collapsed to visible
}

/**
 * 显示优先露出末级路径的自适应面包屑，超出可用宽度时将前导祖先目录折叠进 `...` 下拉菜单，单击空白进入编辑。
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
    val palette = LocalOnyxPalette.current
    val fontSize = LocalOnyxAppearance.current.listFontSize
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    // 名称来自纯内存快照；设备首次发现后应允许更新名称，不更改当前地址身份。
    val breadcrumbs = buildBreadcrumbs(location)
    val labels = breadcrumbs.map { crumb ->
        crumb.storageLabel?.let { storageVolumeTitle(it) } ?: desktopLocationLabel(crumb.label)
    }
    var overflowExpanded by remember(location) { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("address-bar-breadcrumbs")
            .background(palette.inputBackground, RoundedCornerShape(4.dp))
            .clickable { onActivate(); onEdit() }
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        val availableWidthPx = with(density) { maxWidth.toPx() }
        val separatorWidthPx = with(density) { 18.dp.toPx() }
        val overflowChipWidthPx = with(density) { 42.dp.toPx() }
        val labelWidthsPx = remember(labels, fontSize, density) {
            labels.mapIndexed { index, text ->
                val weight = if (index == labels.lastIndex) FontWeight.SemiBold else FontWeight.Normal
                textMeasurer.measure(
                    text = text,
                    style = TextStyle(fontSize = fontSize, fontWeight = weight),
                    maxLines = 1,
                ).size.width.toFloat() + with(density) { 4.dp.toPx() }
            }
        }
        val (collapsedIndices, visibleIndices) = remember(
            labelWidthsPx,
            availableWidthPx,
            separatorWidthPx,
            overflowChipWidthPx,
        ) {
            selectVisibleBreadcrumbIndices(
                labelWidthsPx = labelWidthsPx,
                availableWidthPx = availableWidthPx,
                separatorWidthPx = separatorWidthPx,
                overflowChipWidthPx = overflowChipWidthPx,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (collapsedIndices.isNotEmpty()) {
                val overflowDesc = stringResource(Res.string.label_breadcrumb_collapsed_ancestors)
                val overflowHoverSrc = remember { MutableInteractionSource() }
                val overflowHovered by overflowHoverSrc.collectIsHoveredAsState()
                Box {
                    Text(
                        text = "…",
                        color = if (overflowHovered || overflowExpanded) palette.foreground else palette.mutedForeground,
                        fontSize = fontSize,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .testTag("address-bar-breadcrumb-overflow")
                            .semantics { contentDescription = overflowDesc }
                            .hoverable(overflowHoverSrc)
                            .background(
                                if (overflowHovered || overflowExpanded) palette.rowHoverBackground else Color.Transparent,
                                RoundedCornerShape(3.dp),
                            )
                            .clickable {
                                onActivate()
                                overflowExpanded = !overflowExpanded
                            }
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                    if (overflowExpanded) {
                        BreadcrumbOverflowMenu(
                            collapsedBreadcrumbs = collapsedIndices.map { idx -> breadcrumbs[idx] to labels[idx] },
                            fontSize = fontSize,
                            onSelect = { targetLocation ->
                                overflowExpanded = false
                                onActivate()
                                onOpenLocation(targetLocation)
                            },
                            onDismiss = { overflowExpanded = false },
                        )
                    }
                }
                Icon(
                    key = AllIconsKeys.General.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }

            visibleIndices.forEachIndexed { pos, crumbIndex ->
                if (pos > 0) {
                    Icon(
                        key = AllIconsKeys.General.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                }
                val isLast = crumbIndex == breadcrumbs.lastIndex
                val crumb = breadcrumbs[crumbIndex]
                Text(
                    text = labels[crumbIndex],
                    color = if (isLast) palette.foreground else palette.mutedForeground,
                    fontSize = fontSize,
                    fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .let { base -> if (isLast) base.weight(1f, fill = false) else base }
                        .clickable {
                            onActivate()
                            onOpenLocation(crumb.location)
                        },
                )
            }
        }
    }
}

/**
 * 渲染折叠后的祖先目录快速跳转下拉菜单。
 *
 * @param collapsedBreadcrumbs 已折叠的祖先面包屑及显示标签列表。
 * @param fontSize 菜单项字号。
 * @param onSelect 点击某项祖先目录时的跳转回调。
 * @param onDismiss 关闭下拉菜单回调。
 */
@Composable
private fun BreadcrumbOverflowMenu(
    collapsedBreadcrumbs: List<Pair<VfsBreadcrumb, String>>,
    fontSize: androidx.compose.ui.unit.TextUnit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val shape = RoundedCornerShape(6.dp)
    Popup(
        popupPositionProvider = BelowAnchorPositionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 160.dp, max = 320.dp)
                .testTag("address-bar-breadcrumb-overflow-menu")
                .background(palette.floatingSurface, shape)
                .border(1.dp, palette.outline, shape)
                .padding(vertical = 4.dp),
        ) {
            collapsedBreadcrumbs.forEach { (crumb, label) ->
                val interactionSource = remember(crumb.location) { MutableInteractionSource() }
                val isHovered by interactionSource.collectIsHoveredAsState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .hoverable(interactionSource)
                        .background(if (isHovered) palette.rowHoverBackground else Color.Transparent)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onSelect(crumb.location) },
                        )
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        key = AllIconsKeys.Nodes.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = label,
                        color = palette.foreground,
                        fontSize = fontSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
