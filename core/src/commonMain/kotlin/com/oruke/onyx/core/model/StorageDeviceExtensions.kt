package com.oruke.onyx.core.model

/**
 * 提取卷根盘符，保留真实位置字符串供导航和复制使用。
 * @param location 平台提供的挂载位置。
 * @return 规范盘符；不是 Windows 卷根时返回空。
 */
fun storageDriveDesignator(location: String): String? {
    val root = location.replace('\\', '/').trimEnd('/')
    return root.takeIf { it.length == 2 && it[0].isLetter() && it[1] == ':' }
        ?.uppercase()
}

/**
 * 分离卷标和盘符，避免把 C:\\ 同时当成名称和副标题重复显示。
 * @receiver 已发现的设备快照。
 * @return 可以由任意桌面界面本地化的卷名描述。
 */
fun StorageDevice.volumeLabel(): StorageVolumeLabel {
    val drive = locations.firstNotNullOfOrNull(::storageDriveDesignator)
    val label = name.trim().takeIf { candidate ->
        candidate.isNotEmpty() &&
                !candidate.equals(drive, ignoreCase = true) &&
                locations.none { it.equals(candidate, ignoreCase = true) }
    }
    return StorageVolumeLabel(label, kind, drive)
}

/**
 * 只为已挂载且容量有效的设备计算占用比例，未知和失败不能伪装成空盘。
 * @receiver 已发现的设备快照。
 * @return 0 到 1 的占用比例；容量尚不可用时返回空。
 */
fun StorageDevice.usedCapacityFraction(): Float? {
    val total = totalBytes ?: return null
    val available = availableBytes ?: return null
    if (state != StorageDeviceState.MOUNTED || failure != null) return null
    if (total <= 0 || available < 0) return null
    return (1.0 - available.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
}
