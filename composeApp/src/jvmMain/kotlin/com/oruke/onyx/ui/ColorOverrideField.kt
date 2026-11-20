package com.oruke.onyx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.rememberOnyxButtonStyle
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_close
import onyx.composeapp.generated.resources.action_pick_color
import onyx.composeapp.generated.resources.action_reset
import onyx.composeapp.generated.resources.label_color_following_preset
import onyx.composeapp.generated.resources.label_color_picker_alpha
import onyx.composeapp.generated.resources.label_color_picker_hue
import onyx.composeapp.generated.resources.label_color_picker_presets
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.LocalTextStyle
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.defaultButtonStyle
import org.jetbrains.jewel.ui.theme.outlinedButtonStyle
import kotlin.math.roundToInt

/** 精选色板（含实心主题色与常用半透明选中/悬停色）。 */
private val PICKER_PRESET_SWATCHES: List<Long> = listOf(
    0xFF2F6FEBL,
    0xFF6366F1L,
    0xFF8B5CF6L,
    0xFFEC4899L,
    0xFFEF4444L,
    0xFFF59E0BL,
    0xFF10B981L,
    0xFF06B6D4L,
    0xFF181A20L,
    0xFFF8FAFCL,
    0x592F6FEBL,
    0x4D8B5CF6L,
)

/**
 * 渲染单个扁平紧凑颜色配置项：点击按钮即可展开可视化 HSV 颜色选择器，按槽位语义控制是否允许调整 Alpha 透明度。
 *
 * @param label 颜色字段的本地化名称。
 * @param value 当前已设置的 ARGB 值；null 表示跟随主题预设。
 * @param defaultPreviewColor 未设置覆盖时的当前主题默认预览色。
 * @param initialExpanded 是否默认展开可视化取色器（便于预览或测试）。
 * @param allowAlpha 是否允许调整透明度；不透明语义槽位（如全局背景、文字色、边框色）应传 false。
 * @param onChange 颜色变更回调（传入 null 恢复预设默认）。
 */
@Composable
internal fun ColorOverrideField(
    label: String,
    value: Long?,
    defaultPreviewColor: Color? = null,
    initialExpanded: Boolean = false,
    allowAlpha: Boolean = true,
    onChange: (Long?) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    var pickerExpanded by remember { mutableStateOf(initialExpanded) }
    val rawEffectiveColor = value?.let(::Color) ?: defaultPreviewColor ?: palette.surfaceVariant
    val effectiveColor = if (allowAlpha) rawEffectiveColor else rawEffectiveColor.copy(alpha = 1f)
    val hoverSrc = remember { MutableInteractionSource() }
    val clickSrc = remember { MutableInteractionSource() }
    val rowHovered by hoverSrc.collectIsHoveredAsState()
    val rowBg = when {
        pickerExpanded -> palette.selectionBackground.copy(alpha = 0.55f)
        rowHovered -> palette.rowHoverBackground
        else -> palette.surfaceVariant
    }
    val rowBorder = when {
        pickerExpanded -> palette.accent
        rowHovered -> palette.outline
        value != null -> palette.accent.copy(alpha = 0.45f)
        else -> palette.outlineVariant
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .hoverable(hoverSrc)
                .background(rowBg, ColorRowShape)
                .border(1.dp, rowBorder, ColorRowShape)
                .clickable(interactionSource = clickSrc, indication = null) { pickerExpanded = !pickerExpanded }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.foreground,
                )
                Text(
                    text = if (value != null) {
                        formatColorOverrideBadge(value, allowAlpha)
                    } else {
                        stringResource(Res.string.label_color_following_preset)
                    },
                    fontSize = 10.sp,
                    color = if (value != null) palette.accent else palette.mutedForeground,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ColorSwatchTriggerButton(
                    color = effectiveColor,
                    expanded = pickerExpanded,
                    onClick = { pickerExpanded = !pickerExpanded },
                )
                if (value != null) {
                    DialogTextButton(
                        text = stringResource(Res.string.action_reset),
                        fontSize = 11.sp,
                    ) {
                        onChange(null)
                    }
                }
            }
        }

        if (pickerExpanded) {
            OnyxHsvAlphaColorPicker(
                initialColor = effectiveColor,
                allowAlpha = allowAlpha,
                onColorChange = { nextColor ->
                    val normalized = if (allowAlpha) nextColor else nextColor.copy(alpha = 1f)
                    onChange(colorToArgbLong(normalized))
                },
                onReset = {
                    onChange(null)
                    pickerExpanded = false
                },
                onClose = { pickerExpanded = false },
            )
        }
    }
}

