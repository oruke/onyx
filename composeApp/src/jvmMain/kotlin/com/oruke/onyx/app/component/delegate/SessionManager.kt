package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.filesystem.SessionRepository
import com.oruke.onyx.app.filesystem.SettingsRepository
import com.oruke.onyx.core.model.AppSessionSnapshot
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.OnyxSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 会话与设置持久化管理器。
 *
 * 职责：
 * - 从磁盘恢复设置和会话状态
 * - 将当前设置和会话快照保存到磁盘
 *
 * 不直接持有业务状态，通过回调和返回值与 RootComponent 交互。
 */
class SessionManager(
    private val settingsRepository: SettingsRepository,
    private val sessionRepository: SessionRepository,
) {
    private val persistenceMutex = Mutex()

    /**
     * 恢复持久化的设置和会话。
     *
     * @return Pair(loadedSettings?, loadedSession?)，以及恢复过程中的错误信息
     */
    data class RestoreResult(
        val settings: OnyxSettings?,
        val session: AppSessionSnapshot?,
        val error: I18nMessage?,
    )

    suspend fun restore(): RestoreResult {
        var settings: OnyxSettings? = null
        var session: AppSessionSnapshot? = null
        var error: I18nMessage? = null

        settingsRepository.loadSettings().fold(
            onSuccess = { loadedSettings ->
                settings = loadedSettings
            },
            onFailure = { failure ->
                error = failure.message?.let { I18nMessage(MessageKey.MSG_STRING_LITERAL, it) }
                    ?: I18nMessage(MessageKey.MSG_LOAD_SETTINGS_FAILED)
                OnyxLogger.warn("SessionManager", "设置加载失败", failure)
            },
        )

        sessionRepository.loadSession().fold(
            onSuccess = { loadedSession ->
                session = loadedSession
            },
            onFailure = { failure ->
                error = error ?: failure.message?.let { I18nMessage(MessageKey.MSG_STRING_LITERAL, it) }
                    ?: I18nMessage(MessageKey.MSG_RESTORE_SESSION_FAILED)
                OnyxLogger.warn("SessionManager", "会话恢复失败", failure)
            },
        )

        return RestoreResult(settings, session, error)
    }

    /**
     * 持久化当前状态到磁盘。使用 Mutex 避免并发写入冲突。
     */
    suspend fun persist(settings: OnyxSettings, sessionSnapshot: AppSessionSnapshot) {
        persistenceMutex.withLock {
            settingsRepository.saveSettings(settings)
            sessionRepository.saveSession(sessionSnapshot)
        }
    }

    /**
     * 单独保存设置。
     */
    suspend fun saveSettings(settings: OnyxSettings) {
        persistenceMutex.withLock {
            settingsRepository.saveSettings(settings)
        }
    }
}
