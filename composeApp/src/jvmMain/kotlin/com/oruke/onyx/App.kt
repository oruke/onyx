package com.oruke.onyx

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.component.PaneComponent
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.rememberRootComponent
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_go_up
import onyx.composeapp.generated.resources.action_layout_dual_horizontal
import onyx.composeapp.generated.resources.action_layout_dual_vertical
import onyx.composeapp.generated.resources.action_layout_single
import onyx.composeapp.generated.resources.action_refresh_active
import onyx.composeapp.generated.resources.app_name
import onyx.composeapp.generated.resources.label_active_pane
import onyx.composeapp.generated.resources.label_directory_badge
import onyx.composeapp.generated.resources.label_empty_directory
import onyx.composeapp.generated.resources.label_error_prefix
import onyx.composeapp.generated.resources.label_file_badge
import onyx.composeapp.generated.resources.label_hint_click_folder
import onyx.composeapp.generated.resources.label_items_prefix
import onyx.composeapp.generated.resources.label_loading_entries
import onyx.composeapp.generated.resources.label_location
import onyx.composeapp.generated.resources.label_mode_details
import onyx.composeapp.generated.resources.label_onyx_bootstrap
import onyx.composeapp.generated.resources.pane_primary
import onyx.composeapp.generated.resources.pane_secondary
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Text

@Composable
@Preview
fun App() {
    val rootComponent = rememberRootComponent()
    App(rootComponent = rootComponent)
}

@Composable
private fun App(
    rootComponent: RootComponent,
) {
    val state by rootComponent.state.collectAsState()

    IntUiTheme(
        isDark = isSystemInDarkTheme(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isSystemInDarkTheme()) Color(0xFF1F2329) else Color(0xFFF5F7FA))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OnyxToolbar(
                rootComponent = rootComponent,
                layoutMode = state.layoutMode,
                activePane = state.activePane,
            )

            when (state.layoutMode) {
                PaneLayoutMode.SINGLE -> {
                    PaneSurface(
                        title = stringResource(Res.string.pane_primary),
                        state = state.primaryPane,
                        active = state.activePane == PaneId.PRIMARY,
                        component = rootComponent.primaryPane,
                        modifier = Modifier.fillMaxSize(),
                        onActivate = { rootComponent.activatePane(PaneId.PRIMARY) },
                    )
                }

                PaneLayoutMode.DUAL_VERTICAL -> {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PaneSurface(
                            title = stringResource(Res.string.pane_primary),
                            state = state.primaryPane,
                            active = state.activePane == PaneId.PRIMARY,
                            component = rootComponent.primaryPane,
                            modifier = Modifier.weight(1f),
                            onActivate = { rootComponent.activatePane(PaneId.PRIMARY) },
                        )
                        PaneSurface(
                            title = stringResource(Res.string.pane_secondary),
                            state = state.secondaryPane,
                            active = state.activePane == PaneId.SECONDARY,
                            component = rootComponent.secondaryPane,
                            modifier = Modifier.weight(1f),
                            onActivate = { rootComponent.activatePane(PaneId.SECONDARY) },
                        )
                    }
                }

                PaneLayoutMode.DUAL_HORIZONTAL -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PaneSurface(
                            title = stringResource(Res.string.pane_primary),
                            state = state.primaryPane,
                            active = state.activePane == PaneId.PRIMARY,
                            component = rootComponent.primaryPane,
                            modifier = Modifier.weight(1f),
                            onActivate = { rootComponent.activatePane(PaneId.PRIMARY) },
                        )
                        PaneSurface(
                            title = stringResource(Res.string.pane_secondary),
                            state = state.secondaryPane,
                            active = state.activePane == PaneId.SECONDARY,
                            component = rootComponent.secondaryPane,
                            modifier = Modifier.weight(1f),
                            onActivate = { rootComponent.activatePane(PaneId.SECONDARY) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnyxToolbar(
    rootComponent: RootComponent,
    layoutMode: PaneLayoutMode,
    activePane: PaneId,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF616B75), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.app_name),
            fontWeight = FontWeight.SemiBold,
        )
        Text(text = stringResource(Res.string.label_onyx_bootstrap))
        Text(
            text = "${stringResource(Res.string.label_active_pane)} ${
                if (activePane == PaneId.PRIMARY) {
                    stringResource(Res.string.pane_primary)
                } else {
                    stringResource(Res.string.pane_secondary)
                }
            }"
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LayoutChip(
                text = stringResource(Res.string.action_layout_single),
                selected = layoutMode == PaneLayoutMode.SINGLE,
                onClick = { rootComponent.setLayoutMode(PaneLayoutMode.SINGLE) },
            )
            LayoutChip(
                text = stringResource(Res.string.action_layout_dual_vertical),
                selected = layoutMode == PaneLayoutMode.DUAL_VERTICAL,
                onClick = { rootComponent.setLayoutMode(PaneLayoutMode.DUAL_VERTICAL) },
            )
            LayoutChip(
                text = stringResource(Res.string.action_layout_dual_horizontal),
                selected = layoutMode == PaneLayoutMode.DUAL_HORIZONTAL,
                onClick = { rootComponent.setLayoutMode(PaneLayoutMode.DUAL_HORIZONTAL) },
            )
            LayoutChip(
                text = stringResource(Res.string.action_refresh_active),
                selected = false,
                onClick = rootComponent::refreshActivePane,
            )
        }
    }
}

