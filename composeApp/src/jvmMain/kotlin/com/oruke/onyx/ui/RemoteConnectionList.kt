package com.oruke.onyx.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_new_connection
import onyx.composeapp.generated.resources.label_remote_connection_empty
import onyx.composeapp.generated.resources.label_sidebar_section_connections
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/**
 * 展示可选择、可双击打开的已保存网络位置列表。
 *
 * @param connections 当前已保存的网络位置列表。
 * @param editingConnectionId 当前编辑的连接 ID。
 * @param onNew 新建网络位置回调。
 * @param onEdit 选择并编辑网络位置回调。
 * @param onOpen 打开网络位置回调。
 * @param fontSize 正文字号。
 * @param modifier 布局修饰符。
 */
@Composable
internal fun RemoteConnectionListPane(
    connections: List<RemoteConnectionProfile>,
    editingConnectionId: String?,
    onNew: () -> Unit,
    onEdit: (RemoteConnectionProfile) -> Unit,
    onOpen: (String) -> Unit,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalOnyxPalette.current
    val listState = rememberLazyListState()
    Column(modifier = modifier.background(palette.surfaceVariant.copy(alpha = 0.42f))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Res.string.label_sidebar_section_connections),
                modifier = Modifier.weight(1f),
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                color = palette.foreground,
            )
            Box(
                modifier = Modifier
                    .background(palette.surfaceVariant, RoundedCornerShape(10.dp))
                    .border(1.dp, palette.outlineVariant, RoundedCornerShape(10.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = connections.size.toString(), fontSize = 10.sp, color = palette.mutedForeground)
            }
            DialogTextButton(
                text = stringResource(Res.string.action_new_connection),
                emphasized = true,
                fontSize = 11.sp,
                onClick = onNew,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (connections.isEmpty()) {
                RemoteConnectionEmptyState(modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(start = 6.dp, end = 10.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(items = connections, key = { connection -> connection.id }) { connection ->
                        RemoteConnectionListItem(
                            connection = connection,
                            selected = connection.id == editingConnectionId,
                            onEdit = { onEdit(connection) },
                            onOpen = { onOpen(connection.location) },
                            fontSize = fontSize,
                        )
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

/**
 * 展示网络位置列表的空状态。
 *
 * @param modifier 布局修饰符。
 */
@Composable
private fun RemoteConnectionEmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(20.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(Res.string.label_remote_connection_empty),
            fontSize = 11.sp,
            color = LocalOnyxPalette.current.disabledForeground,
        )
    }
}

/**
 * 展示单个网络位置摘要，单击进入编辑，双击直接打开。
 *
 * @param connection 网络位置配置。
 * @param selected 是否处于选中编辑状态。
 * @param onEdit 选择编辑回调。
 * @param onOpen 双击打开回调。
 * @param fontSize 正文字号。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteConnectionListItem(
    connection: RemoteConnectionProfile,
    selected: Boolean,
    onEdit: () -> Unit,
    onOpen: () -> Unit,
    fontSize: TextUnit,
) {
    val palette = LocalOnyxPalette.current
    val selectedBorder = if (selected) {
        Modifier.border(1.dp, palette.accent.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (selected) palette.selectionBackground else palette.surface.copy(alpha = 0.72f),
                shape = RoundedCornerShape(4.dp),
            )
            .then(selectedBorder)
            .combinedClickable(onClick = onEdit, onDoubleClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RemoteConnectionProtocolBadge(protocol = connection.protocol, selected = selected)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = connection.name,
                fontSize = fontSize,
                fontWeight = FontWeight.Medium,
                color = palette.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = connection.location,
                fontSize = 10.sp,
                color = palette.mutedForeground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 以紧凑徽标展示网络位置协议。
 *
 * @param protocol 网络位置协议。
 * @param selected 是否处于选中状态。
 */
@Composable
private fun RemoteConnectionProtocolBadge(protocol: RemoteConnectionProtocol, selected: Boolean) {
    val palette = LocalOnyxPalette.current
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 26.dp)
            .background(
                if (selected) palette.accent.copy(alpha = 0.15f) else palette.surfaceVariant,
                RoundedCornerShape(4.dp),
            )
            .border(
                1.dp,
                if (selected) palette.accent.copy(alpha = 0.5f) else palette.outlineVariant,
                RoundedCornerShape(4.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = protocol.name,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) palette.accent else palette.mutedForeground,
            maxLines = 1,
        )
    }
}
