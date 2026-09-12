@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package com.oruke.onyx

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 原生窗口和后台搜索状态变化的统一等待上限。 */
internal const val SEARCH_WINDOW_WAIT_MILLIS = 5_000L

/**
 * 在 AWT 线程列出本应用当前可见的独立搜索窗口。
 *
 * @return 仅包含固定窗口标识匹配的生产窗口。
 */
internal fun visibleSearchWindows(): List<ComposeWindow> = Window.getWindows().filterIsInstance<ComposeWindow>()
    .filter { window -> window.name == "onyx-search" && window.isShowing }

/**
 * 等待生产窗口创建并确认唯一性。
 *
 * @return 当前唯一的搜索窗口。
 */
internal fun DesktopComposeUiTest.awaitSearchWindow(): ComposeWindow {
    waitUntil(timeoutMillis = SEARCH_WINDOW_WAIT_MILLIS) { runOnUiThread { visibleSearchWindows().size == 1 } }
    return runOnUiThread { visibleSearchWindows().single() }
}

/**
 * 等待独立窗口中的真实语义节点布局完成。
 *
 * @param tag 生产控件测试标签。
 * @return 无返回值。
 */
internal fun DesktopComposeUiTest.awaitSearchWindowTag(tag: String) {
    waitUntil(timeoutMillis = SEARCH_WINDOW_WAIT_MILLIS) {
        runOnUiThread { visibleSearchWindows().singleOrNull()?.searchWindowNode(tag) != null }
    }
}

/**
 * 使用公开的 Compose 语义动作点击实际窗口控件。
 *
 * @param tag 生产控件测试标签。
 * @return 无返回值。
 */
