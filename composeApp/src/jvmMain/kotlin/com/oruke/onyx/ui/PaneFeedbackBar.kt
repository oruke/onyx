package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import org.jetbrains.jewel.ui.component.Text

/**
 * 统一渲染面板操作失败与目录同步反馈，文字和操作遵循同一外观规范。
 * @param text 已完成本地化的反馈内容。
 * @param modifier 反馈条布局及语义修饰符。
 * @param actions 由调用方提供的关闭、重试等 Jewel 操作控件。
 */
@Composable
internal fun PaneFeedbackBar(
    text: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    val appearance = LocalOnyxAppearance.current
    val palette = LocalOnyxPalette.current
    Row(
        modifier = modifier.fillMaxWidth()
            .background(palette.error.copy(alpha = appearance.feedbackBackgroundAlpha))
            .padding(horizontal = appearance.feedbackHorizontalPadding, vertical = appearance.feedbackVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(appearance.feedbackItemSpacing),
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            fontSize = appearance.feedbackFontSize,
            color = palette.foreground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}
