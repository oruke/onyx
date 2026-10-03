@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package com.oruke.onyx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.oruke.onyx.app.component.RemoteConnectionTestState
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.ui.RemoteConnectionFeedback
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.OnyxTheme
import com.oruke.onyx.ui.theme.resolve
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProviderError
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.imageio.ImageIO
import org.jetbrains.jewel.ui.component.Text
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 验证同一批结构化业务消息和连接结果通过真实 Compose 资源渲染成三种语言。 */
class VfsLocalizationWindowTest {
    /**
     * 切换实际桌面 Locale 后重新渲染原有消息，核对协议、能力与完整反馈，并保存窗口截图。
     *
     * @return 无返回值。
     */
    @Test
    fun existingMessagesAndConnectionCapabilitiesRenderInEverySupportedLanguage() = runDesktopComposeUiTest {
        val originalLocale = Locale.getDefault()
        val originalDisplayLocale = Locale.getDefault(Locale.Category.DISPLAY)
        val originalFormatLocale = Locale.getDefault(Locale.Category.FORMAT)
        val locale = mutableStateOf(Locale.ENGLISH)
        val visible = mutableStateOf(true)
        val messages = listOf(VfsProtocol.LOCAL, VfsProtocol.ARCHIVE, VfsProtocol.COLLECTION).map { protocol ->
            VfsProviderError.UnsupportedOperation(
                protocol = protocol,
                capability = VfsProviderCapability.READ_CONTENT,
            ).toI18nMessage()
        }
        val connection = RemoteConnectionTestState.Reachable(
            setOf(VfsProviderCapability.READ_CONTENT, VfsProviderCapability.WRITE_CONTENT, VfsProviderCapability.RENAME),
        )
        try {
            Locale.setDefault(Locale.ENGLISH)
            setContent {
                if (visible.value) {
                    VfsLocalizationTestWindow(locale.value, messages, connection)
                }
            }
            // 保留业务对象与原生窗口，只重新建立当前语言的资源消费端，避免共享测试污染 JVM Locale。
            localizationExpectations().forEach { expected ->
                runOnUiThread {
                    Locale.setDefault(expected.locale)
                    locale.value = expected.locale
                }
                awaitLocalizedText(expected)
                assertLocalizedContent(expected)
                captureLocalizationWindow(expected.locale.toLanguageTag())
            }
        } finally {
            runOnUiThread { visible.value = false }
            waitForIdle()
            Locale.setDefault(originalLocale)
            Locale.setDefault(Locale.Category.DISPLAY, originalDisplayLocale)
            Locale.setDefault(Locale.Category.FORMAT, originalFormatLocale)
        }
    }
}

/** 每种语言下需从真实语义树观察到的完整文本。 */
private data class LocalizationExpectation(
    /** 本轮桌面资源使用的语言环境。 */
    val locale: Locale,
    /** 本地、压缩包与集合的完整错误文本，顺序与测试中的真实业务消息一致。 */
    val messages: List<String>,
    /** 生产连接反馈组件应展示的完整能力说明。 */
    val connectionFeedback: String,
)

/**
 * 返回人工核定的中英日业务文案，避免直接读取资源作为期望值而掩盖错误资源选择。
 *
 * @return 三语期望集合。
 */
private fun localizationExpectations(): List<LocalizationExpectation> = listOf(
    LocalizationExpectation(
        locale = Locale.ENGLISH,
        messages = listOf(
            "Local files capability is not supported: Read content",
            "Archive capability is not supported: Read content",
            "File collection capability is not supported: Read content",
        ),
        connectionFeedback = "Reachable. Capabilities: Read content, Write content, Rename",
    ),
    LocalizationExpectation(
        locale = Locale.SIMPLIFIED_CHINESE,
        messages = listOf("本地文件 不支持该能力：读取内容", "压缩包 不支持该能力：读取内容", "文件集合 不支持该能力：读取内容"),
        connectionFeedback = "连接可达。能力：读取内容, 写入内容, 重命名",
    ),
    LocalizationExpectation(
        locale = Locale.JAPANESE,
        messages = listOf(
            "ローカルファイル はこの機能をサポートしていません: 内容の読み取り",
            "アーカイブ はこの機能をサポートしていません: 内容の読み取り",
            "ファイルコレクション はこの機能をサポートしていません: 内容の読み取り",
        ),
        connectionFeedback = "接続できます。機能: 内容の読み取り, 内容の書き込み, 名前の変更",
    ),
)

/**
 * 在真实桌面窗口中装配生产消息解析器、连接反馈和应用主题。
 *
 * @param locale 当前选择的桌面语言，作为资源消费端重建标识。
 * @param messages 切换语言前已经生成的结构化业务消息。
 * @param connection 切换语言前已经生成的连接结果。
 * @return 无返回值。
 */
