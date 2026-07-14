package com.oruke.onyx.app.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.oruke.onyx.app.component.delegate.ClipboardManager
import com.oruke.onyx.app.component.delegate.SessionManager
import com.oruke.onyx.app.component.delegate.TaskOrchestrator
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.vfs.api.SessionRepository
import com.oruke.onyx.vfs.api.SettingsRepository
import com.oruke.onyx.vfs.api.TaskPersistenceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.compose.getKoin

/**
 * 所有文件管理器窗口共享的应用级运行时。
 *
 * 后台任务不能随任意单个窗口销毁，剪贴板与设置也必须在窗口之间保持一致。
 */
internal class RootApplicationRuntime(
    /** 应用级协程作用域。 */
    private val scope: CoroutineScope,
    /** 所有窗口共享的后台任务编排器。 */
    val taskOrchestrator: TaskOrchestrator,
    /** 所有窗口共享的文件剪贴板。 */
    val clipboardManager: ClipboardManager,
    /** 所有窗口共享的设置与会话持久化管理器。 */
    val sessionManager: SessionManager,
    /** 所有窗口共享的即时设置状态。 */
    val settings: MutableStateFlow<OnyxSettings>,
) {
    /**
     * 结束应用级后台工作。
     *
     * @return 无返回值。
     */
    fun close() {
        scope.cancel()
    }
}

/**
 * 创建并按 Compose 应用生命周期管理共享根运行时。
 *
 * @return 当前应用实例唯一的共享运行时。
 */
@Composable
internal fun rememberRootApplicationRuntime(): RootApplicationRuntime {
    val koin = getKoin()
    val runtime = remember {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        RootApplicationRuntime(
            scope = scope,
            taskOrchestrator = TaskOrchestrator(
                scope = scope,
                taskRepository = koin.get<TaskPersistenceRepository>(),
            ),
            clipboardManager = ClipboardManager(),
            sessionManager = SessionManager(
                settingsRepository = koin.get<SettingsRepository>(),
                sessionRepository = koin.get<SessionRepository>(),
            ),
            settings = MutableStateFlow(OnyxSettings()),
        )
    }
    DisposableEffect(runtime) {
        onDispose(runtime::close)
    }
    return runtime
}