/**
 * 使用 Jewel 默认按钮组件（展开时 [DefaultButton]、收起时 [OutlinedButton]）内嵌棋盘格色块预览渲染取色触发按钮。
 */
@Composable
private fun ColorSwatchTriggerButton(
    color: Color,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val label = stringResource(Res.string.action_pick_color) + if (expanded) " ▴" else " ▾"
    if (expanded) {
        val expandedBorder = resolveSwatchBorderColor(
            swatchColor = color,
            containerSurface = palette.accent,
            defaultOutline = Color.White.copy(alpha = 0.78f),
        )
        DefaultButton(
            onClick = onClick,
            style = rememberOnyxButtonStyle(JewelTheme.defaultButtonStyle, compact = true),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CheckerboardColorBox(
                    color = color,
                    modifier = Modifier
                        .width(16.dp)
                        .height(11.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .border(1.dp, expandedBorder, RoundedCornerShape(2.dp)),
                )
                Text(text = label, fontSize = 11.sp, style = LocalTextStyle.current)
            }
        }
    } else {
        val collapsedBorder = resolveSwatchBorderColor(
            swatchColor = color,
            containerSurface = palette.surface,
            defaultOutline = palette.outline,
        )
        OutlinedButton(
            onClick = onClick,
            style = rememberOnyxButtonStyle(JewelTheme.outlinedButtonStyle, compact = true),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CheckerboardColorBox(
                    color = color,
                    modifier = Modifier
                        .width(16.dp)
                        .height(11.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .border(1.dp, collapsedBorder, RoundedCornerShape(2.dp)),
                )
                Text(text = label, fontSize = 11.sp, style = LocalTextStyle.current)
            }
        }
    }
}

/**
 * 完整支持 2D 饱和度/明度画布、360° 色相条、可选 Alpha 透明度棋盘格滑块与精选色板的桌面取色器面板。
 *
 * @param initialColor 取色器初始颜色。
 * @param allowAlpha 是否允许调整透明度；为 false 时隐藏透明度滑块并固定输出 100% 不透明色值。
 * @param onColorChange 颜色变更回调。
 * @param onReset 恢复默认回调。
 * @param onClose 关闭取色器回调。
 */
@Composable
internal fun OnyxHsvAlphaColorPicker(
    initialColor: Color,
    allowAlpha: Boolean = true,
    onColorChange: (Color) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val initialHsv = remember { colorToHsv(initialColor) }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember { mutableFloatStateOf(initialHsv[2]) }
    var alpha by remember(allowAlpha) {
        mutableFloatStateOf(if (allowAlpha) initialColor.alpha.coerceIn(0f, 1f) else 1f)
    }

    val effectiveAlpha = if (allowAlpha) alpha else 1f
    val activeColor = remember(hue, saturation, brightness, effectiveAlpha) {
        hsvToColor(hue, saturation, brightness, effectiveAlpha)
    }
    val pureHueColor = remember(hue) {
        hsvToColor(hue, 1f, 1f, 1f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface, PickerPanelShape)
            .border(1.5.dp, palette.accent.copy(alpha = 0.75f), PickerPanelShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SaturationBrightnessBox(
            pureHueColor = pureHueColor,
            saturation = saturation,
            brightness = brightness,
            onChange = { nextSat, nextVal ->
                saturation = nextSat
                brightness = nextVal
                onColorChange(hsvToColor(hue, nextSat, nextVal, effectiveAlpha))
            },
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(Res.string.label_color_picker_hue),
                    fontSize = 10.sp,
                    color = palette.mutedForeground,
                )
                Text(
                    text = "${hue.roundToInt()}°",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.foreground,
                )
            }
            HueSliderBar(
                hue = hue,
                onHueChange = { nextHue ->
                    hue = nextHue
                    onColorChange(hsvToColor(nextHue, saturation, brightness, effectiveAlpha))
                },
            )
        }

        if (allowAlpha) {
            Column(
                modifier = Modifier.testTag("color-picker-alpha-section"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(Res.string.label_color_picker_alpha),
                        fontSize = 10.sp,
                        color = palette.mutedForeground,
                    )
                    Text(
                        text = "${(alpha * PERCENT_MAX).roundToInt()}%",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.accent,
                    )
                }
                AlphaSliderBar(
                    rgbColor = activeColor.copy(alpha = 1f),
                    alpha = alpha,
                    onAlphaChange = { nextAlpha ->
                        alpha = nextAlpha
                        onColorChange(hsvToColor(hue, saturation, brightness, nextAlpha))
                    },
                )
            }
        }

        PickerQuickSwatches(
            selectedArgb = colorToArgbLong(activeColor),
            allowAlpha = allowAlpha,
            onSelectArgb = { swatchArgb ->
                val rawPicked = Color(swatchArgb)
                val picked = if (allowAlpha) rawPicked else rawPicked.copy(alpha = 1f)
                val hsv = colorToHsv(picked)
                hue = hsv[0]
                saturation = hsv[1]
                brightness = hsv[2]
                alpha = if (allowAlpha) picked.alpha else 1f
                onColorChange(picked)
            },
        )

        PickerFooterBar(
            activeColor = activeColor,
            allowAlpha = allowAlpha,
            onReset = onReset,
            onClose = onClose,
        )
    }
}

