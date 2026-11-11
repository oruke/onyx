package com.oruke.onyx.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.DialogWindowScope
import androidx.compose.ui.window.rememberDialogState
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.ui.theme.LocalOnyxSettings
import com.oruke.onyx.ui.theme.OnyxPalette
import com.oruke.onyx.ui.theme.OnyxWindowTheme
import com.oruke.onyx.ui.theme.rememberOnyxPalette
import com.oruke.onyx.ui.theme.rememberOnyxWindowIconPainter
import com.oruke.onyx.ui.theme.resolveEffectiveDarkTheme
import java.awt.Dimension
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Window
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_close_window
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * 与主窗口、搜索窗口及任务中心窗口保持完全一致外观的沉浸式模态对话框容器。
 *
 * 在支持 JBR 窗口装饰的 Windows / macOS 平台上通过 [JbrDialogTitleBarBridge] 移除原生亮蓝系统标题栏与默认图标，
 * 保留系统级模态阻塞、边缘缩放、圆角阴影与原生关闭按钮；在 Linux 或非 JBR 运行时自动降级为无框自绘标题栏。
 *
 * @param onCloseRequest 关闭对话框请求回调。
 * @param title 沉浸式标题栏显示的窗口标题。
 * @param state 对话框尺寸与位置状态。
 * @param resizable 是否允许用户拖拽缩放对话框尺寸。
 * @param settings 对话框应用的主题与缩放设置，默认继承宿主主窗口的 [LocalOnyxSettings]。
 * @param minimumWidthPx 窗口允许缩小的最小宽度（基准像素）。
 * @param minimumHeightPx 窗口允许缩小的最小高度（基准像素）。
 * @param scaleMinimumSizeWithUi 是否按 [OnyxSettings.uiScale] 比例缩放最小窗口尺寸。
 * @param windowName AWT 窗口标识名称，便于焦点与窗口测试检索。
 * @param onPreviewKeyEvent 优先于默认 Escape 关闭逻辑执行的按键拦截器。
 * @param content 包裹在 [OnyxWindowTheme] 内的对话框主体内容。
 */
@Suppress("LongParameterList")
@Composable
internal fun OnyxDialogWindow(
    onCloseRequest: () -> Unit,
    title: String,
    state: DialogState = rememberDialogState(),
    resizable: Boolean = false,
    settings: OnyxSettings = LocalOnyxSettings.current,
    minimumWidthPx: Int? = null,
    minimumHeightPx: Int? = null,
    scaleMinimumSizeWithUi: Boolean = false,
    windowName: String? = null,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable DialogWindowScope.() -> Unit,
) {
    val useJbrCustomTitleBar = remember { JbrDialogTitleBarBridge.isSupported() }
    val windowIcon = rememberOnyxWindowIconPainter()

    DialogWindow(
        onCloseRequest = onCloseRequest,
        title = title,
        icon = windowIcon,
        state = state,
        undecorated = !useJbrCustomTitleBar,
        resizable = resizable,
        onPreviewKeyEvent = { event ->
            if (onPreviewKeyEvent(event)) {
                true
            } else if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onCloseRequest()
                true
            } else {
                false
            }
        },
    ) {
        val dialogScope = this
        val windowActive = rememberWindowActiveState(window)
        val customTitleBar = remember(useJbrCustomTitleBar) {
            if (useJbrCustomTitleBar) JbrDialogTitleBarBridge.createTitleBar() else null
        }
        val isDark = resolveEffectiveDarkTheme(settings.themeMode, isSystemInDarkTheme())
        val palette = rememberOnyxPalette(
            themeMode = settings.themeMode,
            themePreset = settings.themePreset,
            themeColors = settings.themeColors,
        )
        var systemTitleBarInsets by remember { mutableStateOf(PaddingValues(0.dp)) }

        SideEffect {
            if (windowName != null) {
                window.name = windowName
            }
            if (minimumWidthPx != null && minimumHeightPx != null) {
                val factor = if (scaleMinimumSizeWithUi) settings.uiScale / UI_SCALE_PERCENT_BASE else 1.0
                window.minimumSize = Dimension(
                    (minimumWidthPx * factor).toInt(),
                    (minimumHeightPx * factor).toInt(),
                )
            }
            if (customTitleBar != null) {
                customTitleBar.bindToDialog(
                    dialog = window,
                    heightDp = DIALOG_TITLE_BAR_HEIGHT_DP,
                    darkControls = palette.titleBarBackground.luminance() < DARK_CONTROLS_LUMINANCE_THRESHOLD,
                )
                val updatedInsets = PaddingValues(
                    start = customTitleBar.leftInset.dp,
                    end = customTitleBar.rightInset.dp,
                )
                if (systemTitleBarInsets != updatedInsets) {
                    systemTitleBarInsets = updatedInsets
                }
            }
        }

        OnyxDialogSurface(
            title = title,
            settings = settings,
            palette = palette,
            isDark = isDark,
            windowIcon = windowIcon,
            windowActive = windowActive,
            drawOuterBorder = customTitleBar == null,
            showCustomCloseButton = customTitleBar == null,
            titleBarInsets = systemTitleBarInsets,
            onCloseRequest = onCloseRequest,
            titleBarBackgroundModifier = if (customTitleBar != null) {
                Modifier.jbrCustomTitleBarPointerBridge(customTitleBar)
            } else {
                Modifier.undecoratedWindowDragGesture(window)
            },
        ) {
            dialogScope.content()
        }
    }
}

