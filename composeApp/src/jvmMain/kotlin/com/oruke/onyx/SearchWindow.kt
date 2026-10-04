package com.oruke.onyx

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import com.oruke.onyx.app.component.SearchPanelState
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.AppLocale
import kotlinx.coroutines.flow.StateFlow
import com.oruke.onyx.ui.OnyxTooltipHost
import com.oruke.onyx.ui.SearchPanel
import com.oruke.onyx.ui.SearchPanelActions
import com.oruke.onyx.ui.SearchPanelPresentation
import com.oruke.onyx.ui.theme.OnyxTheme
import com.oruke.onyx.ui.theme.OnyxLocaleEnvironment
import com.oruke.onyx.ui.theme.rememberOnyxAppearance
import java.awt.Dimension
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_search_title
import onyx.composeapp.generated.resources.onyx_logo
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.TitleBar

/** 完整搜索窗口的最小逻辑宽度。 */
private const val SEARCH_WINDOW_MIN_WIDTH = 640

/** 完整搜索窗口的最小逻辑高度，为结果区保留可用空间。 */
private const val SEARCH_WINDOW_MIN_HEIGHT = 420

/** 外观设置百分比换算的基数。 */
private const val SEARCH_WINDOW_SCALE_BASE = 100.0

/**
 * 为所属文件窗口展示独立的非模态完整搜索，显示状态不拥有搜索任务的生命周期。
 *
 * @param state 完整搜索会话的最新状态。
 * @param presentation 当前搜索历史与位置显示能力。
 * @param actions 发送到所属根组件的搜索动作。
 * @param settings 当前应用外观设置。
 * @param resourceLocale 已切换完成的应用级资源语言信号。
 * @return 无返回值。
 */
@Composable
internal fun SearchWindow(
    state: SearchPanelState,
    presentation: SearchPanelPresentation,
    actions: SearchPanelActions,
    settings: OnyxSettings,
    resourceLocale: StateFlow<AppLocale>,
) {
    // 首次打开后保留窗口组合；隐藏再显示不会重新执行列表初始化并覆盖用户滚动位置。
    val windowState = rememberWindowState(width = 900.dp, height = 650.dp)
    val listState = rememberLazyListState()
    if (state.activationRequest == 0L && !state.visible) return
    OnyxLocaleEnvironment(resourceLocale) {
        DecoratedWindow(
            onCloseRequest = actions.onClose,
            visible = state.visible,
            title = stringResource(Res.string.label_search_title),
            icon = painterResource(Res.drawable.onyx_logo),
            state = windowState,
            onPreviewKeyEvent = { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    actions.onClose()
                    true
                } else {
                    false
                }
            },
        ) {
            SideEffect {
                window.name = "onyx-search"
                val scale = settings.uiScale / SEARCH_WINDOW_SCALE_BASE
                window.minimumSize = Dimension(
                    (SEARCH_WINDOW_MIN_WIDTH * scale).toInt(),
                    (SEARCH_WINDOW_MIN_HEIGHT * scale).toInt(),
                )
            }
            LaunchedEffect(state.activationRequest, state.visible) {
                if (!state.visible) return@LaunchedEffect
                windowState.isMinimized = false
                window.toFront()
                window.requestFocus()
            }
            TitleBar { _ ->
                Text(
                    stringResource(Res.string.label_search_title),
                    modifier = Modifier.align(Alignment.Start).padding(start = 12.dp),
                )
            }
            val appearance = rememberOnyxAppearance(
                listRowHeightDp = settings.listRowHeightDp,
                listFontSizeSp = settings.listFontSizeSp,
                zebraStripeEnabled = settings.zebraStripeEnabled,
                fileListColors = settings.fileListColors,
            )
            OnyxTheme(uiScale = settings.uiScale, appearance = appearance) {
                OnyxTooltipHost { SearchPanel(state, presentation, actions, listState) }
            }
        }
    }
}
