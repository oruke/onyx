package com.oruke.onyx.app.platform.storage

import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.StorageDevice
import com.oruke.onyx.core.model.StorageDeviceAction
import com.oruke.onyx.core.model.StorageDeviceKind
import com.oruke.onyx.core.model.StorageDeviceState
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** macOS 设备适配，通过系统 diskutil 的结构化 plist 访问卷，不解析终端表格。 */
internal class MacStorageDeviceBackend : StorageDeviceBackend {
    /** 磁盘工具提供的未挂载卷缓存，在原生观察协程更新，不阻塞首帧清单。 */
    private val catalog = AtomicReference<List<MacStorageVolume>>(emptyList())

    /** @return 系统真实卷清单，包含暂未挂载但可识别的文件系统卷。 */
    override fun discover(): List<StorageDevice> = volumes().map { it.device }

    /**
     * 调用原生磁盘服务挂载、卸载或弹出设备，打开与属性操作交给 Finder。
     *
     * @param device 当前设备快照。
     * @param action 已校验支持的操作。
     * @return 挂载后的真实位置，其他操作返回空值。
     */
    override fun execute(device: StorageDevice, action: StorageDeviceAction): String? {
        val volume =
            volumes().firstOrNull { it.device.id == device.id }
                ?: throw LocalizedException(I18nMessage(MessageKey.MSG_DEVICE_UNAVAILABLE))
        when (action) {
            StorageDeviceAction.OPEN_SYSTEM ->
                runStorageCommand(listOf("/usr/bin/open", device.locations.first()))
            StorageDeviceAction.PROPERTIES ->
                runStorageCommand(
                    listOf(
                        "/usr/bin/osascript",
                        "-e",
                        FINDER_INFORMATION_SCRIPT,
                        device.locations.first(),
                    )
                )
            StorageDeviceAction.UNMOUNT if device.kind == StorageDeviceKind.NETWORK ->
                runStorageCommand(listOf("/sbin/umount", device.locations.first()))
            else -> {
                val verb =
                    when (action) {
                        StorageDeviceAction.MOUNT -> "mount"
                        StorageDeviceAction.UNMOUNT -> "unmount"
                        StorageDeviceAction.EJECT -> "eject"
                        else -> error("Unsupported storage action: $action")
                    }
                runStorageCommand(listOf(DISKUTIL, verb, volume.identifier))
            }
        }
        if (action != StorageDeviceAction.MOUNT) return null
        val info =
            readStoragePropertyList(
                runStorageCommand(listOf(DISKUTIL, "info", "-plist", volume.identifier))
            )
        return info["MountPoint"] as? String
            ?: throw LocalizedException(I18nMessage(MessageKey.MSG_DEVICE_UNAVAILABLE))
    }

    /** @return diskutil 提供的系统磁盘变化信号，服务层仍校验丢失事件。 */
    override fun changes() = flow {
        // 原生挂载清单已可立即显示；补充未挂载卷的工具查询独立在后台完成。
        catalog.set(readCatalog())
        emit(Unit)
        MacStorageRunLoop.observe().conflate().collect {
            catalog.set(readCatalog())
            emit(Unit)
        }
    }
        .flowOn(Dispatchers.IO)

    /** @return 根据当前磁盘服务清单解析的卷和操作目标。 */
    private fun volumes(): List<MacStorageVolume> {
        val mounted = MacStorageNative.mountedVolumes()
        val identifiers = mounted.mapTo(hashSetOf()) { it.identifier }
        return mounted +
            catalog.get().filter { volume ->
                volume.identifier !in identifiers &&
                    volume.device.locations.isEmpty() &&
                    Files.exists(Path.of("/dev", volume.identifier))
            }
    }

    /** @return 系统磁盘清单中可识别的文件系统卷，供原生观察的后台补充使用。 */
    private fun readCatalog(): List<MacStorageVolume> =
        parseMacStorageVolumes(
            readStoragePropertyList(runStorageCommand(listOf(DISKUTIL, "list", "-plist")))
        )

    private companion object {
        /** macOS 系统磁盘工具的固定安装位置，不依赖用户 PATH。 */
        const val DISKUTIL = "/usr/sbin/diskutil"
        /** Finder 的固定脚本通过 argv 接收路径，文件名不能成为脚本内容。 */
        const val FINDER_INFORMATION_SCRIPT =
            """on run argv
                tell application "Finder" to open information window of (POSIX file (item 1 of argv) as alias)
                end run"""
    }
}

/** macOS 卷及 diskutil 操作标识，仅在平台适配内保留。 */
internal data class MacStorageVolume(
    /** 不包含原生对象的跨组件快照。 */
    val device: StorageDevice,
    /** 当前启动中的 diskutil 设备标识。 */
    val identifier: String,
)

/**
 * 遍历 diskutil 的磁盘、分区和 APFS 卷结构，容器本身不作为可浏览目录。
 *
 * @param plist 系统完整磁盘清单的根字典。
 * @return 具有稳定卷身份和当前操作标识的文件系统卷。
 */
internal fun parseMacStorageVolumes(plist: Map<String, Any?>): List<MacStorageVolume> {
    val result = mutableListOf<MacStorageVolume>()
    visitMacStorageDictionary(plist, result)
    return result.distinctBy { it.device.id }
}

/**
 * 递归读取系统字典中的文件系统卷，明确识别挂载状态与系统卷操作限制。
 *
 * @param value 当前磁盘或容器字典。
 * @param result 本次解析收集的文件系统卷。
 */
private fun visitMacStorageDictionary(value: Map<*, *>, result: MutableList<MacStorageVolume>) {
    val identifier = value["DeviceIdentifier"] as? String
    val name = value["VolumeName"] as? String
    val location = value["MountPoint"] as? String
    if (identifier != null && (name != null || location != null)) {
        val identity =
            (value["VolumeUUID"] ?: value["DiskUUID"] ?: identifier)
                .toString()
                .lowercase(java.util.Locale.ROOT)
        val locations = listOfNotNull(location?.takeIf(String::isNotBlank))
        val actions =
            if (locations.isEmpty()) setOf(StorageDeviceAction.MOUNT)
            else
                buildSet {
                    add(StorageDeviceAction.OPEN_SYSTEM)
                    add(StorageDeviceAction.PROPERTIES)
                    if (location != "/" && !location.orEmpty().startsWith("/System/")) {
                        add(StorageDeviceAction.UNMOUNT)
                        add(StorageDeviceAction.EJECT)
                    }
                }
        result +=
            MacStorageVolume(
                StorageDevice(
                    "mac-volume:$identity",
                    name ?: identifier,
                    StorageDeviceKind.LOCAL,
                    locations,
                    if (locations.isEmpty()) StorageDeviceState.UNMOUNTED
                    else StorageDeviceState.MOUNTED,
                    actions,
                ),
                identifier,
            )
    }
    value.values.forEach { child ->
        if (child is List<*>)
            child.filterIsInstance<Map<*, *>>().forEach { visitMacStorageDictionary(it, result) }
    }
}
