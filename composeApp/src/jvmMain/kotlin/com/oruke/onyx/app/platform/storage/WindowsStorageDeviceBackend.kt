package com.oruke.onyx.app.platform.storage

import com.oruke.onyx.core.model.StorageDevice
import com.oruke.onyx.core.model.StorageDeviceAction
import com.oruke.onyx.core.model.StorageDeviceKind
import com.oruke.onyx.core.model.StorageDeviceState
import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Kernel32Util
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinError
import com.sun.jna.ptr.IntByReference
import java.util.Locale

/** Windows 原生卷与逻辑盘发现；卷 GUID 与盘符分离，网络盘无需访问内容即可显示。 */
internal class WindowsStorageDeviceBackend : StorageDeviceBackend {
    /**
     * 合并卷挂载点与映射盘，保留目录挂载卷，避免只识别有盘符的磁盘。
     *
     * @return 不含容量查询的设备列表。
     */
    override fun discover(): List<StorageDevice> {
        val volumes = mountedVolumes()
        val represented = volumes.flatMap { it.locations }.map { it.lowercase(Locale.ROOT) }.toSet()
        val otherDrives =
            Kernel32Util.getLogicalDriveStrings()
                .filter { it.lowercase(Locale.ROOT) !in represented }
                .map { root ->
                    device(
                        networkIdentity(root) ?: "windows-drive:${root.lowercase(Locale.ROOT)}",
                        listOf(root),
                    )
                }
        return (volumes + otherDrives)
            .groupBy { it.id }
            .values
            .map { devices ->
                devices.first().copy(locations = devices.flatMap { it.locations }.distinct())
            }
    }

    /**
     * 只为当前设备查询卷标和容量，错误由上层转换为单设备失败。
     *
     * @param device 已发现设备。
     * @return 保持稳定身份的元数据快照。
     */
    override fun metadata(device: StorageDevice): StorageDevice {
        val root = device.locations.firstOrNull() ?: return device
        val name = CharArray(NAME_BUFFER_SIZE)
        val loaded =
            Kernel32.INSTANCE.GetVolumeInformation(root, name, name.size, null, null, null, null, 0)
        if (!loaded) throw Win32Exception(Kernel32.INSTANCE.GetLastError())
        val label = Native.toString(name).takeIf(String::isNotBlank) ?: device.name
        return super.metadata(device.copy(name = label))
    }

    /**
     * 调用系统属性或打开入口；设备弹出需经过设备管理接口，不能误用文件删除。
     *
     * @param device 当前有效设备。
     * @param action 当前设备声明的操作。
     * @return 这些操作不返回新的挂载位置。
     */
    override fun execute(device: StorageDevice, action: StorageDeviceAction): String? {
        val location = device.locations.first()
        WindowsStorageActions.execute(location, action)
        return null
    }

    /** @return 原生窗口收到的卷和设备变化通知。 */
    override fun changes() = WindowsStorageChanges.observe()

    /** @return 以卷 GUID 标识、至少有一个可见挂载点的卷。 */
    private fun mountedVolumes(): List<StorageDevice> {
        return WindowsVolumeEnumeration().use { enumeration ->
            buildList {
                var volume = enumeration.next()
                while (volume != null) {
                    val paths = volumePaths(volume)
                    if (paths.isNotEmpty()) add(device(volume.lowercase(Locale.ROOT), paths))
                    volume = enumeration.next()
                }
            }
        }
    }

    /**
     * 按 Win32 返回长度分配多字符串缓冲区，支持卷挂载到长目录路径。
     *
     * @param volume 原生卷 GUID 路径。
     * @return 当前卷的全部实际挂载位置。
     */
    private fun volumePaths(volume: String): List<String> {
        val required = IntByReference()
        val first = CharArray(NAME_BUFFER_SIZE)
        val api = Kernel32.INSTANCE
        if (api.GetVolumePathNamesForVolumeName(volume, first, first.size, required))
            return paths(first)
        val error = api.GetLastError()
        if (error != WinError.ERROR_MORE_DATA) throw Win32Exception(error)
        val buffer = CharArray(required.value)
        if (!api.GetVolumePathNamesForVolumeName(volume, buffer, buffer.size, required)) {
            throw Win32Exception(api.GetLastError())
        }
        return paths(buffer)
    }

    /**
     * 将 Win32 MULTI_SZ 转换为普通路径集合。
     *
     * @param buffer 以两个零字符结束的多字符串。
     * @return 非空挂载路径。
     */
    private fun paths(buffer: CharArray): List<String> =
        buffer.concatToString().split('\u0000').filter(String::isNotBlank)

    /**
     * 通过不读取文件内容的原生分类创建首帧快照。
     *
     * @param id 稳定卷身份或当前映射盘身份。
     * @param locations 当前全部挂载点。
     * @return 只提供已经实现的系统操作能力。
     */
    private fun device(id: String, locations: List<String>): StorageDevice {
        val kind =
            when (Kernel32.INSTANCE.GetDriveType(locations.first())) {
                WinBase.DRIVE_REMOVABLE -> StorageDeviceKind.REMOVABLE
                WinBase.DRIVE_CDROM -> StorageDeviceKind.OPTICAL
                WinBase.DRIVE_REMOTE -> StorageDeviceKind.NETWORK
                WinBase.DRIVE_FIXED,
                WinBase.DRIVE_RAMDISK -> StorageDeviceKind.LOCAL
                else -> StorageDeviceKind.UNKNOWN
            }
        return StorageDevice(
            id,
            locations.first(),
            kind,
            locations,
            StorageDeviceState.MOUNTED,
            buildSet {
                add(StorageDeviceAction.OPEN_SYSTEM)
                add(StorageDeviceAction.PROPERTIES)
                if (kind == StorageDeviceKind.OPTICAL) add(StorageDeviceAction.EJECT)
            },
        )
    }

    /**
     * 映射网络盘按系统连接目标标识，同一共享的多个盘符合并为一个设备。
     *
     * @param root 逻辑盘根路径。
     * @return 网络连接身份；不是网络盘或当前连接未注册时为空。
     */
    private fun networkIdentity(root: String): String? {
        if (Kernel32.INSTANCE.GetDriveType(root) != WinBase.DRIVE_REMOTE) return null
        val size = IntByReference(NAME_BUFFER_SIZE)
        var buffer = CharArray(size.value)
        var result =
            WindowsNetworkMappings.instance.WNetGetConnection(root.trimEnd('\\'), buffer, size)
        if (result == WinError.ERROR_MORE_DATA) {
            buffer = CharArray(size.value)
            result =
                WindowsNetworkMappings.instance.WNetGetConnection(root.trimEnd('\\'), buffer, size)
        }
        return if (result == WinError.ERROR_SUCCESS)
            "windows-network:${Native.toString(buffer).lowercase(Locale.ROOT)}"
        else null
    }

    private companion object {
        /** 卷 GUID、卷标与初次 MULTI_SZ 查询的常规缓冲区长度。 */
        const val NAME_BUFFER_SIZE = 512
    }
}