/**
 * 渲染对话框的沉浸式标题栏、1px 分割线、可选无框外边框以及包裹在 [OnyxWindowTheme] 中的内容区域。
 *
 * @param title 窗口标题文案。
 * @param settings 对话框使用的设置。
 * @param palette 在窗口基础密度上解析的语义调色板。
 * @param isDark 当前是否为深色模式。
 * @param windowIcon 标题栏左侧应用图标。
 * @param windowActive 当前窗口是否处于激活焦点状态。
 * @param drawOuterBorder 无框模式下是否绘制 1dp 主题外边框。
 * @param showCustomCloseButton 无框模式下是否渲染自绘关闭按钮。
 * @param titleBarInsets JBR 原生系统窗口按钮避让内边距。
 * @param onCloseRequest 关闭回调。
 * @param titleBarBackgroundModifier 标题栏拖拽或命中桥接修饰符。
 * @param content 对话框正文内容。
 */
@Suppress("LongParameterList")
@Composable
internal fun OnyxDialogSurface(
    title: String,
    settings: OnyxSettings,
    palette: OnyxPalette = rememberOnyxPalette(
        themeMode = settings.themeMode,
        themePreset = settings.themePreset,
        themeColors = settings.themeColors,
    ),
    isDark: Boolean = resolveEffectiveDarkTheme(settings.themeMode, isSystemInDarkTheme()),
    windowIcon: Painter = rememberOnyxWindowIconPainter(),
    windowActive: Boolean = true,
    drawOuterBorder: Boolean = false,
    showCustomCloseButton: Boolean = false,
    titleBarInsets: PaddingValues = PaddingValues(0.dp),
    onCloseRequest: () -> Unit = {},
    titleBarBackgroundModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val borderColor = if (windowActive) palette.outline else palette.outlineVariant
    val outerModifier = if (drawOuterBorder) {
        Modifier
            .fillMaxSize()
            .border(1.dp, borderColor, RectangleShape)
            .padding(1.dp)
    } else {
        Modifier.fillMaxSize()
    }

    Column(
        modifier = outerModifier
            .background(palette.appBackground)
            .testTag("onyx-dialog-surface"),
    ) {
        IntUiTheme(isDark = isDark) {
            OnyxDialogTitleBar(
                title = title,
                palette = palette,
                windowIcon = windowIcon,
                windowActive = windowActive,
                showCloseButton = showCustomCloseButton,
                systemInsets = titleBarInsets,
                onCloseRequest = onCloseRequest,
                backgroundModifier = titleBarBackgroundModifier,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.titleBarBorder),
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            OnyxWindowTheme(settings = settings, content = content)
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun OnyxDialogTitleBar(
    title: String,
    palette: OnyxPalette,
    windowIcon: Painter,
    windowActive: Boolean,
    showCloseButton: Boolean,
    systemInsets: PaddingValues,
    onCloseRequest: () -> Unit,
    backgroundModifier: Modifier,
) {
    val headerBg = if (windowActive) palette.titleBarBackground else palette.surfaceVariant
    val titleColor = if (windowActive) palette.foreground else palette.mutedForeground
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(DIALOG_TITLE_BAR_HEIGHT_DP.dp)
            .background(headerBg)
            .testTag("onyx-dialog-title-bar"),
    ) {
        Spacer(modifier = Modifier.fillMaxSize().then(backgroundModifier))
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(systemInsets)
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = windowIcon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showCloseButton) {
                UndecoratedDialogCloseButton(
                    foreground = titleColor,
                    onClick = onCloseRequest,
                )
            }
        }
    }
}

