package com.oruke.onyx.app.component

import com.oruke.onyx.app.filesystem.JvmLocalFileProvider
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class DefaultRootComponent(
    private val scope: CoroutineScope,
    localFileProvider: JvmLocalFileProvider,
) : RootComponent {
    override val primaryPane: PaneComponent = DefaultPaneComponent(
        paneId = PaneId.PRIMARY,
        initialLocation = localFileProvider.defaultLocation(),
        localFileProvider = localFileProvider,
        scope = scope,
    )
    override val secondaryPane: PaneComponent = DefaultPaneComponent(
        paneId = PaneId.SECONDARY,
        initialLocation = localFileProvider.defaultLocation(),
        localFileProvider = localFileProvider,
        scope = scope,
    )

    private val layoutMode = MutableStateFlow(PaneLayoutMode.DUAL_VERTICAL)
    private val activePane = MutableStateFlow(PaneId.PRIMARY)
    private val mutableState = MutableStateFlow(
        RootState(
            layoutMode = layoutMode.value,
            activePane = activePane.value,
            primaryPane = primaryPane.state.value,
            secondaryPane = secondaryPane.state.value,
        )
    )

    override val state: StateFlow<RootState> = mutableState.asStateFlow()

    init {
        scope.launch {
            combine(
                layoutMode,
                activePane,
                primaryPane.state,
                secondaryPane.state,
            ) { currentLayoutMode, currentActivePane, primaryState, secondaryState ->
                RootState(
                    layoutMode = currentLayoutMode,
                    activePane = currentActivePane,
                    primaryPane = primaryState,
                    secondaryPane = secondaryState,
                )
            }.collect { combinedState ->
                mutableState.value = combinedState
            }
        }
    }

    override fun setLayoutMode(mode: PaneLayoutMode) {
        layoutMode.value = mode
    }

    override fun activatePane(paneId: PaneId) {
        activePane.value = paneId
    }

    override fun refreshActivePane() {
        when (activePane.value) {
            PaneId.PRIMARY -> primaryPane.refresh()
            PaneId.SECONDARY -> secondaryPane.refresh()
        }
    }
}
