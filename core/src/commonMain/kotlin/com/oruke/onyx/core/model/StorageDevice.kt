package com.oruke.onyx.core.model

/** 应用内设备概览的稳定位置，可保存于标签、收藏和浏览历史。 */
const val COMPUTER_LOCATION = "system://computer"

/** 存储设备的展示分类，与文件或目录类型分开表达。 */
enum class StorageDeviceKind {
    /** 本地固定存储卷，包括平台识别为本地卷的设备。 */
    LOCAL,
    /** 系统识别的可移动或外置存储。 */
    REMOVABLE,
    /** 光驱及其介质入口。 */
    OPTICAL,
    /** 系统映射或挂载的网络存储。 */
    NETWORK,
    /** 系统尚未提供明确分类的设备。 */
    UNKNOWN,
}

/** 设备的当前访问状态；未挂载和读取失败不能解释为空目录。 */
enum class StorageDeviceState {
    /** 已存在真实挂载位置，可交给统一 VFS 尝试访问。 */
    MOUNTED,
    /** 已识别文件系统，但当前没有挂载位置。 */
    UNMOUNTED,
    /** 当前读取失败，保留设备身份与可恢复错误。 */
    UNAVAILABLE,
}

/** 平台针对单个设备明确提供的操作。 */
enum class StorageDeviceAction {
    /** 挂载卷，成功后解析实际浏览位置。 */
    MOUNT,
    /** 请求操作系统卸载当前卷。 */
    UNMOUNT,
    /** 请求操作系统弹出支持此动作的介质。 */
    EJECT,
    /** 打开系统原生属性或信息窗口。 */
    PROPERTIES,
    /** 在系统文件管理器中打开当前挂载位置。 */
    OPEN_SYSTEM,
}

/** 平台设备快照，原生对象和句柄不能进入组件状态或持久化数据。 */
data class StorageDevice(
    /** 平台提供的稳定设备或卷身份，独立于当前盘符和挂载点。 */
    val id: String,
    /** 操作系统提供的卷标或设备名称。 */
    val name: String,
    /** 用于选择图标与本地化类型名称的设备分类。 */
    val kind: StorageDeviceKind,
    /** 当前实际挂载位置；未挂载设备没有可用于文件操作的路径。 */
    val locations: List<String>,
    /** 当前挂载与可访问状态。 */
    val state: StorageDeviceState,
    /** 平台明确支持的操作集合。 */
    val actions: Set<StorageDeviceAction> = emptySet(),
    /** 文件系统是否只读，未知时为空。 */
    val readOnly: Boolean? = null,
    /** 文件系统总容量，尚未完成查询或不可用时为空。 */
    val totalBytes: Long? = null,
    /** 当前用户可用容量，尚未完成查询或不可用时为空。 */
    val availableBytes: Long? = null,
    /** 只影响当前设备的读取错误，其他设备仍然可用。 */
    val failure: I18nMessage? = null,
)
