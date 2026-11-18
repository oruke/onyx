package com.oruke.onyx.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.oruke.onyx.app.component.DefaultTaskCenterComponent
import com.oruke.onyx.app.component.delegate.TaskOrchestrator
import com.oruke.onyx.app.platform.JvmApplicationLocaleController
import com.oruke.onyx.core.model.AppLocale
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.OnyxThemeMode
import com.oruke.onyx.core.model.TaskError
import com.oruke.onyx.ui.theme.OnyxLocaleEnvironment
import com.oruke.onyx.ui.theme.OnyxWindowTheme
import com.oruke.onyx.vfs.api.TextClipboardService
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_remove_task
import org.jetbrains.compose.resources.getString

/** 验证任务中心结构化错误面板渲染、展开复制交互以及移除按钮销毁后绝不残留悬浮提示。 */
@OptIn(ExperimentalTestApi::class)
class TaskCenterErrorAndTooltipUiTest {
    @Test
    fun rendersStructuredErrorsSupportsExpandCopyAndClearsRemoveTooltipOnDismiss() = runDesktopComposeUiTest(
        width = 740,
        height = 720,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val orchestrator = TaskOrchestrator(scope)
        var copiedContent = ""
        val clipboard = object : TextClipboardService {
            override suspend fun copyText(text: String): Result<Unit> {
                copiedContent = text
                return Result.success(Unit)
            }
        }
        val preferences = MutableStateFlow(
            OnyxSettings(
                themeMode = OnyxThemeMode.DARK,
            )
        )
        val component = DefaultTaskCenterComponent(
            taskOrchestrator = orchestrator,
            settings = preferences,
            textClipboardService = clipboard,
            componentScope = scope,
        )
        val sampleErrors = listOf(
            "[Akisaka Yamoka] Jitsu no Imouto ga Ore ni Tsumetai node, Imouto no Tomodachi ni Nakadashi " +
                "Shichaimashita - My younger sister was cold to wards me 因為親妹妹對我很冷淡，所以只好內射她的好朋友 [Chinese] [De.zip",
            "[Tenkirin (Kanroame)] Sonna Ai nara Iranai ~Arisu nanka Janai noni Tonari no Onii-san ga Arisu " +
                "tte Yondekite nanka Kowai~ 那样的爱我才不想要呢 ~ 明明我的名字又不是爱丽丝 [Chines.zip",
            "[Tenkirin (Kanroame)] Sonna Ainara Iranai 2～Arisu Nanka Janai no ni Tonari no Onii-san ga Arisu " +
                "tte Yondekite Nanka Kowai～ 那样的爱我才不想要呢～2 [Chinese] [白杨汉化组] [Digital].zip",
            "[Kanroame] E nansuka  InCha dakara Choroi toka Omowareterutte Koto suka (Hayakuchi) 咦，难道因为我性格内向了一点 [神人汉化组].zip",
            "[Tenkirin (Kanroame)] Sonna Ai nara Iranai 3 ~ Alice Nanka ja Nai no ni Tonari no Onii-san ga Alice " +
                "tte Yonde kite Nanka Kowai~ 那样的爱我才不想要呢～3 [Chinese] [白杨汉化组] [Digital].zip",
            "[Amagami Honpo (Manno)] Nijigen Otaku no Ore ga Tenkou Shite Kita Kyonyuu Idol ni Otosareru made " +
                "身为二次元宅男的我被转校而来的巨乳偶像给就这样攻陷了 [Chinese] [白杨汉化组] [Digital].zip",
        ).map { name ->
            TaskError(
                fileName = name,
                message = "文件名、目录名或卷标语法不正确。",
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, "文件名、目录名或卷标语法不正确。"),
            )
        }

        try {
            runOnUiThread {
                orchestrator.appendTask(
                    BackgroundTask(
                        id = "batch",
                        kind = BackgroundTaskKind.COPY,
                        title = I18nMessage(MessageKey.MSG_COPY_ITEMS, 148),
                        status = BackgroundTaskStatus.FAILED,
                        detail = I18nMessage(
                            MessageKey.MSG_COPY_PARTIAL_FAILED,
                            142,
                            """Z:\unsorted\ebook\H漫""",
                            6,
                        ),
                        progress = 0.96f,
                        processedCount = 142,
                        totalCount = 148,
                        processedBytes = 19_542_100_000L,
                        totalBytes = 20_310_000_000L,
                        errors = sampleErrors,
                    )
                )
                orchestrator.appendTask(
                    BackgroundTask(
                        id = "active",
                        kind = BackgroundTaskKind.COPY,
                        title = I18nMessage(MessageKey.MSG_COPY_ITEMS, 24),
                        status = BackgroundTaskStatus.RUNNING,
                        canPause = true,
                        detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, """D:\downloads -> Z:\unsorted\ebook"""),
                        progress = 0.62f,
                        processedCount = 15,
                        totalCount = 24,
                        processedBytes = 6_400_000_000L,
                        totalBytes = 10_200_000_000L,
                    )
                )
            }

            setContent {
                val localeController = remember(preferences) { JvmApplicationLocaleController(preferences) }
                val localeScope = rememberCoroutineScope()
                DisposableEffect(localeController, localeScope) {
                    val binding = localeController.bind(localeScope)
                    onDispose(binding::cancel)
                }
                val state by component.state.collectAsState()
                val tasks by component.tasks.collectAsState()
                val currentSettings by preferences.collectAsState()
                OnyxLocaleEnvironment(localeController.appliedLocale) {
                    OnyxWindowTheme(currentSettings) {
                        OnyxTooltipHost {
                            TaskCenterContent(
                                state = state,
                                tasks = tasks,
                                listState = rememberLazyListState(),
                                dispatch = component::dispatch,
                            )
                        }
                    }
                }
            }
            waitForIdle()

            onNodeWithTag("task-error-item-batch-0").assertIsDisplayed()
            onNodeWithTag("task-error-item-batch-1").assertIsDisplayed()
            onNodeWithTag("task-errors-more-batch").assertIsDisplayed()
            saveOffscreenScreenshot("task-errors-collapsed-dark.png", onRoot().captureToImage().toAwtImage())

            // 展开全部 6 条错误并触发复制全部，抓取展开态与“已复制”状态截图
            onNodeWithTag("task-errors-toggle-batch").performClick()
            waitForIdle()
            onNodeWithTag("task-error-item-batch-5").performScrollTo().assertIsDisplayed()
            onNodeWithTag("task-errors-copy-all-batch").performClick()
            waitUntil(timeoutMillis = 5_000L) { copiedContent.contains("[6]") }
            waitForIdle()
            saveOffscreenScreenshot("task-errors-expanded-dark.png", onRoot().captureToImage().toAwtImage())

            // 真实鼠标悬停到失败任务的“移除”按钮上直至浮层显示，再点击移除该任务，断言“移除”浮层立即销毁不残留！
            val removeText = getString(Res.string.action_remove_task)
            onNodeWithContentDescription(removeText).performMouseInput { enter(center) }
            mainClock.advanceTimeBy(450L)
            waitUntil(timeoutMillis = 5_000L) {
                onAllNodesWithText(removeText).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithContentDescription(removeText).performClick()
            waitForIdle()
            onAllNodesWithText(removeText).assertCountEquals(0)
        } finally {
            scope.cancel()
        }
    }

    private fun saveOffscreenScreenshot(fileName: String, image: java.awt.image.BufferedImage) {
        val output = Path.of("build", "reports", "task-center-offscreen", fileName)
        Files.createDirectories(output.parent)
        assertTrue(ImageIO.write(image, "png", output.toFile()))
    }
}
