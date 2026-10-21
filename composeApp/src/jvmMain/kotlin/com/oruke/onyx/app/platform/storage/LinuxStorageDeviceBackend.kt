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

/** Linux 设备适配：挂载表提供基础可见性，UDisks 工具存在时才提供挂载与卸载能力。 */
internal class LinuxStorageDeviceBackend : StorageDeviceBackend {
    /** @return 合并内核挂载与 udev 文件系统设备的真实清单。 */
    override fun discover(): List<StorageDevice> {
        val mounts =
            Files.readAllLines(Path.of("/proc/self/mountinfo")).mapNotNull(::parseLinuxMountInfo)
        val blocks = fileSystemBlocks()
        val byNumber = blocks.associateBy { it.deviceNumber }
        val opener = storageCommandPath("gio") ?: storageCommandPath("xdg-open")
        val mounted =
            mounts
                .filter(::isVisibleStorageMount)
                .groupBy { Triple(it.source, it.root, it.deviceNumber) }
                .values
                .map { entries ->
                    val first = entries.first()
                    val block = byNumber[first.deviceNumber]
                    StorageDevice(
                        id =
                            block?.id
                                ?: "linux-mount:${first.fileSystem}:${first.source}:${first.root}",
                        name = block?.name ?: first.source,
                        kind =
                            if (first.fileSystem in NETWORK_FILE_SYSTEMS) StorageDeviceKind.NETWORK
                            else block?.kind ?: StorageDeviceKind.LOCAL,
                        locations = entries.map { it.location }.distinct(),
                        state = StorageDeviceState.MOUNTED,
                        actions =
                            buildSet {
                                if (opener != null) add(StorageDeviceAction.OPEN_SYSTEM)
                                if (
                                    block != null &&
                                        entries.none { it.location == "/" } &&
                                        udisks() != null
                                )
                                    add(StorageDeviceAction.UNMOUNT)
                            },
                        readOnly = first.readOnly,
                    )
                }
                .groupBy { it.id }
                .values
                .map { devices ->
                    val locations = devices.flatMap { it.locations }.distinct()
                    devices
                        .first()
                        .copy(
                            locations = locations,
                            actions =
                                devices.flatMap { it.actions }.toSet() -
                                    if ("/" in locations) setOf(StorageDeviceAction.UNMOUNT)
                                    else emptySet(),
                        )
                }
        val mountedIds = mounted.mapTo(hashSetOf()) { it.id }
        return mounted +
            blocks
                .filter { it.id !in mountedIds }
                .map { block ->
                    StorageDevice(
                        block.id,
                        block.name,
                        block.kind,
                        emptyList(),
                        StorageDeviceState.UNMOUNTED,
                        if (udisks() != null) setOf(StorageDeviceAction.MOUNT) else emptySet(),
                    )
                }
    }

    /**
     * 通过 UDisks 执行当前设备的明确操作；挂载位置使用新挂载表查询结果。
     *
     * @param device 最新设备快照。
     * @param action 设备声明的操作。
     * @return 挂载后的实际路径；卸载成功返回空值。
     */
    override fun execute(device: StorageDevice, action: StorageDeviceAction): String? {
        if (action == StorageDeviceAction.OPEN_SYSTEM) {
            val gio = storageCommandPath("gio")
            val arguments =
                if (gio != null) listOf(gio.toString(), "open", device.locations.first())
                else
                    listOf(
                        checkNotNull(storageCommandPath("xdg-open")).toString(),
                        device.locations.first(),
                    )
            runStorageCommand(arguments)
            return null
        }
        val tool =
            udisks()
                ?: throw LocalizedException(I18nMessage(MessageKey.MSG_DEVICE_ACTION_UNAVAILABLE))
        val block =
            fileSystemBlocks().firstOrNull { it.id == device.id }
                ?: throw LocalizedException(I18nMessage(MessageKey.MSG_DEVICE_UNAVAILABLE))
        val verb =
            when (action) {
                StorageDeviceAction.MOUNT -> "mount"
                StorageDeviceAction.UNMOUNT -> "unmount"
                else ->
                    throw LocalizedException(I18nMessage(MessageKey.MSG_DEVICE_ACTION_UNAVAILABLE))
            }
        runStorageCommand(listOf(tool.toString(), verb, "--block-device", block.source))
        return if (action == StorageDeviceAction.MOUNT)
            discover().firstOrNull { it.id == device.id }?.locations?.firstOrNull()
                ?: throw LocalizedException(I18nMessage(MessageKey.MSG_DEVICE_UNAVAILABLE))
        else null
    }