/**
 * 2D 饱和度-明度取色画布。
 */
@Composable
private fun SaturationBrightnessBox(
    pureHueColor: Color,
    saturation: Float,
    brightness: Float,
    onChange: (saturation: Float, brightness: Float) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, palette.outline, RoundedCornerShape(6.dp))
            .pointerHoverIcon(PointerIcon.Crosshair)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val s = (offset.x / size.width).coerceIn(0f, 1f)
                    val v = (1f - offset.y / size.height).coerceIn(0f, 1f)
                    onChange(s, v)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val s = (change.position.x / size.width).coerceIn(0f, 1f)
                    val v = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                    onChange(s, v)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = pureHueColor)
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.White, Color.Transparent),
                ),
            )
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                ),
            )
            val cx = saturation.coerceIn(0f, 1f) * size.width
            val cy = (1f - brightness.coerceIn(0f, 1f)) * size.height
            drawCircle(
                color = Color.Black,
                radius = 7.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.dp.toPx()),
            )
            drawCircle(
                color = Color.White,
                radius = 5.5.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

/**
 * 360° 全光谱色相滑块条。
 */
@Composable
private fun HueSliderBar(
    hue: Float,
    onHueChange: (Float) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val hueStops = remember {
        listOf(
            Color.Red,
            Color.Yellow,
            Color.Green,
            Color.Cyan,
            Color.Blue,
            Color.Magenta,
            Color.Red,
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .clip(RoundedCornerShape(9.dp))
            .border(1.dp, palette.outline, RoundedCornerShape(9.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onHueChange((offset.x / size.width * MAX_HUE_DEGREES).coerceIn(0f, MAX_HUE_DEGREES))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    onHueChange((change.position.x / size.width * MAX_HUE_DEGREES).coerceIn(0f, MAX_HUE_DEGREES))
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(brush = Brush.horizontalGradient(hueStops))
            drawSliderThumb(fraction = (hue / MAX_HUE_DEGREES).coerceIn(0f, 1f))
        }
    }
}

/**
 * 带棋盘格透明底纹的 Alpha 透明度滑块条（0% ~ 100%）。
 */
@Composable
private fun AlphaSliderBar(
    rgbColor: Color,
    alpha: Float,
    onAlphaChange: (Float) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .clip(RoundedCornerShape(9.dp))
            .border(1.dp, palette.outline, RoundedCornerShape(9.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onAlphaChange((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    onAlphaChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCheckerboard(squareSizePx = 6.dp.toPx())
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(rgbColor.copy(alpha = 0f), rgbColor.copy(alpha = 1f)),
                ),
            )
            drawSliderThumb(fraction = alpha.coerceIn(0f, 1f))
        }
    }
}

/**
 * 精选快捷色板（按 [allowAlpha] 过滤半透明预设，并为浅色/近背景色块增强边框对比度）。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PickerQuickSwatches(
    selectedArgb: Long,
    allowAlpha: Boolean,
    onSelectArgb: (Long) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val swatches = remember(allowAlpha) {
        if (allowAlpha) {
            PICKER_PRESET_SWATCHES
        } else {
            PICKER_PRESET_SWATCHES.filter { argb -> ((argb ushr 24) and 0xFFL) == 0xFFL }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = stringResource(Res.string.label_color_picker_presets),
            fontSize = 10.sp,
            color = palette.mutedForeground,
        )
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            swatches.forEach { swatchArgb ->
                val hoverSrc = remember { MutableInteractionSource() }
                val clickSrc = remember { MutableInteractionSource() }
                val hovered by hoverSrc.collectIsHoveredAsState()
                val isSelected = selectedArgb == swatchArgb
                val swatchColor = Color(swatchArgb)
                val idleBorder = resolveSwatchBorderColor(
                    swatchColor = swatchColor,
                    containerSurface = palette.surface,
                    defaultOutline = palette.outline,
                )
                CheckerboardColorBox(
                    color = swatchColor,
                    modifier = Modifier
                        .size(22.dp)
                        .hoverable(hoverSrc)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clip(CircleShape)
                        .border(
                            width = if (isSelected || hovered) 2.dp else 1.dp,
                            color = when {
                                isSelected -> palette.accent
                                hovered -> palette.foreground
                                else -> idleBorder
                            },
                            shape = CircleShape,
                        )
                        .clickable(interactionSource = clickSrc, indication = null) { onSelectArgb(swatchArgb) },
                )
            }
        }
    }
}

/**
 * 取色器底部实时棋盘格色块预览、当前颜色数值徽章与动作按钮。
 */
@Composable
private fun PickerFooterBar(
    activeColor: Color,
    allowAlpha: Boolean,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val argb = colorToArgbLong(activeColor)
    val previewBorder = resolveSwatchBorderColor(
        swatchColor = activeColor,
        containerSurface = palette.surface,
        defaultOutline = palette.outline,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CheckerboardColorBox(
                color = activeColor,
                modifier = Modifier
                    .width(42.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, previewBorder, RoundedCornerShape(4.dp)),
            )
            Text(
                text = formatColorOverrideBadge(argb, allowAlpha),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.foreground,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DialogTextButton(
                text = stringResource(Res.string.action_reset),
                fontSize = 11.sp,
                onClick = onReset,
            )
            DialogTextButton(
                text = stringResource(Res.string.action_close),
                emphasized = true,
                fontSize = 11.sp,
                onClick = onClose,
            )
        }
    }
}

/**
 * 渲染带黑白灰棋盘格底纹的颜色预览块，确保带透明度的颜色清晰可见、不发虚。
 */
@Composable
internal fun CheckerboardColorBox(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCheckerboard(squareSizePx = 5.dp.toPx())
            drawRect(color = color)
        }
    }
}

private fun DrawScope.drawCheckerboard(squareSizePx: Float) {
    val step = squareSizePx.coerceAtLeast(2f)
    val cols = (size.width / step).toInt() + 1
    val rows = (size.height / step).toInt() + 1
    val lightCell = Color(0xFFE2E8F0)
    val darkCell = Color(0xFF94A3B8)
    for (col in 0 until cols) {
        for (row in 0 until rows) {
            val isEven = (col + row) % 2 == 0
            drawRect(
                color = if (isEven) lightCell else darkCell,
                topLeft = Offset(col * step, row * step),
                size = Size(step, step),
            )
        }
    }
}

private fun DrawScope.drawSliderThumb(fraction: Float) {
    val thumbWidth = 8.dp.toPx()
    val x = (fraction * (size.width - thumbWidth)).coerceIn(0f, (size.width - thumbWidth).coerceAtLeast(0f))
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.75f),
        topLeft = Offset(x - 1.dp.toPx(), 1.dp.toPx()),
        size = Size(thumbWidth + 2.dp.toPx(), size.height - 2.dp.toPx()),
        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
        style = Stroke(width = 2.dp.toPx()),
    )
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(x, 2.dp.toPx()),
        size = Size(thumbWidth, size.height - 4.dp.toPx()),
        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
    )
}

