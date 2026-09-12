package com.oruke.onyx.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.oruke.onyx.ui.theme.LocalTooltipController
import com.oruke.onyx.ui.theme.TooltipController
import com.oruke.onyx.ui.theme.TooltipRequest
import com.oruke.onyx.ui.theme.toIntOffset

/**
 * 为独立窗口提供跟随指针的 Onyx 提示浮层，坐标以当前窗口内容区为基准。
 *
 * @param content 需要提示能力的窗口内容。
 * @return 无返回值。
 */
@Composable
internal fun OnyxTooltipHost(content: @Composable () -> Unit) {
    var request by remember { mutableStateOf<TooltipRequest?>(null) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    var windowOrigin by remember { mutableStateOf(IntOffset.Zero) }
    val controller = remember {
        TooltipController(
            show = { request = it },
            hide = { owner -> if (request?.owner === owner) request = null },
        )
    }
    CompositionLocalProvider(LocalTooltipController provides controller) {
        Box(
            Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                contentSize = coordinates.size
                windowOrigin = coordinates.localToWindow(Offset.Zero).toIntOffset()
            },
        ) {
            content()
            request?.let { OnyxTooltipOverlay(it, contentSize, windowOrigin) }
        }
    }
}