@Composable
private fun UndecoratedDialogCloseButton(
    foreground: Color,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(DIALOG_CLOSE_BUTTON_WIDTH_DP.dp)
            .background(if (hovered) CloseButtonHoverBackground else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .testTag("onyx-dialog-close"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            key = AllIconsKeys.Actions.Close,
            contentDescription = stringResource(Res.string.action_close_window),
            tint = if (hovered) Color.White else foreground,
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
private fun rememberWindowActiveState(window: ComposeDialog): Boolean {
    var active by remember(window) { mutableStateOf(window.isActive) }
    DisposableEffect(window) {
        val listener = object : WindowAdapter() {
            override fun windowActivated(event: WindowEvent?) {
                active = true
            }

            override fun windowDeactivated(event: WindowEvent?) {
                active = false
            }
        }
        window.addWindowListener(listener)
        onDispose {
            window.removeWindowListener(listener)
        }
    }
    return active
}

private fun Modifier.jbrCustomTitleBarPointerBridge(titleBar: JbrCustomDialogTitleBar): Modifier =
    pointerInput(titleBar) {
        val context = currentCoroutineContext()
        awaitPointerEventScope {
            var inUserControl = false
            while (context.isActive) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                event.changes.forEach { change ->
                    if (!change.isConsumed && !inUserControl) {
                        titleBar.forceHitTest(false)
                    } else {
                        if (event.type == PointerEventType.Press) {
                            inUserControl = true
                        }
                        if (event.type == PointerEventType.Release) {
                            inUserControl = false
                        }
                        titleBar.forceHitTest(true)
                    }
                }
            }
        }
    }

private fun Modifier.undecoratedWindowDragGesture(window: Window): Modifier =
    pointerInput(window) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = true)
            val movedByJbr = JbrDialogTitleBarBridge.tryStartMovingWithMouse(window, MouseEvent.BUTTON1)
            if (!movedByJbr) {
                val startPointer = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
                val startWindow = Point(window.location)
                do {
                    val event = awaitPointerEvent()
                    val currentPointer = MouseInfo.getPointerInfo()?.location
                    if (currentPointer != null) {
                        window.setLocation(
                            startWindow.x + (currentPointer.x - startPointer.x),
                            startWindow.y + (currentPointer.y - startPointer.y),
                        )
                    }
                } while (event.changes.any { it.pressed })
            }
        }
    }

private const val DIALOG_TITLE_BAR_HEIGHT_DP = 34f
private const val DIALOG_CLOSE_BUTTON_WIDTH_DP = 40
private const val DARK_CONTROLS_LUMINANCE_THRESHOLD = 0.5f
private const val UI_SCALE_PERCENT_BASE = 100.0
private val CloseButtonHoverBackground = Color(0xFFE81123)
