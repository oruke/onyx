package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

/** 全局明暗主题模式。 */
@Serializable
enum class OnyxThemeMode {
    /** 跟随操作系统明暗设置。 */
    SYSTEM,

    /** 始终使用浅色主题。 */
    LIGHT,

    /** 始终使用深色主题。 */
    DARK,
}

/** 内置精选主题配色预设。 */
@Serializable
enum class OnyxThemePreset {
    /** Onyx 经典默认配色。 */
    DEFAULT,

    /** 极夜纯黑 OLED 高对比配色。 */
    MIDNIGHT_OLED,

    /** 北欧冰蓝冷调配色。 */
    NORDIC_FROST,

    /** 暗夜魅紫极客配色。 */
    DRACULA_PURPLE,

    /** 暖阳羊皮纸护眼配色。 */
    SOLARIZED_WARM,

    /** 森系翠绿清新配色。 */
    FOREST_EMERALD,
}

/** 全局界面主题颜色覆盖，空值表示使用当前主题预设的默认颜色，按 ARGB 持久化。 */
@Serializable
data class OnyxThemeColors(
    /** 主强调色（按钮高亮、选中指示、焦点边框）。 */
    val accentArgb: Long? = null,
    /** 应用窗口底层主背景色。 */
    val appBackgroundArgb: Long? = null,
    /** 面板、侧边栏与卡片基础表面色。 */
    val surfaceArgb: Long? = null,
    /** 表头与工具栏背景色。 */
    val headerBackgroundArgb: Long? = null,
    /** 主文字前景色。 */
    val foregroundArgb: Long? = null,
    /** 次要说明文字前景色。 */
    val mutedForegroundArgb: Long? = null,
    /** 控件与分栏边框色。 */
    val outlineArgb: Long? = null,
) {
    /** 是否包含任何非空全局颜色覆盖。 */
    fun hasAnyOverride(): Boolean =
        accentArgb != null ||
            appBackgroundArgb != null ||
            surfaceArgb != null ||
            headerBackgroundArgb != null ||
            foregroundArgb != null ||
            mutedForegroundArgb != null ||
            outlineArgb != null
}

/** 文件视图独立颜色设置，空值表示随系统主题，颜色按 ARGB 持久化。 */
@Serializable
data class FileListColors(
    /** 文件区域背景。 */
    val backgroundArgb: Long? = null,
    /** 默认文件名颜色。 */
    val nameArgb: Long? = null,
    /** 活动面板选中背景。 */
    val selectionArgb: Long? = null,
    /** 非活动面板选中背景。 */
    val inactiveSelectionArgb: Long? = null,
    /** 斑马纹背景。 */
    val zebraArgb: Long? = null,
) {
    /** 是否包含任何非空文件列表颜色覆盖。 */
    fun hasAnyOverride(): Boolean =
        backgroundArgb != null ||
            nameArgb != null ||
            selectionArgb != null ||
            inactiveSelectionArgb != null ||
            zebraArgb != null
}

/** 用户保存的自定义命名主题方案。 */
@Serializable
data class CustomThemePreset(
    /** 自定义主题唯一 ID。 */
    val id: String,
    /** 用户输入的自定义主题名称。 */
    val name: String,
    /** 保存时的明暗模式偏好。 */
    val themeMode: OnyxThemeMode = OnyxThemeMode.DARK,
    /** 继承的内置基础配色预设。 */
    val basePreset: OnyxThemePreset = OnyxThemePreset.DEFAULT,
    /** 全局界面颜色配置。 */
    val themeColors: OnyxThemeColors = OnyxThemeColors(),
    /** 文件列表颜色配置。 */
    val fileListColors: FileListColors = FileListColors(),
)

/** 组件计算后的条目着色结果；先配置的规则优先，选择背景始终优先于规则。 */
data class FileLabelStyle(
    /** 规则文件名颜色。 */
    val textColorArgb: Long? = null,
    /** 规则行背景。 */
    val backgroundColorArgb: Long? = null,
)
