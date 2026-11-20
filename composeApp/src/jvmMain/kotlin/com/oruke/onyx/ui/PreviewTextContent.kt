package com.oruke.onyx.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.oruke.onyx.app.filesystem.PreviewTextEncoding
import com.oruke.onyx.app.filesystem.PreviewTextRequest
import com.oruke.onyx.app.filesystem.PreviewTextResult
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.decodeSfmDisplayText
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.resolve
import kotlinx.coroutines.delay
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_copy
import onyx.composeapp.generated.resources.action_open_externally
import onyx.composeapp.generated.resources.action_preview_switch_encoding
import onyx.composeapp.generated.resources.action_preview_word_wrap
import onyx.composeapp.generated.resources.label_copied_brief
import onyx.composeapp.generated.resources.label_preview_empty_file
import onyx.composeapp.generated.resources.label_preview_encoding_auto
import onyx.composeapp.generated.resources.label_preview_lines_count
import onyx.composeapp.generated.resources.label_preview_loading
import onyx.composeapp.generated.resources.label_preview_too_large
import onyx.composeapp.generated.resources.label_preview_truncated_lines
import onyx.composeapp.generated.resources.label_preview_unavailable
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** 文本预览单次头部读取的最大字节上限（256 KB）。 */
internal const val TEXT_PREVIEW_MAX_BYTES = 256L * 1024L

/** 文本预览允许展示的最大行数。 */
internal const val TEXT_PREVIEW_MAX_LINES = 500

/** 复制成功短反馈时长（毫秒）。 */
private const val COPY_FEEDBACK_DURATION_MS = 1_500L

/**
 * 铺满右侧面板高度的纯文本 / 代码 / 电子书章节预览视图。
 *
 * - 顶部不再渲染冗余大图标与基础属性，直接把空间留给正文内容；
 * - 支持自动编码探测（UTF-8 / UTF-16 / GBK / Shift-JIS）与手动切换编码；
 * - 支持自动换行开关（关闭换行时提供水平滚动条）、一键复制与外部打开；
 * - 大文件采用头部截断预览并在底栏标明截断状态。
 *
 * @param entry 当前选中文件条目。
 * @param loadTextPreview 文本预览加载服务回调。
 * @param onOpenExternal 可选外部打开回调。
 */
@Composable
internal fun PreviewTextContent(
    entry: VFile,
    loadTextPreview: suspend (PreviewTextRequest) -> PreviewTextResult,
    onOpenExternal: ((VFile) -> Unit)? = null,
) {
    val palette = LocalOnyxPalette.current
    val clipboard = LocalClipboardManager.current

    var selectedEncoding by remember(entry.location) { mutableStateOf(PreviewTextEncoding.AUTO) }
    var softWrap by remember { mutableStateOf(true) }
    var encodingMenuOpen by remember { mutableStateOf(false) }
    var copiedToken by remember { mutableStateOf(0) }
    var recentlyCopied by remember { mutableStateOf(false) }

    var isLoading by remember(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes, selectedEncoding) {
        mutableStateOf(true)
    }
    var result by remember(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes, selectedEncoding) {
        mutableStateOf<PreviewTextResult?>(null)
    }

    LaunchedEffect(copiedToken) {
        if (copiedToken > 0) {
            recentlyCopied = true
            delay(COPY_FEEDBACK_DURATION_MS)
            recentlyCopied = false
        }
    }

    LaunchedEffect(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes, selectedEncoding) {
        isLoading = true
        result = loadTextPreview(
            PreviewTextRequest(
                entry = entry,
                maxBytes = TEXT_PREVIEW_MAX_BYTES,
                maxLines = TEXT_PREVIEW_MAX_LINES,
                encoding = selectedEncoding,
            )
        )
        isLoading = false
    }

    val loadedText = result as? PreviewTextResult.Text

    Column(modifier = Modifier.fillMaxSize()) {
        // 紧凑文本操作条：左编码选择，右换行 / 复制 / 外部打开图标按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.headerBackground.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            EncodingSelectorChip(
                selectedEncoding = selectedEncoding,
                detectedEncoding = loadedText?.detectedEncoding,
                menuOpen = encodingMenuOpen,
                onToggleMenu = { encodingMenuOpen = !encodingMenuOpen },
                onDismissMenu = { encodingMenuOpen = false },
                onSelectEncoding = { chosen ->
                    selectedEncoding = chosen
                    encodingMenuOpen = false
                },
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LayoutIconButton(
                    selected = softWrap,
                    onClick = { softWrap = !softWrap },
                    tooltip = stringResource(Res.string.action_preview_word_wrap),
                    modifier = Modifier.testTag("preview-wrap-toggle"),
                ) {
                    Icon(
                        key = AllIconsKeys.Actions.ToggleSoftWrap,
                        contentDescription = stringResource(Res.string.action_preview_word_wrap),
                        tint = if (softWrap) palette.accent else palette.foreground.copy(alpha = 0.80f),
                        modifier = Modifier.size(13.dp),
                    )
                }

                val textToCopy = loadedText?.value
                if (!textToCopy.isNullOrEmpty()) {
                    TitleBarIconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(textToCopy))
                            copiedToken += 1
                        },
                        tooltip = stringResource(
                            if (recentlyCopied) Res.string.label_copied_brief else Res.string.action_copy
                        ),
                        modifier = Modifier.testTag("preview-copy-text-button"),
                    ) {
                        Icon(
                            key = AllIconsKeys.Actions.Copy,
                            contentDescription = stringResource(Res.string.action_copy),
                            tint = if (recentlyCopied) palette.success else palette.foreground.copy(alpha = 0.80f),
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }

                if (onOpenExternal != null) {
                    TitleBarIconButton(
                        onClick = { onOpenExternal(entry) },
                        tooltip = stringResource(Res.string.action_open_externally),
                        modifier = Modifier.testTag("preview-open-external-button"),
                    ) {
                        Icon(
                            key = AllIconsKeys.Ide.External_link_arrow,
                            contentDescription = stringResource(Res.string.action_open_externally),
                            tint = palette.foreground.copy(alpha = 0.80f),
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
        }
        Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))

        // 主文本区：占满剩余全部高度
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(palette.surface),
        ) {
            when {
                isLoading -> Text(
                    text = stringResource(Res.string.label_preview_loading),
                    color = palette.mutedForeground,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center),
                )

                loadedText != null && loadedText.value.isEmpty() -> Text(
                    text = stringResource(Res.string.label_preview_empty_file),
                    color = palette.mutedForeground,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center),
                )

                loadedText != null -> {
                    val verticalScroll = rememberScrollState()
                    val horizontalScroll = rememberScrollState()
                    val textModifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(verticalScroll)
                        .let { mod -> if (softWrap) mod else mod.horizontalScroll(horizontalScroll) }
                        .padding(start = 10.dp, top = 8.dp, end = 16.dp, bottom = if (softWrap) 10.dp else 18.dp)

                    SelectionContainer {
                        Text(
                            text = loadedText.value,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            softWrap = softWrap,
                            color = palette.foreground,
                            modifier = textModifier.testTag("preview-text-body"),
                        )
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(verticalScroll),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp),
                    )
                    if (!softWrap) {
                        HorizontalScrollbar(
                            adapter = rememberScrollbarAdapter(horizontalScroll),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .padding(end = 10.dp, bottom = 2.dp),
                        )
                    }
                }

                result is PreviewTextResult.TooLarge -> Text(
                    text = stringResource(Res.string.label_preview_too_large),
                    color = palette.mutedForeground,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )

                result is PreviewTextResult.Failed -> Text(
                    text = (result as PreviewTextResult.Failed).reason.resolve(),
                    color = palette.errorForeground,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )

                else -> Text(
                    text = stringResource(Res.string.label_preview_unavailable),
                    color = palette.mutedForeground,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            }
        }

        // 极简底栏：文件名 + 行数 / 截断提示
        Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.headerBackground.copy(alpha = 0.5f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = entry.name.decodeSfmDisplayText(),
                color = palette.mutedForeground,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (loadedText != null) {
                val badgeText = if (loadedText.truncated) {
                    stringResource(Res.string.label_preview_truncated_lines, loadedText.lineCount)
                } else {
                    stringResource(Res.string.label_preview_lines_count, loadedText.lineCount)
                }
                Text(
                    text = badgeText,
                    color = if (loadedText.truncated) palette.accent else palette.mutedForeground,
                    fontSize = 10.5.sp,
                    fontWeight = if (loadedText.truncated) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier.testTag("preview-text-status-badge"),
                )
            }
        }
    }
}

