package com.oruke.onyx.app.platform.storage

import com.oruke.onyx.core.model.StorageDevice
import com.oruke.onyx.core.model.StorageDeviceAction
import com.oruke.onyx.core.model.StorageDeviceKind
import com.oruke.onyx.core.model.StorageDeviceState
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.mac.CoreFoundation
import com.sun.jna.platform.mac.CoreFoundation.CFBooleanRef
import com.sun.jna.platform.mac.CoreFoundation.CFDictionaryRef
import com.sun.jna.platform.mac.CoreFoundation.CFStringRef
import com.sun.jna.platform.mac.CoreFoundation.CFTypeRef
import com.sun.jna.platform.mac.DiskArbitration
import com.sun.jna.platform.mac.DiskArbitration.DASessionRef
import com.sun.jna.platform.mac.SystemB
import java.io.Closeable
import java.io.IOException
import java.util.Locale

/** macOS 的内核挂载快照与 Disk Arbitration 卷身份读取，不查询文件内容。 */
internal object MacStorageNative {
    /** @return 使用 MNT_NOWAIT 缓存数据快速生成的本地与网络卷清单。 */
    fun mountedVolumes(): List<MacStorageVolume> {
        val system = SystemB.INSTANCE
        val count = system.getfsstat64(null, 0, SystemB.MNT_NOWAIT)
        if (count < 0) throw IOException(Native.getLastError().toString())
        val first = SystemB.Statfs()
        @Suppress("UNCHECKED_CAST")
        val buffer = first.toArray(count + MOUNT_ENUMERATION_HEADROOM) as Array<SystemB.Statfs>
        val loaded = system.getfsstat64(buffer, first.size() * buffer.size, SystemB.MNT_NOWAIT)
        if (loaded < 0) throw IOException(Native.getLastError().toString())
        val arbitration = DiskArbitration.INSTANCE
        val session = checkNotNull(arbitration.DASessionCreate(null))
        try {
            return buffer.take(loaded.coerceAtMost(buffer.size)).mapNotNull { entry ->
                val location = Native.toString(entry.f_mntonname, "UTF-8")
                if (location != "/" && !location.startsWith("/Volumes/")) return@mapNotNull null
                val source = Native.toString(entry.f_mntfromname, "UTF-8")
                val identifier = source.removePrefix("/dev/")
                val fileSystem = Native.toString(entry.f_fstypename, "UTF-8")
                val info =
                    if (source.startsWith("/dev/")) volumeIdentity(session, identifier) else null
                val network = fileSystem in setOf("smbfs", "nfs", "afpfs", "webdav")
                val actions = buildSet {
                    add(StorageDeviceAction.OPEN_SYSTEM)
                    add(StorageDeviceAction.PROPERTIES)
                    if (location != "/") add(StorageDeviceAction.UNMOUNT)
                    if (info?.ejectable == true && location != "/") add(StorageDeviceAction.EJECT)
                }
                val kind =
                    when {
                        network -> StorageDeviceKind.NETWORK
                        info?.removable == true -> StorageDeviceKind.REMOVABLE
                        else -> StorageDeviceKind.LOCAL
                    }
                val identity =
                    info?.uuid
                        ?: if (source.startsWith("/dev/")) identifier
                        else source.substringAfter('@')
                MacStorageVolume(
                    StorageDevice(
                        "mac-volume:$identity",
                        info?.name ?: location.substringAfterLast('/').ifBlank { location },
                        kind,
                        listOf(location),
                        StorageDeviceState.MOUNTED,
                        actions,
                        entry.f_flags and READ_ONLY_FLAG != 0,
                        entry.f_blocks.takeIf { it >= 0 }?.times(entry.f_bsize.toLong()),
                        entry.f_bavail.takeIf { it >= 0 }?.times(entry.f_bsize.toLong()),
                    ),
                    identifier,
                )
            }
        } finally {
            CoreFoundation.INSTANCE.CFRelease(session)
        }
    }

