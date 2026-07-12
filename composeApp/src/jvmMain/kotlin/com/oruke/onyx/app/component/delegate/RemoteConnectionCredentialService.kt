package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.RemoteConnectionDraft
import com.oruke.onyx.app.component.toAuthContextOrNull
import com.oruke.onyx.app.component.toRemoteCredentialSavePolicy
import com.oruke.onyx.app.component.toVfsProtocol
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.vfs.api.RemoteAuthStore
import com.oruke.onyx.vfs.api.RemoteCredentialSavePolicy
import com.oruke.onyx.vfs.api.RemoteCredentialSaveResult
import com.oruke.onyx.vfs.api.RemoteKeyringAuthStore
import com.oruke.onyx.vfs.api.VfsAuthContext

/**
 * 远程连接凭据协调服务，负责在配置编辑、目标迁移和保存策略切换时维护凭据生命周期。
 *
 * 该服务只执行同步平台调用，调用方必须在 IO 调度器中使用。
 *
 * @param remoteAuthStore 统一远程凭据存储。
 */
internal class RemoteConnectionCredentialService(
    private val remoteAuthStore: RemoteAuthStore,
) {
    /**
     * 将编辑草稿中的凭据变化同步到目标存储。
     *
     * 未修改密钥且连接作用域和保存策略均未改变时不会写入，避免空密钥覆盖已有凭据。
     *
     * @param existing 正在编辑的原连接；新建连接时为空。
     * @param draft 当前连接草稿。
     * @param normalizedLocation 规范化后的新连接位置。
     * @return 实际写入结果；无需写入或只清理旧凭据时返回空。
     */
    fun synchronize(
        existing: RemoteConnectionProfile?,
        draft: RemoteConnectionDraft,
        normalizedLocation: String,
    ): RemoteCredentialSaveResult? = if (existing == null) {
        synchronizeNewConnection(draft, normalizedLocation)
    } else {
        synchronizeExistingConnection(existing, draft, normalizedLocation)
    }

    /**
     * 保存新连接草稿中显式输入的凭据。
     *
     * @param draft 新连接草稿。
     * @param normalizedLocation 规范化后的连接位置。
     * @return 实际写入结果；草稿没有凭据时返回空。
     */
    private fun synchronizeNewConnection(
        draft: RemoteConnectionDraft,
        normalizedLocation: String,
    ): RemoteCredentialSaveResult? {
        val newProtocol = draft.protocol.toVfsProtocol()
        val newSavePolicy = draft.savePolicy.toRemoteCredentialSavePolicy()
        return draft.toAuthContextOrNull()?.let { authContext ->
            remoteAuthStore.put(newProtocol, normalizedLocation, authContext, newSavePolicy)
        }
    }

    /**
     * 根据草稿变化更新已有连接凭据，并在作用域切换失败时恢复原凭据。
     *
     * @param existing 编辑前的连接配置。
     * @param draft 当前连接草稿。
     * @param normalizedLocation 规范化后的连接位置。
     * @return 实际写入结果；凭据无需变化时返回空。
     */
    private fun synchronizeExistingConnection(
        existing: RemoteConnectionProfile,
        draft: RemoteConnectionDraft,
        normalizedLocation: String,
    ): RemoteCredentialSaveResult? {
        val newProtocol = draft.protocol.toVfsProtocol()
        val newSavePolicy = draft.savePolicy.toRemoteCredentialSavePolicy()
        return when {
            !requiresSynchronization(existing, draft, normalizedLocation) -> null
            newSavePolicy == RemoteCredentialSavePolicy.SYSTEM_KEYRING && !isSystemKeyringAvailable() -> {
                RemoteCredentialSaveResult.UNSUPPORTED
            }

            else -> replaceExistingCredential(
                existing = existing,
                draft = draft,
                normalizedLocation = normalizedLocation,
                newProtocol = newProtocol,
                newSavePolicy = newSavePolicy,
            )
        }
    }

    /**
     * 清理变化前的凭据作用域并按草稿决定是否写入替换凭据。
     *
     * @param existing 编辑前的连接配置。
     * @param draft 当前连接草稿。
     * @param normalizedLocation 规范化后的连接位置。
     * @param newProtocol 新连接协议。
     * @param newSavePolicy 新保存策略。
     * @return 实际写入结果；无需写入时返回空。
     */
    private fun replaceExistingCredential(
        existing: RemoteConnectionProfile,
        draft: RemoteConnectionDraft,
        normalizedLocation: String,
        newProtocol: com.oruke.onyx.vfs.api.VfsProtocol,
        newSavePolicy: RemoteCredentialSavePolicy,
    ): RemoteCredentialSaveResult? {
        val oldProtocol = existing.protocol.toVfsProtocol()
        val targetChanged = oldProtocol != newProtocol || existing.location != normalizedLocation
        val savePolicyChanged = existing.savePolicy != draft.savePolicy

        val previousAuth = remoteAuthStore.authContext(oldProtocol, existing.location)
        val nextAuth = if (draft.secretChanged) {
            draft.toAuthContextOrNull()
        } else {
            previousAuth.mergeWithDraftMetadata(draft)
        }
        val mustClearOldScope = targetChanged || savePolicyChanged
        if (mustClearOldScope) {
            remoteAuthStore.clear(oldProtocol, existing.location)
        }

        val shouldWrite = nextAuth != null &&
            nextAuth != VfsAuthContext.None &&
            (newSavePolicy != RemoteCredentialSavePolicy.DO_NOT_SAVE || draft.secretChanged)
        return if (shouldWrite) {
            persistReplacementCredential(
                existing = existing,
                previousAuth = previousAuth,
                newProtocol = newProtocol,
                normalizedLocation = normalizedLocation,
                nextAuth = requireNotNull(nextAuth),
                newSavePolicy = newSavePolicy,
                mustClearOldScope = mustClearOldScope,
            )
        } else {
            null
        }
    }

    /**
     * 判断草稿变化是否会影响凭据内容、保存策略或存储作用域。
     *
     * @param existing 编辑前的连接配置。
     * @param draft 当前连接草稿。
     * @param normalizedLocation 规范化后的连接位置。
     * @return 需要同步凭据时返回 true。
     */
    private fun requiresSynchronization(
        existing: RemoteConnectionProfile,
        draft: RemoteConnectionDraft,
        normalizedLocation: String,
    ): Boolean {
        val targetChanged = existing.protocol.toVfsProtocol() != draft.protocol.toVfsProtocol() ||
            existing.location != normalizedLocation
        val savePolicyChanged = existing.savePolicy != draft.savePolicy
        val metadataChanged = existing.username != draft.username.trim() ||
            existing.domain != draft.domain.trim()
        return draft.secretChanged || targetChanged || savePolicyChanged || metadataChanged
    }

    /**
     * 写入替换凭据，并在写入不受支持或失败时恢复已经清理的旧作用域。
     *
     * @param existing 编辑前的连接配置。
     * @param previousAuth 切换前读取的认证上下文。
     * @param newProtocol 新连接协议。
     * @param normalizedLocation 新连接位置。
     * @param nextAuth 待写入认证上下文。
     * @param newSavePolicy 新保存策略。
     * @param mustClearOldScope 是否已经清理旧作用域。
     * @return 新凭据写入结果。
     */
    private fun persistReplacementCredential(
        existing: RemoteConnectionProfile,
        previousAuth: VfsAuthContext,
        newProtocol: com.oruke.onyx.vfs.api.VfsProtocol,
        normalizedLocation: String,
        nextAuth: VfsAuthContext,
        newSavePolicy: RemoteCredentialSavePolicy,
        mustClearOldScope: Boolean,
    ): RemoteCredentialSaveResult {
        val writeResult = runCatching {
            remoteAuthStore.put(newProtocol, normalizedLocation, nextAuth, newSavePolicy)
        }
        writeResult.onSuccess { result ->
            if (result == RemoteCredentialSaveResult.UNSUPPORTED && mustClearOldScope) {
                restorePreviousCredential(existing, previousAuth)
            }
        }.onFailure { failure ->
            if (mustClearOldScope) {
                runCatching { restorePreviousCredential(existing, previousAuth) }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
            }
        }
        return writeResult.getOrThrow()
    }

    /**
     * 解析连接测试应使用的认证上下文。
     *
     * 编辑已有连接且密钥未修改时读取原凭据，并把草稿中的用户名、域或 Region 合并进去。
     *
     * @param existing 正在编辑的原连接；新建连接时为空。
     * @param draft 当前连接草稿。
     * @return 可用于本次连接测试的认证上下文。
     */
    fun authContextForTest(
        existing: RemoteConnectionProfile?,
        draft: RemoteConnectionDraft,
    ): VfsAuthContext {
        if (existing == null || draft.secretChanged) {
            return draft.toAuthContextOrNull() ?: VfsAuthContext.None
        }
        return remoteAuthStore
            .authContext(existing.protocol.toVfsProtocol(), existing.location)
            .mergeWithDraftMetadata(draft)
            ?: VfsAuthContext.None
    }

    /**
     * 删除连接关联的会话凭据和系统钥匙串凭据。
     *
     * @param profile 即将删除的连接配置。
     */
    fun delete(profile: RemoteConnectionProfile) {
        remoteAuthStore.clear(profile.protocol.toVfsProtocol(), profile.location)
    }

    /**
     * 检查统一凭据存储是否支持系统钥匙串。
     *
     * @return 系统钥匙串可写时返回 true。
     */
    private fun isSystemKeyringAvailable(): Boolean {
        return (remoteAuthStore as? RemoteKeyringAuthStore)?.isSystemKeyringAvailable() == true
    }

    /**
     * 在新凭据写入失败时恢复旧连接凭据，避免策略切换造成凭据丢失。
     *
     * @param existing 原连接配置。
     * @param previousAuth 切换前读取的认证上下文。
     */
    private fun restorePreviousCredential(
        existing: RemoteConnectionProfile,
        previousAuth: VfsAuthContext,
    ) {
        if (previousAuth == VfsAuthContext.None) return
        remoteAuthStore.put(
            protocol = existing.protocol.toVfsProtocol(),
            location = existing.location,
            authContext = previousAuth,
            savePolicy = existing.savePolicy.toRemoteCredentialSavePolicy(),
        )
    }
}

/**
 * 使用草稿中的非密钥字段更新认证上下文，同时保留原密码或 Secret Access Key。
 *
 * @param draft 当前连接草稿。
 * @return 合并后的认证上下文；原上下文为空时返回空。
 */
private fun VfsAuthContext.mergeWithDraftMetadata(draft: RemoteConnectionDraft): VfsAuthContext? {
    return when (this) {
        is VfsAuthContext.UsernamePassword -> copy(
            username = draft.username.trim(),
            domain = draft.domain.trim().ifBlank { null },
        )

        is VfsAuthContext.AwsCredentials -> copy(
            accessKeyId = draft.username.trim(),
            region = draft.domain.trim().ifBlank { null },
        )

        is VfsAuthContext.BearerToken -> this

        VfsAuthContext.None -> null
    }
}
