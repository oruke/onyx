@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package com.oruke.onyx

import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.TextLayoutResult
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.math.ceil
import kotlin.math.floor

/** 原生窗口创建和异步重组的最大等待时间。 */
internal const val TASK_WINDOW_WAIT_MILLIS = 15_000L

/** 原生窗口置顶与重绘后的截图稳定等待时间。 */
private const val NATIVE_PAINT_SETTLE_MILLIS = 300

/**
 * 获取当前显示的生产任务窗口；调用方必须在 AWT UI 线程读取。
 *
 * @return 仅含目标窗口的列表，不包含其他应用或测试窗口。
 */
internal fun visibleTaskWindows(): List<ComposeWindow> {
    return Window.getWindows().filterIsInstance<ComposeWindow>()
        .filter { window -> window.name == "onyx-task-center" && window.isShowing }
}

/**
 * 等待独立任务窗口创建，确保后续检查使用真实原生窗口。
 *
 * @return 当前唯一的任务窗口。
 */
internal fun DesktopComposeUiTest.awaitTaskWindow(): ComposeWindow {
    waitUntil(timeoutMillis = TASK_WINDOW_WAIT_MILLIS) { runOnUiThread { visibleTaskWindows().size == 1 } }
    return runOnUiThread { visibleTaskWindows().single() }
}

/**
 * 通过公开窗口语义树等待目标控件完成重组与布局。
 *
 * @param tag 生产组件暴露的测试标签。
 * @return 无返回值。
 */
internal fun DesktopComposeUiTest.awaitTaskWindowTag(tag: String) {
    waitUntil(timeoutMillis = TASK_WINDOW_WAIT_MILLIS) {
        runOnUiThread { visibleTaskWindows().singleOrNull()?.hasTaskWindowTag(tag) == true }
    }
}

/**
 * 对原生窗口中的实际语义节点执行 Compose 点击动作。
 *
 * 独立 Window 不依赖测试场景注册，通过 Compose 语义工具接口访问实际窗口控件。
 *
 * @param tag 待点击控件的测试标签。
 * @return 无返回值。
 */