    /**
     * 从原生磁盘缓存读取 UUID、卷标和弹出能力，所有 Copy/Create 引用均在本层释放。
     *
     * @param session 本次枚举使用的原生会话。
     * @param identifier 当前系统中的 BSD 设备名称。
     * @return 系统提供的卷身份；设备不属于 Disk Arbitration 时为空。
     */
    private fun volumeIdentity(session: DASessionRef, identifier: String): MacVolumeIdentity? {
        val api = DiskArbitration.INSTANCE
        val disk = api.DADiskCreateFromBSDName(null, session, identifier) ?: return null
        return Closeable { CoreFoundation.INSTANCE.CFRelease(disk) }
            .use {
                val description = api.DADiskCopyDescription(disk)
                if (description == null) null
                else
                    Closeable { CoreFoundation.INSTANCE.CFRelease(description) }
                        .use {
                            description.readVolumeIdentity()
                        }
            }
    }

    /** @return 当前原生描述中的稳定身份、名称和介质能力，不转移任何引用所有权。 */
    private fun CFDictionaryRef.readVolumeIdentity(): MacVolumeIdentity {
        val uuid =
            value("kDADiskDescriptionVolumeUUIDKey") ?: value("kDADiskDescriptionMediaUUIDKey")
        val name = value("kDADiskDescriptionVolumeNameKey")?.let { CFStringRef(it).stringValue() }
        val removable =
            boolean("kDADiskDescriptionMediaRemovableKey") == true ||
                boolean("kDADiskDescriptionDeviceInternalKey") == false
        return MacVolumeIdentity(
            uuid?.copyUuidText(),
            name,
            removable,
            boolean("kDADiskDescriptionMediaEjectableKey") == true,
        )
    }

    /** @return 复制并规范化后的 UUID 文本，临时字符串引用在返回前释放。 */
    private fun Pointer.copyUuidText(): String? {
        val copied = MacStorageRunLoop.foundation.CFUUIDCreateString(null, this) ?: return null
        return Closeable { CoreFoundation.INSTANCE.CFRelease(CFTypeRef(copied)) }
            .use {
                CFStringRef(copied).stringValue().lowercase(Locale.ROOT)
            }
    }

    /**
     * 使用系统导出的常量查询描述，常量引用归操作系统所有，不能释放。
     *
     * @param symbol Disk Arbitration 导出的键名称。
     * @return 字典中借用的值引用。
     */
    private fun CFDictionaryRef.value(symbol: String): Pointer? {
        val key =
            NativeLibrary.getInstance("DiskArbitration")
                .getGlobalVariableAddress(symbol)
                .getPointer(0)
        return getValue(CFStringRef(key))
    }

    /**
     * 读取可选原生布尔值，缺失与 false 保持区别。
     *
     * @param symbol 操作系统导出的描述键。
     * @return 系统提供的布尔值，未知时为空。
     */
    private fun CFDictionaryRef.boolean(symbol: String): Boolean? =
        value(symbol)?.let {
            CoreFoundation.INSTANCE.CFBooleanGetValue(CFBooleanRef(it)).toInt() != 0
        }

    /** 在两次内核枚举之间允许新增少量挂载，避免数组越界。 */
    private const val MOUNT_ENUMERATION_HEADROOM = 8
    /** Darwin 挂载标志中的只读位。 */
    private const val READ_ONLY_FLAG = 1
}

/** 从 macOS 磁盘服务复制的普通元数据，不包含跨线程使用的原生引用。 */
private data class MacVolumeIdentity(
    /** 卷或介质 UUID，未知时为空。 */
    val uuid: String?,
    /** 原生卷标。 */
    val name: String?,
    /** 当前存储是否属于外置或可移动设备。 */
    val removable: Boolean,
    /** 系统是否允许弹出该介质。 */
    val ejectable: Boolean,
)
