package com.oruke.onyx

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_layout_dual_horizontal
import onyx.composeapp.generated.resources.action_layout_dual_vertical
import onyx.composeapp.generated.resources.action_layout_single
import onyx.composeapp.generated.resources.app_name
import onyx.composeapp.generated.resources.label_bootstrap_status
import onyx.composeapp.generated.resources.label_mode_details
import onyx.composeapp.generated.resources.label_onyx_bootstrap
import onyx.composeapp.generated.resources.label_placeholder_address
import onyx.composeapp.generated.resources.label_status_ready
import onyx.composeapp.generated.resources.label_template_replaced
import onyx.composeapp.generated.resources.pane_primary
import onyx.composeapp.generated.resources.pane_secondary
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Text

private enum class PaneLayoutMode {
    SINGLE,
    DUAL_VERTICAL,
    DUAL_HORIZONTAL,
}

private enum class PaneSlot {
    PRIMARY,
    SECONDARY,
}

private data class PaneChromeState(
    val title: String,
    val location: String,
    val active: Boolean,
)

@Composable
@Preview
fun App() {
    var layoutMode by remember { mutableStateOf(PaneLayoutMode.DUAL_VERTICAL) }
    var activePane by remember { mutableStateOf(PaneSlot.PRIMARY) }

    val primaryPane = PaneChromeState(
        title = stringResource(Res.string.pane_primary),
        location = stringResource(Res.string.label_placeholder_address),
        active = activePane == PaneSlot.PRIMARY,
    )
    val secondaryPane = PaneChromeState(
        title = stringResource(Res.string.pane_secondary),
        location = stringResource(Res.string.label_placeholder_address),
        active = activePane == PaneSlot.SECONDARY,
    )

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
                layoutMode = layoutMode,
                onModeSelected = { layoutMode = it },
            )

            when (layoutMode) {
                PaneLayoutMode.SINGLE -> {
                    PaneSurface(
                        state = primaryPane,
                        modifier = Modifier
                            .fillMaxSize(),
                        onActivate = { activePane = PaneSlot.PRIMARY },
                    )
                }

                PaneLayoutMode.DUAL_VERTICAL -> {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PaneSurface(
                            state = primaryPane,
                            modifier = Modifier.weight(1f),
                            onActivate = { activePane = PaneSlot.PRIMARY },
                        )
                        PaneSurface(
                            state = secondaryPane,
                            modifier = Modifier.weight(1f),
                            onActivate = { activePane = PaneSlot.SECONDARY },
                        )
                    }
                }

                PaneLayoutMode.DUAL_HORIZONTAL -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PaneSurface(
                            state = primaryPane,
                            modifier = Modifier.weight(1f),
                            onActivate = { activePane = PaneSlot.PRIMARY },
                        )
                        PaneSurface(
                            state = secondaryPane,
                            modifier = Modifier.weight(1f),
                            onActivate = { activePane = PaneSlot.SECONDARY },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnyxToolbar(
    layoutMode: PaneLayoutMode,
    onModeSelected: (PaneLayoutMode) -> Unit,
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LayoutChip(
                text = stringResource(Res.string.action_layout_single),
                selected = layoutMode == PaneLayoutMode.SINGLE,
                onClick = { onModeSelected(PaneLayoutMode.SINGLE) },
            )
            LayoutChip(
                text = stringResource(Res.string.action_layout_dual_vertical),
                selected = layoutMode == PaneLayoutMode.DUAL_VERTICAL,
                onClick = { onModeSelected(PaneLayoutMode.DUAL_VERTICAL) },
            )
            LayoutChip(
                text = stringResource(Res.string.action_layout_dual_horizontal),
                selected = layoutMode == PaneLayoutMode.DUAL_HORIZONTAL,
                onClick = { onModeSelected(PaneLayoutMode.DUAL_HORIZONTAL) },
            )
        }

        Text(text = stringResource(Res.string.label_template_replaced))
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

@Composable
private fun PaneSurface(
    state: PaneChromeState,
    modifier: Modifier = Modifier,
    onActivate: () -> Unit,
) {
    Column(
        modifier = modifier
            .border(
                width = if (state.active) 2.dp else 1.dp,
                color = if (state.active) Color(0xFF4D8DFF) else Color(0xFF616B75),
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
                text = state.title,
                fontWeight = FontWeight.Medium,
            )
            Text(text = stringResource(Res.string.label_mode_details))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF616B75), RoundedCornerShape(6.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = stringResource(Res.string.label_bootstrap_status))
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
                .padding(12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(text = stringResource(Res.string.label_status_ready))
        }
    }
}