internal fun DesktopComposeUiTest.clickSearchWindowTag(tag: String) {
    awaitSearchWindowTag(tag)
    val window = awaitSearchWindow()
    runOnUiThread {
        window.toFront()
        if (!window.isFocused) window.requestFocus()
    }
    waitUntil(timeoutMillis = SEARCH_WINDOW_WAIT_MILLIS) {
        runOnUiThread { window.isFocused && window.focusOwner != null }
    }
    // 独立 Window 不属于测试框架自身的 scene；必须等待真实控件解除禁用，不能只等待标签存在。
    waitUntil(timeoutMillis = SEARCH_WINDOW_WAIT_MILLIS) {
        runOnUiThread {
            val node = visibleSearchWindows().singleOrNull()?.searchWindowNode(tag)
            node != null && !node.config.contains(SemanticsProperties.Disabled) &&
                node.config.getOrNull(SemanticsActions.OnClick)?.action != null
        }
    }
    val previousToggleState = runOnUiThread {
        val node = assertNotNull(visibleSearchWindows().single().searchWindowNode(tag))
        val toggle = node.config.getOrNull(SemanticsProperties.ToggleableState)
        assertTrue(assertNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
        toggle
    }
    waitForIdle()
    runOnUiThread { visibleSearchWindows().singleOrNull()?.renderImmediately() }
    if (previousToggleState != null) {
        waitUntil(timeoutMillis = SEARCH_WINDOW_WAIT_MILLIS) {
            runOnUiThread {
                visibleSearchWindows().singleOrNull()?.searchWindowNode(tag)
                    ?.config?.getOrNull(SemanticsProperties.ToggleableState)?.let { current ->
                        current != previousToggleState
                    } == true
            }
        }
    }
}

/**
 * 通过真实文本输入框的编辑动作更新查询，触发生产组件状态回写。
 *
 * @param query 本次需要执行的搜索文本。
 * @return 无返回值。
 */
internal fun DesktopComposeUiTest.enterSearchWindowQuery(query: String) {
    awaitSearchWindowTag("search-query")
    runOnUiThread {
        val node = assertNotNull(visibleSearchWindows().single().searchWindowNode("search-query"))
        val edit = assertNotNull(node.config.getOrNull(SemanticsActions.SetText)?.action)
        assertTrue(edit(AnnotatedString(query)))
    }
    waitForIdle()
    waitUntil(timeoutMillis = SEARCH_WINDOW_WAIT_MILLIS) {
        runOnUiThread {
            visibleSearchWindows().singleOrNull()?.searchWindowNode("search-query")
                ?.config?.getOrNull(SemanticsProperties.EditableText)?.text == query
        }
    }
}

/**
 * 向搜索窗口的实际焦点控件发送 Enter 事件，验证生产键盘路由。
 *
 * 只向已确认属于搜索窗口的原生组件定向派发，避免键盘事件进入用户的其他应用。
 *
 * @param locate 是否附带 Ctrl 修饰以执行“在文件夹中定位”。
 * @param focusedTag 预期已经获得 Compose 焦点的控件；默认验证单击结果后的列表焦点链。
 * @return 无返回值。
 */
internal fun DesktopComposeUiTest.pressSearchWindowEnter(
    locate: Boolean = false,
    focusedTag: String = "search-results",
) {
    val window = awaitSearchWindow()
    runOnUiThread {
        window.toFront()
        if (!window.isFocused) window.requestFocus()
        window.renderImmediately()
    }
    try {
        waitUntil(timeoutMillis = SEARCH_WINDOW_WAIT_MILLIS) {
            runOnUiThread {
                // 焦点可能属于 LazyColumn 的内部节点；沿祖先确认路由仍位于指定控件内部。
                window.isFocused && window.focusOwner != null && window.searchWindowFocusIsInside(focusedTag)
            }
        }
    } catch (failure: ComposeTimeoutException) {
        val focusDescription = runOnUiThread {
            window.searchWindowNodes().filter { node -> node.config.getOrNull(SemanticsProperties.Focused) != null }
                .joinToString { node ->
                    val tags = generateSequence(node) { current -> current.parent }
                        .mapNotNull { current -> current.config.getOrNull(SemanticsProperties.TestTag) }.toList()
                    "focused=${node.config.getOrNull(SemanticsProperties.Focused)}, tags=$tags"
                }
        }
        throw AssertionError("Expected focus inside $focusedTag; $focusDescription", failure)
    }
    runOnUiThread {
        val owner = assertNotNull(window.focusOwner)
        val modifiers = if (locate) KeyEvent.CTRL_DOWN_MASK else 0
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        listOf(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED).forEach { id ->
            focusManager.redispatchEvent(owner, KeyEvent(owner, id, System.currentTimeMillis(), modifiers, KeyEvent.VK_ENTER, '\n'))
        }
    }
    waitForIdle()
}

/**
 * 滚动真实结果列表并等待可观察滚动位置变化，用于验证关闭重开不会拉回选中行。
 *
 * @param distance 向下滚动的设备像素距离。
 * @return 实际完成滚动后的语义滚动位置。
 */
internal fun DesktopComposeUiTest.scrollSearchWindowResults(distance: Float): Float {
    awaitSearchWindowTag("search-results")
    val window = awaitSearchWindow()
    val previousScroll = runOnUiThread {
        val node = assertNotNull(window.searchWindowNode("search-results"))
        val previous = assertNotNull(node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)).value()
        assertTrue(assertNotNull(node.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(0f, distance))
        previous
    }
    var settledScroll = previousScroll
    var lastChangeAt = System.nanoTime()
    waitUntil(timeoutMillis = SEARCH_WINDOW_WAIT_MILLIS) {
        val current = runOnUiThread {
            window.renderImmediately()
            assertNotNull(window.searchWindowNode("search-results")
                ?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)).value()
        }
        if (current != settledScroll) {
            settledScroll = current
            lastChangeAt = System.nanoTime()
        }
        // ScrollBy 在组件自己的协程中启动动画；跨至少十五个常规帧保持稳定后才能作为恢复基准。
        current > previousScroll && System.nanoTime() - lastChangeAt >= 250_000_000L
    }
    runOnUiThread { window.renderImmediately() }
    return runOnUiThread {
        assertNotNull(window.searchWindowNode("search-results")
            ?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)).value()
    }
}

/**
 * 验证最小窗口中的关键按钮完整可见且文字无溢出。
 *
 * @param tag 待检查的生产控件测试标签。
 * @return 无返回值。
 */