@Composable
private fun VfsLocalizationTestWindow(
    locale: Locale,
    messages: List<I18nMessage>,
    connection: RemoteConnectionTestState.Reachable,
) {
    Window(
        onCloseRequest = {},
        title = "Onyx · ${locale.getDisplayLanguage(locale)}",
        state = rememberWindowState(width = 1000.dp, height = 280.dp),
    ) {
        SideEffect { window.name = "onyx-vfs-localization-test" }
        // Compose 1.10.1 没有公开的资源环境 Provider；公开桌面 Locale 由新的资源消费端读取。
        key(locale.toLanguageTag()) {
            OnyxTheme {
                Column(
                    modifier = Modifier.fillMaxSize().testTag("localization-content")
                        .background(LocalOnyxPalette.current.appBackground).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    messages.forEachIndexed { index, message ->
                        Text(text = message.resolve(), modifier = Modifier.testTag("localized-message-$index"))
                    }
                    Column(modifier = Modifier.testTag("localized-connection")) {
                        RemoteConnectionFeedback(testState = connection, error = null, saving = false)
                    }
                }
            }
        }
    }
}

/**
 * 等待资源选择和文本重组完成，不把窗口已显示等同于本地化已经生效。
 *
 * @param expected 本轮期望出现的实际文案。
 * @return 无返回值。
 */
private fun DesktopComposeUiTest.awaitLocalizedText(expected: LocalizationExpectation) {
    waitUntil(timeoutMillis = 5_000L) {
        runOnUiThread {
            val window = localizationWindow() ?: return@runOnUiThread false
            window.renderImmediately()
            val text = window.localizationNodes().mapNotNull { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            }
            expected.messages.all { it in text } && expected.connectionFeedback in text
        }
    }
}

/**
 * 核对完整消息及生产反馈，确认枚举未泄漏且所有实际文字均未被布局裁剪。
 *
 * @param expected 本轮三语期望文本。
 * @return 无返回值。
 */
private fun DesktopComposeUiTest.assertLocalizedContent(expected: LocalizationExpectation) {
    runOnUiThread {
        val nodes = assertNotNull(localizationWindow()).localizationNodes()
        expected.messages.forEachIndexed { index, message ->
            val node = assertNotNull(nodes.firstOrNull {
                it.config.getOrNull(SemanticsProperties.TestTag) == "localized-message-$index"
            })
            assertEquals(message, node.config.getOrNull(SemanticsProperties.Text)?.single()?.text)
        }
        val allText = nodes.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .joinToString("\n") { it.text }
        listOf("LOCAL", "ARCHIVE", "COLLECTION", "READ_CONTENT", "WRITE_CONTENT", "RENAME").forEach { identifier ->
            assertFalse(identifier in allText, "界面不应展示内部枚举：$identifier")
        }
        assertTrue(expected.connectionFeedback in allText)
        nodes.forEach { node ->
            val getLayouts = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return@forEach
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(getLayouts(layouts))
            layouts.forEach { layout ->
                assertFalse(layout.hasVisualOverflow, "本地化文本应完整可见：${layout.layoutInput.text}")
                assertTrue(node.boundsInWindow.width + 1f >= layout.size.width)
                assertTrue(node.boundsInWindow.height + 1f >= layout.size.height)
            }
        }
    }
}

/**
 * 截取本测试窗口的内容区，临时置顶防止其他应用遮挡，完成后恢复置顶状态。
 *
 * @param languageTag 用于区分各语言证据的公开标准语言标签。
 * @return 无返回值。
 */
private fun DesktopComposeUiTest.captureLocalizationWindow(languageTag: String) {
    val window = runOnUiThread { assertNotNull(localizationWindow()) }
    val originalAlwaysOnTop = runOnUiThread {
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
            val content = assertNotNull(window.localizationNodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.TestTag) == "localization-content"
            }).boundsInWindow
            val scale = window.graphicsConfiguration.defaultTransform
            val origin = window.locationOnScreen
            val left = ceil(content.left / scale.scaleX).toInt()
            val top = ceil(content.top / scale.scaleY).toInt()
            val right = floor(content.right / scale.scaleX).toInt()
            val bottom = floor(content.bottom / scale.scaleY).toInt()
            Rectangle(
                origin.x + window.insets.left + left + 8,
                origin.y + window.insets.top + top,
                right - left - 16,
                bottom - top - 8,
            )
        }
        val output = Path.of("build", "reports", "localization", "vfs-$languageTag.png")
        Files.createDirectories(output.parent)
        assertTrue(ImageIO.write(robot.createScreenCapture(bounds), "png", output.toFile()))
    } finally {
        runOnUiThread { window.isAlwaysOnTop = originalAlwaysOnTop }
    }
}

/**
 * 定位唯一的可见测试窗口；必须在 AWT 线程调用。
 *
 * @return 当前测试窗口，窗口尚未创建时为空。
 */
private fun localizationWindow(): ComposeWindow? = java.awt.Window.getWindows().filterIsInstance<ComposeWindow>()
    .singleOrNull { it.name == "onyx-vfs-localization-test" && it.isShowing }

/**
 * 通过 Compose 语义工具接口读取真实窗口未合并的语义树。
 *
 * @return 已组合的全部语义节点。
 */
@OptIn(ComposeToolingApi::class)
private fun ComposeWindow.localizationNodes(): List<SemanticsNode> {
    val pending = ArrayDeque(semanticsOwners.map { it.unmergedRootSemanticsNode })
    return buildList {
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            add(node)
            pending.addAll(node.children)
        }
    }
}
