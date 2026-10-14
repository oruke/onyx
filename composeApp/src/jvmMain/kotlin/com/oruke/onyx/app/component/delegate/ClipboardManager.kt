package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.filesystem.ClipboardFileEntryResolver
import com.oruke.onyx.app.filesystem.VfsPathService
import com.oruke.onyx.app.platform.JvmSystemFileClipboardService
import com.oruke.onyx.app.platform.SystemFileClipboardSnapshot
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.FileRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 文件格式相同的剪贴板重写不会触发 FlavorListener，因此定期刷新按钮可用状态。 */
private const val CLIPBOARD_REFRESH_INTERVAL_MS = 500L

/**
 * 管理应用共享的文件剪贴板，当前系统载荷是粘贴动作的唯一依据。
 * @param fileRepository 将外部文件解析为 VFile 的统一仓库。
 * @param pathService 系统文件位置的统一解析入口。
 * @param systemClipboard JVM 系统剪贴板读写边界。
 */
internal class ClipboardManager(
    fileRepository: FileRepository,
    pathService: VfsPathService,
    /** 负责平台格式及 I/O 隔离的剪贴板服务。 */
    private val systemClipboard: JvmSystemFileClipboardService = JvmSystemFileClipboardService(),
) {
    /** 将外部文件路径映射为真实业务条目的服务。 */
    private val entryResolver = ClipboardFileEntryResolver(fileRepository, pathService)

    /** 最近一次明确解析或发布的业务载荷；系统内容变化后立即失效。 */
    private val mutableClipboard = MutableStateFlow<ClipboardPayload?>(null)

    /** 当前已解析的文件载荷，供应用内部观察。 */
    val clipboard = mutableClipboard.asStateFlow()

    /** 系统当前是否具有可粘贴文件或属于当前 JVM 的完整 VFS 载荷。 */
    private val mutableAvailability = MutableStateFlow(false)

    /** 所有窗口共享的粘贴启用状态；外部复制后无需先在 Onyx 中复制。 */
    val availability = mutableAvailability.asStateFlow()

    /** 当前是否允许发起文件粘贴，执行时仍会重新读取系统内容。 */
    val canPaste: Boolean get() = mutableAvailability.value

    /** 串行化发布、读取和清理，防止较早刷新覆盖较新的应用内载荷。 */
    private val accessMutex = Mutex()

    /** 绑定到应用生命周期的状态观察任务。 */
    private var monitorJob: Job? = null

    /**
     * 在应用级主线程作用域中持续观察系统文件载荷，不执行目录遍历。
     * @param scope 应用共享作用域，关闭最后一个窗口后负责取消观察任务。
     * @return 无返回值。
     */
    fun bind(scope: CoroutineScope) {
        check(monitorJob == null) { "Clipboard manager is already bound" }
        monitorJob = scope.launch {
            var readFailed = false
            while (isActive) {
                refresh().onSuccess { readFailed = false }.onFailure { failure ->
                    if (!readFailed) OnyxLogger.warn("ClipboardManager", "系统剪贴板读取失败", failure)
                    readFailed = true
                }
                delay(CLIPBOARD_REFRESH_INTERVAL_MS)
            }
        }
    }

    /**
     * 发布复制条目，只有系统写入成功后才暴露新载荷。
     * @param entries 本次明确选择的复制条目。
     * @return 发布结果，失败由组件显示本地化反馈。
     */
    suspend fun stageCopy(entries: List<VFile>): Result<Unit> = stage(entries, ClipboardOperation.COPY)

    /**
     * 发布剪切条目，使用唯一标识区分随后发生的外部同路径复制。
     * @param entries 本次明确选择的剪切条目。
     * @return 发布结果，失败时不会保留可误执行的内部剪切载荷。
     */
    suspend fun stageCut(entries: List<VFile>): Result<Unit> = stage(entries, ClipboardOperation.CUT)

    /**
     * 刷新当前系统载荷的可用状态，内容相同也不能仅凭格式列表判断是否被重写。
     * @return 当前观察结果，系统读取失败时清除旧载荷并禁用粘贴。
     */
    suspend fun refresh(): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        accessMutex.withLock {
            systemClipboard.read().map { snapshot -> updateAvailability(snapshot) }.onFailure { invalidate() }
        }
    }

    /**
     * 在每次粘贴命令执行时重新读取系统内容，外部文件通过统一仓库恢复为 VFile。
     * @return 当前业务载荷，非文件内容返回 null，读取或解析失败明确返回失败。
     */
    suspend fun consume(): Result<ClipboardPayload?> = withContext(Dispatchers.Main.immediate) {
        accessMutex.withLock {
            systemClipboard.read().mapCatching { snapshot ->
                updateAvailability(snapshot)
                mutableClipboard.value ?: snapshot.locations.takeIf { it.isNotEmpty() }?.let { locations ->
                    ClipboardPayload(
                        if (snapshot.isCut) ClipboardOperation.CUT else ClipboardOperation.COPY,
                        entryResolver.resolve(locations).getOrThrow(),
                        snapshot,
                    ).also { mutableClipboard.value = it }
                }
            }.onFailure {
                invalidate()
                if (it is CancellationException) throw it
            }
        }
    }

    /**
     * 剪切完成后仅清理本次传输读取的载荷，保护期间新复制的文件或文本。
     * @param expected 实际用于已完成传输的业务载荷。
     * @return 确实清理时为 true，新载荷已替换时为 false，系统错误明确返回失败。
     */
    suspend fun clearIfUnchanged(expected: ClipboardPayload): Result<Boolean> =
        withContext(Dispatchers.Main.immediate) {
            accessMutex.withLock {
                systemClipboard.clearIfUnchanged(expected.snapshot).onSuccess { cleared ->
                    if (cleared) invalidate()
                }
            }
        }

    /**
     * 串行发布当前完整业务载荷，纯远端文件也以唯一标识占有系统剪贴板。
     * @param entries 本次用户选择的完整条目。
     * @param operation 明确的复制或剪切操作。
     * @return 发布结果，不在写入失败后回退到旧载荷。
     */
    private suspend fun stage(entries: List<VFile>, operation: ClipboardOperation): Result<Unit> =
        withContext(Dispatchers.Main.immediate) {
            if (entries.isEmpty()) return@withContext Result.success(Unit)
            accessMutex.withLock {
                systemClipboard.write(entries, operation == ClipboardOperation.CUT, UUID.randomUUID().toString())
                    .map { snapshot ->
                        mutableClipboard.value = ClipboardPayload(operation, entries.toList(), snapshot)
                        mutableAvailability.value = true
                    }.onFailure { invalidate() }
            }
        }

    /**
     * 根据系统快照更新可用状态，旧内部条目仅在载荷标识与内容仍一致时保留。
     * @param snapshot 最新读取的系统快照。
     * @return 无返回值。
     */
    private fun updateAvailability(snapshot: SystemFileClipboardSnapshot) {
        if (mutableClipboard.value?.snapshot != snapshot) mutableClipboard.value = null
        mutableAvailability.value = snapshot.locations.isNotEmpty() || mutableClipboard.value != null
    }

    /** 清除旧业务载荷并禁用文件粘贴，避免访问失败后继续执行过期剪切。 */
    private fun invalidate() {
        mutableClipboard.value = null
        mutableAvailability.value = false
    }

    /** 当前系统快照对应的业务文件载荷，清理时与实际传输请求一起保留。 */
    data class ClipboardPayload(
        /** 本次实际读取的复制或剪切操作。 */
        val operation: ClipboardOperation,
        /** 经统一文件服务解析的完整业务条目。 */
        val entries: List<VFile>,
        /** 本载荷读取时的系统快照，用于传输完成后的清理判定。 */
        val snapshot: SystemFileClipboardSnapshot,
    )

    /** 文件剪贴板支持的业务操作，普通无标记文件列表按复制处理。 */
    enum class ClipboardOperation { COPY, CUT }
}
