package com.oruke.onyx.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import com.oruke.onyx.core.model.StorageDeviceKind
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.ic_computer
import onyx.composeapp.generated.resources.ic_storage_local
import onyx.composeapp.generated.resources.ic_storage_network
import onyx.composeapp.generated.resources.ic_storage_optical
import onyx.composeapp.generated.resources.ic_storage_removable
import org.jetbrains.compose.resources.painterResource

/**
 * 在既有细线图标风格下区分电脑、磁盘、网络卷和可移动介质，颜色响应全局主题。
 *
 * @param kind 设备类型；为空表示电脑概览入口。
 * @param modifier 由所属公共组件提供的统一尺寸。
 * @param contentDescription 独立操作按钮的无障碍名称，装饰图标保持为空。
 */
@Composable
internal fun StorageDeviceIcon(
    kind: StorageDeviceKind? = null,
    modifier: Modifier = Modifier.size(LocalOnyxAppearance.current.entryIconSize),
    contentDescription: String? = null,
) {
    val resource =
        when (kind) {
            null -> Res.drawable.ic_computer
            StorageDeviceKind.NETWORK -> Res.drawable.ic_storage_network
            StorageDeviceKind.REMOVABLE -> Res.drawable.ic_storage_removable
            StorageDeviceKind.OPTICAL -> Res.drawable.ic_storage_optical
            else -> Res.drawable.ic_storage_local
        }
    Image(
        painterResource(resource),
        contentDescription,
        modifier,
        colorFilter = ColorFilter.tint(LocalOnyxPalette.current.mutedForeground),
    )
}
