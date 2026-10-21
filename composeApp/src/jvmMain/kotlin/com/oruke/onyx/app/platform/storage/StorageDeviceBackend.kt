package com.oruke.onyx.app.platform.storage

import com.oruke.onyx.core.model.StorageDevice
import com.oruke.onyx.core.model.StorageDeviceAction
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** 平台设备适配的阻塞接口，只能由设备服务在 I/O 调度器调用。 */
internal interface StorageDeviceBackend {
    /** @return 不查询目录内容或磁盘容量的设备清单。 */
    fun discover(): List<StorageDevice>

    /**
     * 查询单设备的容量和只读状态，慢设备不得阻塞设备清单。
     *
     * @param device 本次发现的设备快照。
     * @return 具有最新元数据的同身份设备。
     */
    fun metadata(device: StorageDevice): StorageDevice {
        val location = device.locations.firstOrNull() ?: return device
        val store = Files.getFileStore(Path.of(location))
        return device.copy(
            totalBytes = store.totalSpace,
            availableBytes = store.usableSpace,
            readOnly = store.isReadOnly,
        )
    }

    /**
     * 执行已经重新校验能力的设备操作。
     *
     * @param device 当前有效设备。
     * @param action 用户请求的操作。
     * @return 挂载成功后的真实位置；其他操作返回空值。
     */
    fun execute(device: StorageDevice, action: StorageDeviceAction): String?

    /** @return 平台原生设备变更通知；不提供通知时由服务的低频校验覆盖。 */
    fun changes(): Flow<Unit> = emptyFlow()
}