@Suppress("MagicNumber")
private fun colorToHsv(color: Color): FloatArray {
    val r = (color.red * 255f).roundToInt().coerceIn(0, 255)
    val g = (color.green * 255f).roundToInt().coerceIn(0, 255)
    val b = (color.blue * 255f).roundToInt().coerceIn(0, 255)
    val hsb = java.awt.Color.RGBtoHSB(r, g, b, null)
    return floatArrayOf(hsb[0] * MAX_HUE_DEGREES, hsb[1], hsb[2])
}

@Suppress("MagicNumber")
private fun hsvToColor(hue: Float, saturation: Float, brightness: Float, alpha: Float): Color {
    val rgb = java.awt.Color.HSBtoRGB(
        (hue / MAX_HUE_DEGREES).coerceIn(0f, 1f),
        saturation.coerceIn(0f, 1f),
        brightness.coerceIn(0f, 1f),
    )
    val r = (rgb shr 16) and 0xFF
    val g = (rgb shr 8) and 0xFF
    val b = rgb and 0xFF
    val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
    val argb = ((a.toLong() and 0xFFL) shl 24) or
        ((r.toLong() and 0xFFL) shl 16) or
        ((g.toLong() and 0xFFL) shl 8) or
        (b.toLong() and 0xFFL)
    return Color(argb)
}