@Composable
private fun PaneSurface(
    title: String,
    state: PaneState,
    active: Boolean,
    component: PaneComponent,
    modifier: Modifier = Modifier,
    onActivate: () -> Unit,
) {
    Column(
        modifier = modifier
            .border(
                width = if (active) 2.dp else 1.dp,
                color = if (active) Color(0xFF4D8DFF) else Color(0xFF616B75),
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onActivate)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Medium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LayoutChip(
                    text = stringResource(Res.string.action_go_up),
                    selected = false,
                    onClick = {
                        onActivate()
                        component.goUp()
                    },
                )
                Text(text = stringResource(Res.string.label_mode_details))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF616B75), RoundedCornerShape(6.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = stringResource(Res.string.label_location))
            Text(text = state.location)
        }

        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF616B75)),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(1.dp, Color(0xFF616B75), RoundedCornerShape(6.dp))
                .padding(vertical = 4.dp),
        ) {
            PaneEntriesContent(
                state = state.entriesState,
                onActivate = onActivate,
                onOpenEntry = component::openEntry,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaneEntriesContent(
    state: PaneEntriesState,
    onActivate: () -> Unit,
    onOpenEntry: (VFile) -> Unit,
) {
    when (state) {
        PaneEntriesState.Idle,
        PaneEntriesState.Loading,
            -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(text = stringResource(Res.string.label_loading_entries))
            }
        }

        is PaneEntriesState.Failure -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "${stringResource(Res.string.label_error_prefix)} ${state.reason.orEmpty()}".trim(),
                )
            }
        }

        is PaneEntriesState.Ready -> {
            if (state.entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(text = stringResource(Res.string.label_empty_directory))
                }
                return
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "${stringResource(Res.string.label_items_prefix)} ${state.entries.size}",
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Text(
                    text = stringResource(Res.string.label_hint_click_folder),
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(
                        items = state.entries,
                        key = { entry -> entry.id },
                    ) { entry ->
                        EntryRow(
                            entry = entry,
                            onActivate = onActivate,
                            onOpenEntry = onOpenEntry,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: VFile,
    onActivate: () -> Unit,
    onOpenEntry: (VFile) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                onActivate()
                onOpenEntry(entry)
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = entry.name,
                fontWeight = if (entry.kind == VFileKind.DIRECTORY) FontWeight.Medium else FontWeight.Normal,
            )
            Text(
                text = entry.location,
                color = Color(0xFF9AA4AF),
            )
        }
        Text(
            text = when (entry.kind) {
                VFileKind.DIRECTORY -> stringResource(Res.string.label_directory_badge)
                VFileKind.FILE -> stringResource(Res.string.label_file_badge)
            },
        )
    }
}

@Composable
private fun LayoutChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .border(
                width = 1.dp,
                color = if (selected) Color(0xFF4D8DFF) else Color(0xFF616B75),
                shape = RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text)
    }
}
