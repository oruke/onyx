package com.oruke.onyx.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oruke.onyx.ui.theme.LocalOnyxPalette

/**
 * 渲染受窗口可用高度约束的菜单容器，高缩放和长系统菜单通过滚动访问全部项目。
 * 主菜单与级联子菜单共用此容器，短菜单保持内容高度。
 *
 * @param content 菜单项及分隔线的渲染内容。
 */
@Composable
internal fun ContextMenuPanel(content: @Composable ColumnScope.() -> Unit) {
    val scrollState = rememberScrollState()
    val palette = LocalOnyxPalette.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier.width(220.dp)
            .border(1.dp, palette.outlineVariant, shape)
            .background(palette.floatingSurface, shape),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)
                .padding(top = 4.dp, bottom = 4.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
            content = content,
        )
        // 滚动条只跟随内容测得的面板尺寸，不能把短菜单撑到整个窗口高度。
        Box(Modifier.matchParentSize().padding(vertical = 4.dp)) {
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
            )
        }
    }
}