internal fun DesktopComposeUiTest.clickTaskWindowTag(tag: String) {
    awaitTaskWindowTag(tag)
    runOnUiThread {
        val node = assertNotNull(visibleTaskWindows().single().taskWindowNodes()
            .firstOrNull { current -> current.config.getOrNull(SemanticsProperties.TestTag) == tag })
        assertTrue(assertNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
    }
    waitForIdle()
}

/**
 * 根据生产本地化说明点击指定任务行内的动作，并在重组后继续断言。
 *
 * @param taskId 待操作的任务标识。
 * @param description 当前语言下动作图标的无障碍说明。
 * @return 无返回值。
 */
internal fun DesktopComposeUiTest.clickTaskWindowAction(taskId: String, description: String) {
    awaitTaskWindowTag("task-row-$taskId")
    waitUntil(timeoutMillis = TASK_WINDOW_WAIT_MILLIS) {
        runOnUiThread {
            visibleTaskWindows().single().taskWindowNodes().any { node ->
                description in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                    generateSequence(node) { current -> current.parent }.any { parent ->
                        parent.config.getOrNull(SemanticsProperties.TestTag) == "task-row-$taskId"
                    }
            }
        }
    }
    runOnUiThread {
        val icon = visibleTaskWindows().single().taskWindowNodes().first { node ->
            description in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                generateSequence(node) { current -> current.parent }.any { parent ->
                    parent.config.getOrNull(SemanticsProperties.TestTag) == "task-row-$taskId"
                }
        }
        // 图标说明与按钮点击语义可能位于不同层，沿实际父节点找到最近的可点击控件。
        val action = generateSequence(icon) { node -> node.parent }
            .mapNotNull { node -> node.config.getOrNull(SemanticsActions.OnClick)?.action }
            .first()
        assertTrue(action.invoke())
    }
    waitForIdle()
}

/**
 * 验证关键操作位于窗口内，且其实际文字布局没有溢出或被父布局裁切。
 *
 * @param tag 待验证控件的测试标签。
 * @return 无返回值。
 */
internal fun DesktopComposeUiTest.assertTaskWindowControlIsVisible(tag: String) {
    awaitTaskWindowTag(tag)
    runOnUiThread {
        val window = visibleTaskWindows().single()
        val node = assertNotNull(window.taskWindowNodes()
            .firstOrNull { current -> current.config.getOrNull(SemanticsProperties.TestTag) == tag })
        val bounds = node.boundsInWindow
        assertTrue(bounds.width > 0f && bounds.height > 0f, "Control is clipped: $tag")
        assertTrue(bounds.left >= 0f && bounds.top >= 0f, "Control starts outside the window: $tag")
        val transform = window.graphicsConfiguration.defaultTransform
        assertTrue(
            bounds.right <= window.width * transform.scaleX && bounds.bottom <= window.height * transform.scaleY,
            "Control exceeds window: $tag",
        )
        val textNodes = window.taskWindowNodes().filter { candidate ->
            candidate.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null &&
                generateSequence(candidate) { current -> current.parent }.any { parent -> parent.id == node.id }
        }
        assertTrue(textNodes.isNotEmpty(), "Control has no measurable text: $tag")
        textNodes.forEach { textNode ->
            val layouts = mutableListOf<TextLayoutResult>()
            val measure = assertNotNull(textNode.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)
            assertTrue(measure(layouts))
            assertTrue(layouts.isNotEmpty(), "Control text has no layout: $tag")
            layouts.forEach { layout ->
                val details = "tag=$tag text=${layout.layoutInput.text.text} " +
                    "widthOverflow=${layout.didOverflowWidth} heightOverflow=${layout.didOverflowHeight} " +
                    "size=${layout.size} paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} " +
                    "constraints=${layout.layoutInput.constraints} density=${layout.layoutInput.density} " +
                    "fontSize=${layout.layoutInput.style.fontSize} lineHeight=${layout.layoutInput.style.lineHeight} " +
                    "bounds=${textNode.boundsInWindow}"
                assertTrue(!layout.hasVisualOverflow, "Control text overflows: $details")
                val textBounds = textNode.boundsInWindow
                assertTrue(textBounds.width + 1f >= layout.size.width, "Control text width is clipped: $details")
                assertTrue(textBounds.height + 1f >= layout.size.height, "Control text height is clipped: $details")
            }
        }
    }
}

/**
 * 保存实际原生窗口的限定区域截图，用于人工核对密度、布局与缩放结果。
 *
 * @param fileName reports/task-center 下的 PNG 文件名。
 * @return 无返回值。
 */
internal fun DesktopComposeUiTest.captureTaskWindow(fileName: String) {
    val window = awaitTaskWindow()
    val wasAlwaysOnTop = runOnUiThread {
        val previous = window.isAlwaysOnTop
        window.isAlwaysOnTop = true
        window.toFront()
        window.renderImmediately()
        previous
    }
    try {
        waitForIdle()
        val robot = Robot()
        // 测试时暂时置顶并等待原生绘制，避免异步合成截到前一帧或其他应用窗口。
        robot.waitForIdle()
        Toolkit.getDefaultToolkit().sync()
        robot.delay(NATIVE_PAINT_SETTLE_MILLIS)
        val bounds = runOnUiThread { window.taskWindowContentBoundsOnScreen() }
        val output = Path.of("build", "reports", "task-center", fileName)
        Files.createDirectories(output.parent)
        assertTrue(ImageIO.write(robot.createScreenCapture(bounds), "png", output.toFile()))
    } finally {
        runOnUiThread { window.isAlwaysOnTop = wasAlwaysOnTop }
    }
}

/**
 * 将任务内容的设备像素范围转换为屏幕坐标，裁掉窗口圆角可能露出的桌面区域。
 *
 * @return 仅覆盖任务窗口内容的截图矩形。
 */
private fun ComposeWindow.taskWindowContentBoundsOnScreen(): Rectangle {
    val node = assertNotNull(taskWindowNodes()
        .firstOrNull { current -> current.config.getOrNull(SemanticsProperties.TestTag) == "task-center" })
    val bounds = node.boundsInWindow
    val transform = graphicsConfiguration.defaultTransform
    val origin = locationOnScreen
    val left = ceil(bounds.left / transform.scaleX).toInt()
    val top = ceil(bounds.top / transform.scaleY).toInt()
    val right = floor(bounds.right / transform.scaleX).toInt()
    val bottom = floor(bounds.bottom / transform.scaleY).toInt()
    // 底部圆角与两侧边框属于原生窗口装饰，不纳入产品内容截图。
    val cornerInset = 8
    return Rectangle(
        origin.x + insets.left + left + cornerInset,
        origin.y + insets.top + top,
        right - left - cornerInset * 2,
        bottom - top - cornerInset,
    )
}

/**
 * 判断真实窗口是否存在目标测试标签，必须在 AWT UI 线程读取。
 *
 * @param tag 待查找的语义测试标签。
 * @return 找到节点时返回 true。
 */
internal fun ComposeWindow.hasTaskWindowTag(tag: String): Boolean {
    return taskWindowNodes().any { node -> node.config.getOrNull(SemanticsProperties.TestTag) == tag }
}

/**
 * 遍历公开的未合并语义树，同时覆盖测试标签和按钮点击语义。
 *
 * @return 窗口内当前已组合的全部语义节点。
 */
@OptIn(ComposeToolingApi::class)
private fun ComposeWindow.taskWindowNodes(): List<SemanticsNode> {
    val pending = ArrayDeque(semanticsOwners.map { owner -> owner.unmergedRootSemanticsNode })
    return buildList {
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            add(node)
            pending.addAll(node.children)
        }
    }
}
