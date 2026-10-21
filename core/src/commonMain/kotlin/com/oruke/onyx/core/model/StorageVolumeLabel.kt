package com.oruke.onyx.core.model

/** 卷名的统一展示信息；不携带原生路径对象，也不绑定界面语言。 */
data class StorageVolumeLabel(
    /** 有效卷标；操作系统只返回挂载路径时为空，由界面本地化设备类型。 */
    val name: String?,
    /** 无卷标时使用的本地化设备类型。 */
    val kind: StorageDeviceKind,
    /** Windows 卷根的盘符，例如 D:；其他挂载位置为空。 */
    val drive: String?,
)