/** 编码切换紧凑芯片与下拉弹出列表。 */
@Composable
private fun EncodingSelectorChip(
    selectedEncoding: PreviewTextEncoding,
    detectedEncoding: PreviewTextEncoding?,
    menuOpen: Boolean,
    onToggleMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onSelectEncoding: (PreviewTextEncoding) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val autoLabel = stringResource(Res.string.label_preview_encoding_auto)
    val chipText = if (selectedEncoding == PreviewTextEncoding.AUTO) {
        val detected = detectedEncoding?.displayLabel
        if (detected != null) "$autoLabel · $detected" else autoLabel
    } else {
        selectedEncoding.displayLabel
    }

    Box {
        OnyxTooltip(text = stringResource(Res.string.action_preview_switch_encoding)) {
            Row(
                modifier = Modifier
                    .testTag("preview-encoding-button")
                    .clip(RoundedCornerShape(4.dp))
                    .background(palette.surfaceVariant.copy(alpha = 0.7f))
                    .border(1.dp, palette.outlineVariant, RoundedCornerShape(4.dp))
                    .clickable(onClick = onToggleMenu)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = chipText,
                    fontSize = 10.5.sp,
                    color = palette.foreground,
                    maxLines = 1,
                )
                Icon(
                    key = AllIconsKeys.General.ArrowDown,
                    contentDescription = null,
                    tint = palette.mutedForeground,
                    modifier = Modifier.size(10.dp),
                )
            }
        }

        if (menuOpen) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, 24),
                onDismissRequest = onDismissMenu,
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    modifier = Modifier
                        .width(126.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(palette.surface)
                        .border(1.dp, palette.outline, RoundedCornerShape(5.dp))
                        .padding(vertical = 4.dp),
                ) {
                    PreviewTextEncoding.entries.forEach { option ->
                        val active = option == selectedEncoding
                        val optionText = if (option == PreviewTextEncoding.AUTO) {
                            autoLabel
                        } else {
                            option.displayLabel
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("preview-encoding-option-${option.name}")
                                .background(if (active) palette.accent.copy(alpha = 0.16f) else palette.surface)
                                .clickable { onSelectEncoding(option) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(
                                text = optionText,
                                fontSize = 11.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) palette.accent else palette.foreground,
                            )
                        }
                    }
                }
            }
        }
    }
}
