package com.oruke.onyx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.filesystem.JvmMemoryStatus
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.formatFileSize
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_clear_thumbnail_disk_cache
import onyx.composeapp.generated.resources.label_max_heap_active_summary
import onyx.composeapp.generated.resources.label_max_heap_custom_input
import onyx.composeapp.generated.resources.label_max_heap_dev_hint
import onyx.composeapp.generated.resources.label_max_heap_memory
import onyx.composeapp.generated.resources.label_max_heap_range_error
import onyx.composeapp.generated.resources.label_max_heap_readonly_hint
import onyx.composeapp.generated.resources.label_max_heap_restart_hint
import onyx.composeapp.generated.resources.label_max_heap_unit_mb
import onyx.composeapp.generated.resources.label_max_heap_write_error
import onyx.composeapp.generated.resources.label_section_memory_performance
import onyx.composeapp.generated.resources.label_thumbnail_disk_cache
import onyx.composeapp.generated.resources.label_thumbnail_disk_cache_usage
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/** 供用户快速选择的常用 JVM 最大堆内存预设（单位 MB）。 */
private val HEAP_MEMORY_PRESETS_MB = listOf(512, 768, 1024, 2048, 4096)

/**
 * 绘制内存与性能设置卡片，支持快捷预设与手动输入自定义 JVM `-Xmx` 上限、展示当前运行期堆状态并管理缩略图磁盘缓存。
 *
 * @param draft 当前设置草稿。
 * @param memoryStatus 当前进程运行期堆信息、缩略图磁盘缓存占用与 `Onyx.cfg` 配置状态。
 * @param onDraftChange 设置草稿更新回调。
 * @param bodyFontSize 正文字号。
 * @param labelFontSize 标签字号。
 * @param onClearThumbnailDiskCache 清理缩略图磁盘缓存回调。
 */
