package com.oruke.onyx.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 文件条目主键按下时的输入快照。
 *
 * @property pressMillis 本次按下事件单调时间戳（毫秒）。
 * @property position 条目局部坐标系下的按下位置。
 * @property wasSingleSelectedBeforePress 按下之前当前条目是否已是活动面板内的唯一选中项。
 * @property additiveOrRange 本次按下是否携带 Ctrl/Meta/Shift 等多选或范围选择修饰键。
 * @property inRenameLabel 按下位置是否精确落在文件名文本标签区域内。
 * @property doubleClickSlopPx 当前屏幕密度下两次点击构成双击的最大位移容差（像素）。
 */
internal data class FileEntryPressRequest(
    val pressMillis: Long,
    val position: Offset,
    val wasSingleSelectedBeforePress: Boolean,
    val additiveOrRange: Boolean,
    val inRenameLabel: Boolean,
    val doubleClickSlopPx: Float,
)

/**
 * 文件条目主键正常松开时的输入快照。
 *
 * @property releaseMillis 本次松开事件单调时间戳（毫秒）。
 * @property inRenameLabel 松开位置是否精确落在文件名文本标签区域内。
 * @property singleClickToOpen 当前是否启用单击直接打开模式。
 * @property canStillRename 松开瞬间条目是否仍满足唯一选中且未处于编辑态等重命名前提。
 */
internal data class FileEntryReleaseRequest(
    val releaseMillis: Long,
    val inRenameLabel: Boolean,
    val singleClickToOpen: Boolean,
    val canStillRename: Boolean,
)

/**
 * 文件条目主键松开后的交互决策结果。
 */
internal enum class FileEntryReleaseOutcome {
    /** 仅保持按下阶段已完成的选择状态，不执行打开或重命名。 */
    NONE,

    /** 单击打开模式下立即打开当前条目。 */
    OPEN_SINGLE_CLICK,

    /** 双击打开当前条目。 */
    OPEN_DOUBLE_CLICK,

    /** 满足慢速点击文件名条件，启动延迟重命名计时。 */
    SCHEDULE_RENAME,
}

private const val DOUBLE_CLICK_COUNT = 2
private const val TRIPLE_CLICK_COUNT = 3

/**
 * 统一管理详情行与画廊项的主键点击、无下限桌面双击识别及慢速点击文件名重命名状态机。
 *
 * 相比移动端基因的 `combinedClickable`（含 40ms 最小双击间隔下限、300ms 超时上限及延迟单击回调），
 * 本控制器直接采用桌面系统双击阈值（无最小间隔下限），并将每次按下状态封装为不可变快照，
 * 同时要求慢速重命名精确命中文件名文本且避开连续点击缓冲期，彻底消除双击误触重命名。
 *
 * @param doubleClickIntervalProvider 提供当前系统双击最大间隔（毫秒）。
 * @param renameDelayMillis 确认慢速点击文件名后进入内联重命名的等待时长（毫秒）。
 */