    /** @return UDisks 的设备和挂载通知；服务缺失时仍可使用内核挂载表。 */
    override fun changes() =
        udisks()?.let { storageCommandChanges(listOf(it.toString(), "monitor")) }
            ?: kotlinx.coroutines.flow.emptyFlow()

    /** @return 当前安装的 UDisks 命令工具；结果不持久化，允许运行期间安装服务。 */
    private fun udisks(): Path? = storageCommandPath("udisksctl")

    /** @return udev 已确认包含文件系统的块设备，排除交换分区与原始分区容器。 */
    private fun fileSystemBlocks(): List<LinuxFileSystemBlock> {
        val root = Path.of("/sys/class/block")
        if (!Files.isDirectory(root)) return emptyList()
        return Files.newDirectoryStream(root).use { blocks ->
            blocks.mapNotNull { block ->
                val number = Files.readString(block.resolve("dev")).trim()
                val metadata = Path.of("/run/udev/data/b$number")
                if (!Files.isRegularFile(metadata)) return@mapNotNull null
                val fields =
                    Files.readAllLines(metadata)
                        .filter { it.startsWith("E:") }
                        .mapNotNull { line ->
                            val split = line.indexOf('=')
                            if (split < 0) null
                            else line.substring(2, split) to line.substring(split + 1)
                        }
                        .toMap()
                if (fields["ID_FS_USAGE"] != "filesystem") return@mapNotNull null
                val source = "/dev/${block.fileName}"
                val removableFile =
                    block.resolve("removable").takeIf(Files::isRegularFile)
                        ?: block.toRealPath().parent.resolve("removable")
                val removable =
                    Files.isRegularFile(removableFile) &&
                        Files.readString(removableFile).trim() == "1"
                LinuxFileSystemBlock(
                    id = fields["ID_FS_UUID"]?.let { "linux-volume:$it" } ?: "linux-device:$number",
                    source = source,
                    deviceNumber = number,
                    name =
                        fields["ID_FS_LABEL"]?.takeIf(String::isNotBlank)
                            ?: block.fileName.toString(),
                    kind = if (removable) StorageDeviceKind.REMOVABLE else StorageDeviceKind.LOCAL,
                )
            }
        }
    }

    private companion object {
        /** 网络和 FUSE 网络存储的内核文件系统名称。 */
        val NETWORK_FILE_SYSTEMS =
            setOf("nfs", "nfs4", "cifs", "smb3", "fuse.sshfs", "fuse.rclone", "fuse.davfs")
    }
}

/** udev 文件系统设备记录，仅供 Linux 适配内部将稳定身份映射为操作目标。 */
private data class LinuxFileSystemBlock(
    /** UUID 优先的设备身份。 */
    val id: String,
    /** UDisks 接受的实际块设备入口。 */
    val source: String,
    /** 内核主次设备号，用于匹配 /dev/mapper 等不同路径别名。 */
    val deviceNumber: String,
    /** 用户卷标或内核设备名称。 */
    val name: String,
    /** 设备展示分类。 */
    val kind: StorageDeviceKind,
)

/**
 * 仅展示用户可浏览的存储挂载，根文件系统始终保留，排除内核和运行期虚拟文件系统。
 *
 * @param mount 已解析的挂载记录。
 * @return 应出现在设备概览中时返回 true。
 */
internal fun isVisibleStorageMount(mount: LinuxMountInfo): Boolean =
    mount.location == "/" ||
        (mount.source.startsWith("/dev/") ||
            mount.fileSystem in
                setOf("nfs", "nfs4", "cifs", "smb3", "fuse.sshfs", "fuse.rclone", "fuse.davfs")) &&
            // Btrfs 的正常卷可直接挂载任意子卷，不能因此把已挂载分区误判为未挂载。
            (mount.root == "/" || mount.location == "/home" || mount.fileSystem == "btrfs") &&
            !mount.location.startsWith("/snap/") &&
            (!mount.location.startsWith("/run/") || mount.location.startsWith("/run/media/"))
