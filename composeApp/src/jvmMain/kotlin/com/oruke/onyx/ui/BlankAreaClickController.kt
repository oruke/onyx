package com.oruke.onyx.ui

import androidx.compose.ui.geometry.Offset

/**
 * 统一管理文件面板空白区域（含空目录视图与非空目录留白）的主键点击及桌面双击返回上级状态机。
 *
 * 与移动端基因的 `detectTapGestures(onDoubleTap = ...)` 不同，本控制器不消费或延迟首次单击事件，
 * 允许面板在主键按下瞬间立即切换焦点与关闭右键菜单，并在满足系统双击间隔与位移容差的第二次松开时触发返回上级。
 *
 * @param doubleClickIntervalProvider 提供当前桌面双击最大间隔（毫秒）。
 */
internal class BlankAreaClickController(
    private val doubleClickIntervalProvider: () -> Long = ::resolveSystemDoubleClickIntervalMillis,
) {
    /** 当前按住周期内的主键按下单调时间戳（毫秒）。 */
    private var activePressMillis: Long? = null

    /** 当前按住周期内的容器局部按下坐标。 */
    private var activePressPosition: Offset? = null

    /** 最近一次干净单击的主键按下单调时间戳（毫秒）。 */
    private var lastCleanTapPressMillis: Long? = null

    /** 最近一次干净单击的容器局部坐标。 */
    private var lastCleanTapPosition: Offset? = null

    /**
     * 记录一次空白区域主键按下。
     *
     * @param pressMillis 本次按下事件单调时间戳（毫秒）。
     * @param position 容器局部坐标系下的按下位置。
     */
    fun onPrimaryPress(pressMillis: Long, position: Offset) {
        activePressMillis = pressMillis
        activePressPosition = position
    }

    /**
     * 在转入框选拖拽、右键菜单、命中文件条目或手势被消费取消时清空空白点击与连击状态。
     */
    fun reset() {
        activePressMillis = null
        activePressPosition = null
        lastCleanTapPressMillis = null
        lastCleanTapPosition = null
    }

    /**
     * 处理空白区域主键松开，判定本次点击是否构成有效的空白区双击。
     *
     * @param releaseMillis 本次松开事件单调时间戳（毫秒）。
     * @param position 容器局部坐标系下的松开位置。
     * @param dragSlopSquared 单次点击允许的最大按下-松开位移平方（像素²）。
     * @param doubleClickSlopPx 两次点击构成双击的最大位移容差（像素）。
     * @return 构成有效双击时返回 `true`（并自动重置连击链，防止三连击连续返回两级目录）。
     */
    fun onPrimaryRelease(
        releaseMillis: Long,
        position: Offset,
        dragSlopSquared: Float,
        doubleClickSlopPx: Float,
    ): Boolean {
        val pressMillis = activePressMillis ?: return false
        val pressPos = activePressPosition ?: return false
        activePressMillis = null
        activePressPosition = null

        val moveDx = position.x - pressPos.x
        val moveDy = position.y - pressPos.y
        val doubleClickInterval = doubleClickIntervalProvider()
        val holdMillis = (releaseMillis - pressMillis).coerceAtLeast(0L)
        val cancelledByMoveOrHold = moveDx * moveDx + moveDy * moveDy > dragSlopSquared ||
            holdMillis > doubleClickInterval
        if (cancelledByMoveOrHold) {
            lastCleanTapPressMillis = null
            lastCleanTapPosition = null
            return false
        }

        val prevPressMillis = lastCleanTapPressMillis
        val prevPosition = lastCleanTapPosition
        val isDoubleClick = prevPressMillis != null &&
            prevPosition != null &&
            (pressMillis - prevPressMillis).coerceAtLeast(0L) <= doubleClickInterval &&
            isWithinSlop(position, prevPosition, doubleClickSlopPx)

        if (isDoubleClick) {
            lastCleanTapPressMillis = null
            lastCleanTapPosition = null
        } else {
            lastCleanTapPressMillis = pressMillis
            lastCleanTapPosition = position
        }
        return isDoubleClick
    }

    /**
     * 判断两次点击位置是否落在允许的桌面双击位移容差之内。
     */
    private fun isWithinSlop(current: Offset, previous: Offset, slopPx: Float): Boolean {
        val dx = current.x - previous.x
        val dy = current.y - previous.y
        return dx * dx + dy * dy <= slopPx * slopPx
    }
}