internal class FileEntryClickController(
    private val doubleClickIntervalProvider: () -> Long = ::resolveSystemDoubleClickIntervalMillis,
    private val renameDelayMillis: Long = FILE_ENTRY_RENAME_DELAY_MILLIS,
) {
    /** 单次按住周期内的不可变手势快照，避免后续快速连击覆写前一次点击的状态。 */
    private data class GestureSnapshot(
        val pressMillis: Long,
        val position: Offset,
        val clickCount: Int,
        val additiveOrRange: Boolean,
        val eligibleForSlowRenameOnPress: Boolean,
    )

    private var renameJob: Job? = null
    private var activeGesture: GestureSnapshot? = null
    private var lastCleanTapPressMillis: Long? = null
    private var lastCleanTapPosition: Offset? = null
    private var consecutiveClickCount: Int = 0
    private var lastAnyPrimaryPressMillis: Long? = null

    /**
     * 记录一次主键按下，立即取消挂起的重命名计时并计算当前连击次数与慢速重命名资格。
     *
     * @param request 按下快照参数。
     */
    fun onPrimaryPress(request: FileEntryPressRequest) {
        cancelRename()
        val doubleClickInterval = doubleClickIntervalProvider()
        val dtFromCleanTap = lastCleanTapPressMillis
            ?.let { (request.pressMillis - it).coerceAtLeast(0L) }
            ?: Long.MAX_VALUE
        val dtFromAnyPress = lastAnyPrimaryPressMillis
            ?.let { (request.pressMillis - it).coerceAtLeast(0L) }
            ?: Long.MAX_VALUE
        val withinDoubleClickDistance = lastCleanTapPosition?.let { previous ->
            val dx = request.position.x - previous.x
            val dy = request.position.y - previous.y
            dx * dx + dy * dy <= request.doubleClickSlopPx * request.doubleClickSlopPx
        } ?: false

        val clickCount = if (
            !request.additiveOrRange &&
            dtFromCleanTap <= doubleClickInterval &&
            withinDoubleClickDistance
        ) {
            consecutiveClickCount + 1
        } else {
            1
        }

        val pastDoubleClickGuard = dtFromAnyPress >= doubleClickInterval + POST_SELECT_RENAME_GUARD_MILLIS
        val eligibleForSlowRename = clickCount == 1 &&
            request.wasSingleSelectedBeforePress &&
            !request.additiveOrRange &&
            request.inRenameLabel &&
            pastDoubleClickGuard

        lastAnyPrimaryPressMillis = request.pressMillis
        activeGesture = GestureSnapshot(
            pressMillis = request.pressMillis,
            position = request.position,
            clickCount = clickCount,
            additiveOrRange = request.additiveOrRange,
            eligibleForSlowRenameOnPress = eligibleForSlowRename,
        )
    }

    /**
     * 在指针转入文件拖拽、框选、右键菜单、展开箭头或手势被消费取消时清理当前点击状态。
     */
    fun onPointerDraggedOrCancelled() {
        cancelRename()
        activeGesture = null
        lastCleanTapPressMillis = null
        lastCleanTapPosition = null
        consecutiveClickCount = 0
    }

    /**
     * 处理主键正常松开并返回后续动作决策。
     *
     * @param request 松开快照参数。
     * @return 本次点击完成后的动作类型。
     */
    fun onPrimaryRelease(request: FileEntryReleaseRequest): FileEntryReleaseOutcome {
        val gesture = activeGesture ?: return FileEntryReleaseOutcome.NONE
        activeGesture = null
        val doubleClickInterval = doubleClickIntervalProvider()
        val holdMillis = (request.releaseMillis - gesture.pressMillis).coerceAtLeast(0L)
        recordOrResetCleanTap(gesture, holdMillis, doubleClickInterval)

        return when {
            gesture.clickCount == DOUBLE_CLICK_COUNT && !gesture.additiveOrRange -> {
                if (request.singleClickToOpen) {
                    FileEntryReleaseOutcome.NONE
                } else {
                    FileEntryReleaseOutcome.OPEN_DOUBLE_CLICK
                }
            }

            gesture.clickCount >= TRIPLE_CLICK_COUNT -> FileEntryReleaseOutcome.NONE

            else -> resolveSingleClickRelease(gesture, request, holdMillis)
        }
    }

    /**
     * 根据本次点击的时长与修饰键维持连击链，或在超时及组合键点击时重置连击链。
     */
    private fun recordOrResetCleanTap(
        gesture: GestureSnapshot,
        holdMillis: Long,
        doubleClickInterval: Long,
    ) {
        if (!gesture.additiveOrRange && holdMillis <= doubleClickInterval) {
            lastCleanTapPressMillis = gesture.pressMillis
            lastCleanTapPosition = gesture.position
            consecutiveClickCount = gesture.clickCount
        } else {
            resetCleanTapChain()
        }
    }

    /**
     * 判定单击松开时的动作：单击直接打开、慢速重命名或仅维持选中。
     */
    private fun resolveSingleClickRelease(
        gesture: GestureSnapshot,
        request: FileEntryReleaseRequest,
        holdMillis: Long,
    ): FileEntryReleaseOutcome = when {
        request.singleClickToOpen && !gesture.additiveOrRange -> FileEntryReleaseOutcome.OPEN_SINGLE_CLICK
        gesture.eligibleForSlowRenameOnPress &&
            holdMillis <= MAX_RENAME_PRESS_HOLD_MILLIS &&
            request.inRenameLabel &&
            request.canStillRename -> FileEntryReleaseOutcome.SCHEDULE_RENAME

        else -> FileEntryReleaseOutcome.NONE
    }

    /**
     * 清空干净点击连击链的状态记录。
     */
    private fun resetCleanTapChain() {
        lastCleanTapPressMillis = null
        lastCleanTapPosition = null
        consecutiveClickCount = 0
    }

    /** 取消尚未触发的慢速重命名定时任务。 */
    fun cancelRename() {
        renameJob?.cancel()
        renameJob = null
    }

    /**
     * 启动慢速重命名延迟任务；等待期间若发生第二次点击、拖拽或失选会被立即取消。
     *
     * @param scope 绑定到条目组合生命周期的协程作用域。
     * @param onBeginRename 延迟到期后的开始重命名回调。
     */
    fun scheduleRename(scope: CoroutineScope, onBeginRename: () -> Unit) {
        cancelRename()
        renameJob = scope.launch {
            delay(renameDelayMillis)
            renameJob = null
            onBeginRename()
        }
    }
}

/**
 * 判断当前指针按下事件是否为主键（鼠标左键或触摸/触控笔输入，排除右键与中键）。
 *
 * @return 主键按下时返回 `true`。
 */
internal fun PointerEvent.isPrimaryPointerPress(): Boolean {
    if (buttons.isSecondaryPressed || buttons.isTertiaryPressed) return false
    if (buttons.isPrimaryPressed) return true
    return changes.any { change -> change.pressed && change.type != PointerType.Mouse }
}