internal fun DesktopComposeUiTest.assertSearchWindowControlIsVisible(tag: String) {
    awaitSearchWindowTag(tag)
    runOnUiThread {
        val window = visibleSearchWindows().single()
        val node = assertNotNull(window.searchWindowNode(tag))
        val bounds = node.boundsInWindow
        val scale = window.graphicsConfiguration.defaultTransform
        assertTrue(bounds.width > 0f && bounds.height > 0f, "Control is clipped: $tag")
        assertTrue(bounds.left >= 0f && bounds.top >= 0f, "Control is outside window: $tag")
        assertTrue(bounds.right <= window.width * scale.scaleX && bounds.bottom <= window.height * scale.scaleY)
        val texts = window.searchWindowNodes().filter { candidate ->
            candidate.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null &&
                generateSequence(candidate) { current -> current.parent }.any { parent -> parent.id == node.id }
        }
        assertTrue(texts.isNotEmpty(), "Control has no text layout: $tag")
        texts.forEach { text ->
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(assertNotNull(text.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(layouts))
            layouts.forEach { layout ->
                assertTrue(!layout.hasVisualOverflow, "Control text overflows: $tag, ${layout.layoutInput.text}")
                assertTrue(text.boundsInWindow.width + 1f >= layout.size.width, "Control text width clipped: $tag")
                assertTrue(text.boundsInWindow.height + 1f >= layout.size.height, "Control text height clipped: $tag")
            }
        }
    }
}

/**
 * 保存实际窗口内容截图，临时置顶只用于防止截到其他应用。
 *
 * @param fileName build/reports/search-window 中的 PNG 名称。
 * @return 无返回值。
 */
internal fun DesktopComposeUiTest.captureSearchWindow(fileName: String) {
    val window = awaitSearchWindow()
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
        robot.waitForIdle()
        Toolkit.getDefaultToolkit().sync()
        robot.delay(300)
        val bounds = runOnUiThread {
            val node = assertNotNull(window.searchWindowNode("search-window-content"))
            val content = node.boundsInWindow
            val scale = window.graphicsConfiguration.defaultTransform
            val origin = window.locationOnScreen
            val left = ceil(content.left / scale.scaleX).toInt()
            val top = ceil(content.top / scale.scaleY).toInt()
            val right = floor(content.right / scale.scaleX).toInt()
            val bottom = floor(content.bottom / scale.scaleY).toInt()
            // 裁掉原生边框与圆角，输出仅包含产品内容。
            Rectangle(origin.x + window.insets.left + left + 8, origin.y + window.insets.top + top, right - left - 16, bottom - top - 8)
        }
        val output = Path.of("build", "reports", "search-window", fileName)
        Files.createDirectories(output.parent)
        assertTrue(ImageIO.write(robot.createScreenCapture(bounds), "png", output.toFile()))
    } finally {
        runOnUiThread { window.isAlwaysOnTop = wasAlwaysOnTop }
    }
}

/**
 * 查找窗口未合并语义树中的指定测试节点；调用方必须在 AWT 线程执行。
 *
 * @param tag 待查找的生产标签。
 * @return 当前存在的语义节点，否则为空。
 */
internal fun ComposeWindow.searchWindowNode(tag: String): SemanticsNode? = searchWindowNodes()
    .firstOrNull { node -> node.config.getOrNull(SemanticsProperties.TestTag) == tag }

/**
 * 确认真实 Compose 焦点位于指定控件或其内部节点，排除焦点误留在查询输入框的情况。
 *
 * @param tag 应当承接键盘路由的控件标签。
 * @return 任一实际获得焦点节点的祖先包含目标控件时返回 true。
 */
private fun ComposeWindow.searchWindowFocusIsInside(tag: String): Boolean = searchWindowNodes().any { node ->
    node.config.getOrNull(SemanticsProperties.Focused) == true &&
        generateSequence(node) { current -> current.parent }.any { ancestor ->
            ancestor.config.getOrNull(SemanticsProperties.TestTag) == tag
        }
}

/**
 * 使用官方 semanticsOwners API 遍历独立窗口的完整语义树。
 *
 * @return 当前已经组合的节点，不依赖 UI 测试框架自身的独立场景注册。
 */
private fun ComposeWindow.searchWindowNodes(): List<SemanticsNode> {
    val pending = ArrayDeque(semanticsOwners.map { owner -> owner.unmergedRootSemanticsNode })
    return buildList {
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            add(node)
            pending.addAll(node.children)
        }
    }
}
