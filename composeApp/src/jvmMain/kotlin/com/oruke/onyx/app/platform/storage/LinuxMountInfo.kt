package com.oruke.onyx.app.platform.storage

/** Linux 内核挂载表中的单个文件系统，设备号与挂载位置分别保存。 */
internal data class LinuxMountInfo(
    /** 内核中的主次设备号。 */
    val deviceNumber: String,
    /** 当前挂载暴露的文件系统内部根路径。 */
    val root: String,
    /** 当前进程命名空间中的挂载位置。 */
    val location: String,
    /** 内核报告的文件系统类型。 */
    val fileSystem: String,
    /** 挂载源，可能是块设备、网络位置或虚拟文件系统名称。 */
    val source: String,
    /** 此挂载是否只读。 */
    val readOnly: Boolean,
)

/**
 * 解析内核 mountinfo 格式，按固定分隔符定位可选字段，正确处理空格与反斜杠转义。
 *
 * @param line 单行 /proc/self/mountinfo 内容。
 * @return 有效挂载记录；不完整记录返回空值。
 */
internal fun parseLinuxMountInfo(line: String): LinuxMountInfo? {
    val separator = line.indexOf(MOUNT_INFO_SEPARATOR)
    if (separator < 0) return null
    val mount = line.substring(0, separator).split(' ')
    val fileSystem = line.substring(separator + MOUNT_INFO_SEPARATOR.length).split(' ')
    return if (mount.size < MIN_MOUNT_FIELDS || fileSystem.size < MIN_FILE_SYSTEM_FIELDS) null
    else
        LinuxMountInfo(
            mount[DEVICE_NUMBER_FIELD],
            decodeMountField(mount[ROOT_FIELD]),
            decodeMountField(mount[LOCATION_FIELD]),
            fileSystem[0],
            decodeMountField(fileSystem[1]),
            "ro" in mount[OPTIONS_FIELD].split(','),
        )
}

/**
 * 还原内核挂载字段的八进制转义，不执行 Shell 展开或 URL 解码。
 *
 * @param value 内核编码的挂载字段。
 * @return 实际路径或名称。
 */
internal fun decodeMountField(value: String): String =
    MOUNT_ESCAPE.replace(value) {
        it.groupValues[1].toInt(OCTAL_RADIX).toChar().toString()
    }

/** mountinfo 对特殊字符使用三位八进制转义。 */
private val MOUNT_ESCAPE = Regex("\\\\([0-7]{3})")
/** 内核挂载表路径转义的数值进制。 */
private const val OCTAL_RADIX = 8
/** mountinfo 可选字段与文件系统字段之间的固定分隔符。 */
private const val MOUNT_INFO_SEPARATOR = " - "
/** 挂载部分必须包含到 mount options 的全部字段。 */
private const val MIN_MOUNT_FIELDS = 6
/** 文件系统部分必须包含类型、来源和 super options。 */
private const val MIN_FILE_SYSTEM_FIELDS = 3
/** 主次设备号在挂载部分的位置。 */
private const val DEVICE_NUMBER_FIELD = 2
/** 文件系统内部根路径字段。 */
private const val ROOT_FIELD = 3
/** 当前命名空间中的挂载路径字段。 */
private const val LOCATION_FIELD = 4
/** 挂载选项字段。 */
private const val OPTIONS_FIELD = 5
