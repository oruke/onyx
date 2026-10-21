package com.oruke.onyx.vfs.api

import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.StorageDevice
import com.oruke.onyx.core.model.StorageDeviceAction
import kotlinx.coroutines.flow.Flow

/** 跨桌面平台的设备发现与操作边界，所有原生调用由平台实现承担。 */
interface StorageDeviceService {
    /**
     * 读取最近成功发现的设备，仅供名称展示，禁止在此方法执行 I/O 或推断操作能力。
     * @return 最近设备清单；尚未发现时为空，发现失败仍保留上一份成功结果。
     */
    fun cachedSnapshot(): List<StorageDevice> = emptyList()

    /**
     * 观察完整设备快照，订阅释放时停止对应系统观察资源。
     *
     * @return 设备增删、挂载、离线和元数据更新的结果流。
     */
    fun observe(): Flow<Result<List<StorageDevice>>>

    /**
     * 重新查询当前设备，失败不覆盖调用方保留的上一份有效快照。
     *
     * @return 当前完整设备快照或查询失败。
     */
    suspend fun snapshot(): Result<List<StorageDevice>>

    /**
     * 重新验证设备能力后执行操作，不能把设备身份直接当作文件路径。
     *
     * @param deviceId 平台设备稳定身份。
     * @param action 请求执行的设备操作。
     * @return 挂载成功返回实际浏览位置，其他操作成功返回空值。
     */
    suspend fun execute(deviceId: String, action: StorageDeviceAction): Result<String?>

    /**
     * 根据稳定设备身份取得当前浏览位置，需要时先挂载，挂载失败保留原始错误。
     *
     * @param deviceId 设备的稳定身份。
     * @return 可以交给统一 VFS 的实际目录位置。
     */
    suspend fun openLocation(deviceId: String): Result<String> =
        snapshot()
            .fold(
                onFailure = { Result.failure(it) },
                onSuccess = { devices ->
                    val device = devices.firstOrNull { it.id == deviceId }
                    val mounted = device?.locations?.firstOrNull()
                    when {
                        device == null ->
                            Result.failure(
                                LocalizedException(I18nMessage(MessageKey.MSG_DEVICE_UNAVAILABLE))
                            )
                        mounted != null -> Result.success(mounted)
                        StorageDeviceAction.MOUNT in device.actions ->
                            execute(deviceId, StorageDeviceAction.MOUNT).mapCatching {
                                it
                                    ?: throw LocalizedException(
                                        I18nMessage(MessageKey.MSG_DEVICE_UNAVAILABLE)
                                    )
                            }
                        else ->
                            Result.failure(
                                LocalizedException(
                                    I18nMessage(MessageKey.MSG_DEVICE_ACTION_UNAVAILABLE)
                                )
                            )
                    }
                },
            )
}
