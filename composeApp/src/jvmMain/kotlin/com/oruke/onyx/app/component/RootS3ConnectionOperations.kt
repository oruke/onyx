package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.vfs.s3.S3ConnectionRegistration

/**
 * 将已保存网络位置同步到运行期 S3 配置仓库。
 *
 * 配置仓库必须与设置状态同时更新，确保保存、删除或恢复连接后，后续文件请求立即使用最新 Endpoint。
 */
internal fun DefaultRootComponent.synchronizeS3ConnectionConfigurations() {
    val registrations = settings.value.remoteConnections
        .asSequence()
        .filter { connection -> connection.protocol == RemoteConnectionProtocol.S3 }
        .map { connection ->
            S3ConnectionRegistration(
                rootLocation = connection.location,
                config = connection.s3Config,
            )
        }
        .toList()
    s3ConnectionRepository.replaceAll(registrations)
}
