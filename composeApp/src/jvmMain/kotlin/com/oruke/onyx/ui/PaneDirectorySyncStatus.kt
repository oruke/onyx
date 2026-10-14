package com.oruke.onyx.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.ui.theme.resolve
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_retry_task
import onyx.composeapp.generated.resources.label_directory_update_failed
import onyx.composeapp.generated.resources.label_directory_observation_degraded
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Link

/**
 * 保留旧列表时显示持久的同步错误与重试，不占用临时操作反馈通道。
 * @param runtime 当前面板状态和统一事件入口。
 */
@Composable
internal fun PaneDirectorySyncStatus(runtime: PaneSurfaceRuntime) {
    val state = runtime.state.activeTabState
    val failure = state.directoryError
    val observation = state.directoryObservationError
    if (failure == null && observation == null) return
    val text = if (failure != null) stringResource(Res.string.label_directory_update_failed, failure.resolve())
        else stringResource(Res.string.label_directory_observation_degraded, observation?.resolve().orEmpty())
    PaneFeedbackBar(text, Modifier.testTag("pane-directory-feedback-${runtime.state.paneId}")) {
        Link(stringResource(Res.string.action_retry_task), onClick = { runtime.dispatch(PaneIntent.Refresh) },
            modifier = Modifier.testTag("pane-directory-retry-${runtime.state.paneId}"))
    }
}