@Suppress("MagicNumber")
internal fun colorToArgbLong(color: Color): Long {
    val a = (color.alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255).toLong()
    val r = (color.red.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255).toLong()
    val g = (color.green.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255).toLong()
    val b = (color.blue.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255).toLong()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

@Suppress("MagicNumber")
internal fun resolveSwatchBorderColor(
    swatchColor: Color,
    containerSurface: Color,
    defaultOutline: Color,
): Color {
    val resolvedSurface = if (containerSurface.alpha < 1f) containerSurface.compositeOver(Color.White) else containerSurface
    val resolvedSwatch = swatchColor.compositeOver(resolvedSurface)
    val surfaceLum = resolvedSurface.luminance()
    val swatchLum = resolvedSwatch.luminance()
    val contrastGap = kotlin.math.abs(swatchLum - surfaceLum)
    return when {
        // 浅色底上的浅色/白色色块极易融于背景，增强深灰描边以保持清晰边界。
        surfaceLum > 0.55f && (swatchLum > 0.72f || contrastGap < 0.16f) -> Color(0xFF475569)
        // 深色底上的深黑色块同样容易融于背景，使用中性浅灰描边。
        surfaceLum <= 0.55f && contrastGap < 0.12f -> Color(0xFF94A3B8)
        else -> defaultOutline
    }
}

@Suppress("MagicNumber")
private fun formatColorOverrideBadge(argb: Long, allowAlpha: Boolean): String {
    if (!allowAlpha) {
        val rgb = (argb and 0xFFFFFFL).toString(HEX_RADIX).uppercase().padStart(RGB_DIGITS, '0')
        return "#$rgb"
    }
    return formatArgbWithOpacity(argb)
}

@Suppress("MagicNumber")
private fun formatArgbWithOpacity(argb: Long): String {
    val hex = argb.toString(HEX_RADIX).uppercase().padStart(ARGB_DIGITS, '0')
    val alphaByte = ((argb ushr 24) and 0xFFL).toInt()
    val opacityPercent = ((alphaByte / 255f) * PERCENT_MAX).roundToInt()
    return "#$hex · $opacityPercent%"
}

/**
 * 将用户输入的 RGB 或 ARGB 十六进制转换为持久化值。
 */
internal fun parseColorOverride(input: String): Long? {
    val digits = input.trim().removePrefix("#")
    if (digits.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
    return when (digits.length) {
        RGB_DIGITS -> digits.toLongOrNull(HEX_RADIX)?.let { it or OPAQUE_ALPHA_MASK }
        ARGB_DIGITS -> digits.toLongOrNull(HEX_RADIX)
        else -> null
    }
}

private const val MAX_HUE_DEGREES = 360f
private const val PERCENT_MAX = 100f
private const val RGB_DIGITS = 6
private const val ARGB_DIGITS = 8
private const val HEX_RADIX = 16
private const val OPAQUE_ALPHA_MASK = 0xFF000000L
private val ColorRowShape = RoundedCornerShape(4.dp)
private val PickerPanelShape = RoundedCornerShape(4.dp)
