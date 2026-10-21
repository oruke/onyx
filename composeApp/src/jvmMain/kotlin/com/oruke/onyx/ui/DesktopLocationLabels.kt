package com.oruke.onyx.ui

import androidx.compose.runtime.Composable
import com.oruke.onyx.app.filesystem.isComputerLocation
import com.oruke.onyx.app.platform.DesktopOperatingSystem
import com.oruke.onyx.app.platform.currentDesktopOperatingSystem
import com.oruke.onyx.core.model.StandardUserDirectory
import com.oruke.onyx.core.model.SystemQuickAccessLocation
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_computer
import onyx.composeapp.generated.resources.label_home
import onyx.composeapp.generated.resources.label_system_desktop
import onyx.composeapp.generated.resources.label_system_documents
import onyx.composeapp.generated.resources.label_system_downloads
import onyx.composeapp.generated.resources.label_system_music
import onyx.composeapp.generated.resources.label_system_pictures
import onyx.composeapp.generated.resources.label_system_videos
import onyx.composeapp.generated.resources.label_this_pc
import org.jetbrains.compose.resources.stringResource

/** @return 与宿主桌面习惯一致、随应用语言更新的设备概览名称。 */
@Composable
internal fun desktopComputerLabel(): String =
    stringResource(
        if (currentDesktopOperatingSystem() == DesktopOperatingSystem.WINDOWS)
            Res.string.label_this_pc
        else Res.string.label_computer
    )

/**
 * 将系统位置的稳定标题标识转换为当前语言文案，其他系统名称原样显示。
 *
 * @param label 组件输出的标题或位置名称。
 * @return 当前界面使用的本地化名称。
 */
@Composable
internal fun desktopLocationLabel(label: String): String =
    if (isComputerLocation(label)) desktopComputerLabel() else label

/**
 * 标准用户目录名称跟随应用语言，用户自定义目录继续保留其系统名称。
 *
 * @param place 系统目录或用户书签。
 * @param fallback 系统没有提供名称时使用的路径显示名称。
 * @return 当前界面的目录名称。
 */
@Composable
internal fun systemPlaceLabel(
    place: SystemQuickAccessLocation,
    fallback: String = place.location,
): String {
    if (place.isHome) return stringResource(Res.string.label_home)
    val resource =
        when (place.standardDirectory) {
            StandardUserDirectory.DESKTOP -> Res.string.label_system_desktop
            StandardUserDirectory.DOCUMENTS -> Res.string.label_system_documents
            StandardUserDirectory.DOWNLOADS -> Res.string.label_system_downloads
            StandardUserDirectory.PICTURES -> Res.string.label_system_pictures
            StandardUserDirectory.MUSIC -> Res.string.label_system_music
            StandardUserDirectory.VIDEOS -> Res.string.label_system_videos
            null -> null
        }
    return resource?.let { stringResource(it) } ?: place.displayName ?: fallback
}