@Composable
internal fun MemorySettingsCard(
    draft: OnyxSettings,
    memoryStatus: JvmMemoryStatus,
    onDraftChange: (OnyxSettings) -> Unit,
    bodyFontSize: TextUnit,
    labelFontSize: TextUnit,
    onClearThumbnailDiskCache: () -> Unit = {},
) {
    val palette = LocalOnyxPalette.current
    val feedbackFontSize = LocalOnyxAppearance.current.feedbackFontSize
    var customInputText by remember { mutableStateOf(draft.maxHeapMemoryMb.toString()) }

    // 点击预设或重置默认设置时同步刷新输入框文本，避免外部变更后显示陈旧数值。
    LaunchedEffect(draft.maxHeapMemoryMb) {
        val parsed = customInputText.trim().toIntOrNull()
        if (parsed != draft.maxHeapMemoryMb) {
            customInputText = draft.maxHeapMemoryMb.toString()
        }
    }

    val parsedInput = customInputText.trim().toIntOrNull()
    val inputOutOfRange = parsedInput == null ||
        parsedInput !in OnyxSettings.MIN_MAX_HEAP_MEMORY_MB..OnyxSettings.MAX_MAX_HEAP_MEMORY_MB

    SettingsGroupCard(
        title = stringResource(Res.string.label_section_memory_performance),
        fontSize = labelFontSize,
    ) {
        SettingsSection(stringResource(Res.string.label_max_heap_memory), labelFontSize) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HEAP_MEMORY_PRESETS_MB.forEach { presetMb ->
                        SettingsOption(
                            selected = draft.maxHeapMemoryMb == presetMb,
                            text = "${presetMb} MB",
                            fontSize = labelFontSize,
                            onClick = {
                                customInputText = presetMb.toString()
                                onDraftChange(draft.copy(maxHeapMemoryMb = presetMb))
                            },
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.label_max_heap_custom_input),
                        fontSize = labelFontSize,
                        color = palette.foreground,
                    )
                    OnyxTextInput(
                        value = customInputText,
                        onValueChange = { raw ->
                            val filtered = raw.filter(Char::isDigit).take(MAX_HEAP_INPUT_DIGITS)
                            customInputText = filtered
                            val numeric = filtered.toIntOrNull()
                            if (numeric != null &&
                                numeric in OnyxSettings.MIN_MAX_HEAP_MEMORY_MB..OnyxSettings.MAX_MAX_HEAP_MEMORY_MB
                            ) {
                                onDraftChange(draft.copy(maxHeapMemoryMb = numeric))
                            }
                        },
                        fontSize = bodyFontSize,
                        height = 26.dp,
                        modifier = Modifier.width(104.dp).testTag("settings-xmx-input"),
                    )
                    Text(
                        text = stringResource(
                            Res.string.label_max_heap_unit_mb,
                            OnyxSettings.MIN_MAX_HEAP_MEMORY_MB,
                            OnyxSettings.MAX_MAX_HEAP_MEMORY_MB,
                        ),
                        fontSize = feedbackFontSize,
                        color = palette.mutedForeground,
                    )
                }
                if (inputOutOfRange) {
                    Text(
                        text = stringResource(
                            Res.string.label_max_heap_range_error,
                            OnyxSettings.MIN_MAX_HEAP_MEMORY_MB,
                            OnyxSettings.MAX_MAX_HEAP_MEMORY_MB,
                        ),
                        fontSize = feedbackFontSize,
                        color = palette.errorForeground,
                    )
                }
                MemoryRuntimeStatusHints(
                    memoryStatus = memoryStatus,
                    feedbackFontSize = feedbackFontSize,
                )
            }
        }
        SettingsSection(stringResource(Res.string.label_thumbnail_disk_cache), labelFontSize) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(
                        Res.string.label_thumbnail_disk_cache_usage,
                        formatFileSize(memoryStatus.thumbnailDiskCacheBytes),
                        formatFileSize(memoryStatus.thumbnailDiskCacheLimitBytes),
                    ),
                    fontSize = feedbackFontSize,
                    color = palette.mutedForeground,
                    modifier = Modifier.testTag("settings-thumbnail-disk-cache-usage"),
                )
                DialogTextButton(
                    text = stringResource(Res.string.action_clear_thumbnail_disk_cache),
                    fontSize = bodyFontSize,
                    onClick = onClearThumbnailDiskCache,
                    modifier = Modifier.testTag("settings-clear-thumbnail-disk-cache-button"),
                )
            }
        }
    }
}

/**
 * 渲染当前进程实时堆内存占用、生效方式及启动配置写入错误提示。
 *
 * @param memoryStatus 运行期内存快照。
 * @param feedbackFontSize 辅助提示字号。
 */
@Composable
private fun MemoryRuntimeStatusHints(
    memoryStatus: JvmMemoryStatus,
    feedbackFontSize: TextUnit,
) {
    val palette = LocalOnyxPalette.current
    if (memoryStatus.activeMaxHeapMb > 0) {
        Text(
            text = stringResource(
                Res.string.label_max_heap_active_summary,
                memoryStatus.activeMaxHeapMb,
                memoryStatus.usedHeapMb,
            ),
            fontSize = feedbackFontSize,
            color = palette.mutedForeground,
        )
    }
    val statusHint = when {
        memoryStatus.launcherConfigWritable -> stringResource(Res.string.label_max_heap_restart_hint)
        memoryStatus.launcherConfigPath != null -> stringResource(
            Res.string.label_max_heap_readonly_hint,
            memoryStatus.launcherConfigPath,
        )
        else -> stringResource(Res.string.label_max_heap_dev_hint)
    }
    Text(
        text = statusHint,
        fontSize = feedbackFontSize,
        color = palette.mutedForeground,
    )
    val writeError = memoryStatus.lastWriteError
    if (!writeError.isNullOrBlank()) {
        Text(
            text = stringResource(Res.string.label_max_heap_write_error, writeError),
            fontSize = feedbackFontSize,
            color = palette.errorForeground,
        )
    }
}

/** 自定义堆内存输入框允许的最大数字位数（覆盖 `16384` MB）。 */
private const val MAX_HEAP_INPUT_DIGITS = 5
