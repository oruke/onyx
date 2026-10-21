package com.oruke.onyx.app.platform.storage

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.platform.DesktopOperatingSystem
import com.oruke.onyx.app.platform.currentDesktopOperatingSystem
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.StorageDevice
import com.oruke.onyx.core.model.StorageDeviceAction
import com.oruke.onyx.core.model.StorageDeviceState
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.vfs.api.StorageDeviceService
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 共享设备发现和原生通知，容量查询使用有界后台线程，慢设备不会延迟首帧或重复堆积查询。
 *
 * @param backend 当前平台设备适配。
 */
internal class JvmStorageDeviceService(
    /** 原生设备发现、通知和操作实现。 */
    private val backend: StorageDeviceBackend = createStorageDeviceBackend()
) : StorageDeviceService, Closeable {
    /** 应用级共享资源作用域，通过 DI 关闭，不依赖某个面板的生命周期。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 容量查询最多同时占用四个后台线程。 */
    private val metadataDispatcher = Dispatchers.IO.limitedParallelism(METADATA_PARALLELISM)
    /** 已在执行或最近完成的查询；未完成原生调用不会因轮询反复启动。 */
    private val metadata = ConcurrentHashMap<String, MetadataRead>()
    /** 设备级操作锁由所有面板共享；锁对象保留以避免移除后产生两个并行锁。 */
    private val operationLocks = ConcurrentHashMap<String, Mutex>()
    /** 用户完成设备操作后立即触发完整清单校验。 */
    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** 仅用于同步名称展示的最近成功清单，不允许界面据此执行过期设备操作。 */
    @Volatile private var cachedDevices: List<StorageDevice> = emptyList()
    /** 双面板和多个窗口共享同一原生观察，在最后一个订阅释放后停止。 */
    private val observations =
        deviceUpdates().shareIn(scope, SharingStarted.WhileSubscribed(0), replay = 1)

    /** @return 当前共享的设备快照变化流。 */
    override fun observe(): Flow<Result<List<StorageDevice>>> = observations

    /** @return 最近成功发现的设备，读取过程不访问文件系统。 */
    override fun cachedSnapshot(): List<StorageDevice> = cachedDevices

    /** @return 在 I/O 线程读取、按身份去重后的轻量设备快照。 */
    override suspend fun snapshot(): Result<List<StorageDevice>> =
        withContext(Dispatchers.IO) {
            storageResult {
                backend
                    .discover()
                    .distinctBy { it.id }
                    .map { device ->
                        val cached =
                            metadata[device.id]?.takeIf {
                                it.locations == device.locations &&
                                    it.result.isCompleted &&
                                    !it.result.isCancelled
                            }
                        val result = cached?.result?.await()
                        if (result == null) device else mergeStorageMetadata(device, result)
                    }
            }.onSuccess { cachedDevices = it }
        }

    /**
     * 重新发现并校验设备，避免用旧菜单操作已经拔出的设备。
     *
     * @param deviceId 稳定设备身份。
     * @param action 请求执行的动作。
     * @return 挂载后的当前浏览位置或空值。
     */
    override suspend fun execute(deviceId: String, action: StorageDeviceAction): Result<String?> =
        performOperation(deviceId, action)

    /**
     * 按当前设备身份打开，和挂载、卸载共用互斥边界，避免跨面板重复操作。
     *
     * @param deviceId 稳定设备身份。
     * @return 当前真实挂载位置或显式失败。
     */
    override suspend fun openLocation(deviceId: String): Result<String> =
        performOperation(deviceId, null).mapCatching {
            it ?: throw LocalizedException(I18nMessage(MessageKey.MSG_DEVICE_UNAVAILABLE))
        }

    /**
     * 重新发现设备后执行原子命令；忙碌时直接失败，不能排队执行过时的卸载或弹出。
     *
     * @param deviceId 稳定设备身份。
     * @param action 原生动作；为空表示打开已挂载位置或挂载后打开。
     * @return 打开或挂载后的当前浏览位置；其他动作返回空值。
     */
    private suspend fun performOperation(
        deviceId: String,
        action: StorageDeviceAction?,
    ): Result<String?> =
        withContext(Dispatchers.IO) {
            val lock = operationLocks.computeIfAbsent(deviceId) { Mutex() }
            if (!lock.tryLock()) {
                return@withContext Result.failure(
                    LocalizedException(I18nMessage(MessageKey.MSG_DEVICE_BUSY))
                )
            }
            try {
                storageResult {
                    val device =
                        backend.discover().firstOrNull { it.id == deviceId }
                            ?: throw LocalizedException(
                                I18nMessage(MessageKey.MSG_DEVICE_UNAVAILABLE)
                            )
                    if (action == null && device.locations.isNotEmpty()) {
                        device.locations.first()
                    } else {
                        val requested = action ?: StorageDeviceAction.MOUNT
                        if (requested !in device.actions)
                            throw LocalizedException(
                                I18nMessage(MessageKey.MSG_DEVICE_ACTION_UNAVAILABLE)
                            )
                        backend.execute(device, requested).also {
                            metadata.remove(deviceId)
                            refreshes.tryEmit(Unit)
                        }
                    }
                }
            } finally {
                lock.unlock()
            }
        }

    /** 停止共享通知、容量查询与平台资源，重复关闭不会影响其他应用状态。 */
    override fun close() {
        scope.cancel()
    }

    /** @return 先发布轻量列表，再逐个补全元数据的共享更新流。 */
    private fun deviceUpdates(): Flow<Result<List<StorageDevice>>> = channelFlow {
        val wakeups = Channel<Unit>(Channel.CONFLATED)
        val native =
            backend.changes().catch { failure ->
                if (failure is CancellationException) throw failure
                OnyxLogger.warn("StorageDevices", "设备原生通知不可用，继续进行有界校验", failure)
            }
        val periodic = flow {
            while (currentCoroutineContext().isActive) {
                emit(Unit)
                delay(DEVICE_RESCAN_MILLIS)
            }
        }
        launch { merge(native, periodic, refreshes).conflate().collect { wakeups.trySend(Unit) } }
        for (ignored in wakeups) {
            val found = snapshot()
            send(found)
            val devices = found.getOrNull() ?: continue
            val activeIds = devices.mapTo(hashSetOf()) { it.id }
            metadata.entries.removeIf { it.key !in activeIds && it.value.result.isCompleted }
            val current = devices.associateByTo(linkedMapOf()) { it.id }
            val publishing = Mutex()
            // 每个查询独立等待，结果只合并到本次扫描，旧设备不会在拔出后重新出现。
            kotlinx.coroutines.coroutineScope {
                devices
                    .filter { it.locations.isNotEmpty() }
                    .forEach { device ->
                        launch {
                            val read =
                                metadata.compute(device.id) { _, old ->
                                    if (old != null && old.reusableFor(device.locations)) old
                                    else
                                        MetadataRead(
                                            System.nanoTime(),
                                            device.locations,
                                            scope.async(metadataDispatcher) {
                                                storageResult { backend.metadata(device) }
                                            },
                                        )
                                }!!
                            val result =
                                withTimeoutOrNull(METADATA_WAIT_MILLIS) { read.result.await() }
                            val enriched =
                                if (read.locations != device.locations) device
                                else
                                    result?.let { mergeStorageMetadata(device, it) }
                                        ?: device.copy(
                                            failure =
                                                I18nMessage(MessageKey.MSG_DEVICE_METADATA_TIMEOUT)
                                        )
                            // 元数据协程共享合并结果；串行发布避免同时写入列表和丢失其他设备的更新。
                            publishing.withLock {
                                current[device.id] = enriched
                                send(Result.success(current.values.toList()))
                            }
                        }
                    }
            }
        }
    }

    /** 元数据任务与创建时间，缓存仅用于减轻设备查询，不持久化到用户设置。 */
    private data class MetadataRead(
        /** 原生读取开始时的单调时钟值。 */
        val started: Long,
        /** 查询开始时的挂载点，挂载位置变化后不能复用旧路径的结果。 */
        val locations: List<String>,
        /** 即使界面等待超时也保留的底层读取任务。 */
        val result: Deferred<Result<StorageDevice>>,
    ) {
        /**
         * 在途查询继续合并；已完成查询只在原位置和有效期内复用。
         *
         * @param currentLocations 当前发现的挂载位置。
         * @return 可以复用当前元数据任务时返回 true。
         */
        fun reusableFor(currentLocations: List<String>): Boolean {
            val fresh = System.nanoTime() - started < METADATA_CACHE_NANOS
            return !result.isCompleted || locations == currentLocations && fresh
        }
    }

    private companion object {
        /** 丢失原生通知或缺少系统服务时的完整清单校验间隔。 */
        const val DEVICE_RESCAN_MILLIS = 5_000L
        /** 单设备查询的界面等待预算，底层慢调用继续合并复用。 */
        const val METADATA_WAIT_MILLIS = 800L
        /** 容量与卷标缓存的有效时间。 */
        const val METADATA_CACHE_NANOS = 30_000_000_000L
        /** 允许同时执行的原生元数据查询数量。 */
        const val METADATA_PARALLELISM = 4
    }
}

/**
 * 只合并元数据字段，挂载路径、操作能力和新发现的设备状态始终以当前清单为准。
 *
 * @param device 刚刚发现的当前设备。
 * @param metadata 单设备查询结果。
 * @return 保持当前路径与能力的设备快照。
 */
internal fun mergeStorageMetadata(
    device: StorageDevice,
    metadata: Result<StorageDevice>,
): StorageDevice =
    metadata.fold(
        onSuccess = {
            device.copy(
                name = it.name,
                totalBytes = it.totalBytes,
                availableBytes = it.availableBytes,
                readOnly = it.readOnly,
                failure = it.failure,
            )
        },
        onFailure = {
            device.copy(state = StorageDeviceState.UNAVAILABLE, failure = it.toI18nMessage())
        },
    )

/**
 * 在平台边界保留取消语义，将其他失败交给显式错误状态处理。
 *
 * @param action 同步平台操作。
 * @return 操作值或原始失败。
 */
@Suppress("TooGenericExceptionCaught") // 平台边界统一承接原生、文件与解析失败，取消继续上抛，其他失败显式返回。
internal inline fun <T> storageResult(action: () -> T): Result<T> =
    try {
        Result.success(action())
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        Result.failure(failure)
    }

/** @return 依宿主系统选择的设备实现，原生库只在对应平台加载。 */
internal fun createStorageDeviceBackend(): StorageDeviceBackend =
    when (currentDesktopOperatingSystem()) {
        DesktopOperatingSystem.WINDOWS -> WindowsStorageDeviceBackend()
        DesktopOperatingSystem.LINUX -> LinuxStorageDeviceBackend()
        DesktopOperatingSystem.MAC_OS -> MacStorageDeviceBackend()
        DesktopOperatingSystem.OTHER -> error("Unsupported desktop operating system")
    }
