package com.oruke.onyx.ui

import androidx.compose.runtime.Composable
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsProviderCapability
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_vfs_protocol_system
import onyx.composeapp.generated.resources.label_remote_protocol_s3
import onyx.composeapp.generated.resources.label_remote_protocol_smb
import onyx.composeapp.generated.resources.label_remote_protocol_webdav
import onyx.composeapp.generated.resources.label_vfs_capability_copy
import onyx.composeapp.generated.resources.label_vfs_capability_create_directory
import onyx.composeapp.generated.resources.label_vfs_capability_create_file
import onyx.composeapp.generated.resources.label_vfs_capability_delete
import onyx.composeapp.generated.resources.label_vfs_capability_external_open
import onyx.composeapp.generated.resources.label_vfs_capability_move
import onyx.composeapp.generated.resources.label_vfs_capability_read_content
import onyx.composeapp.generated.resources.label_vfs_capability_read_preview
import onyx.composeapp.generated.resources.label_vfs_capability_read_random_access
import onyx.composeapp.generated.resources.label_vfs_capability_rename
import onyx.composeapp.generated.resources.label_vfs_capability_thumbnail
import onyx.composeapp.generated.resources.label_vfs_capability_trash
import onyx.composeapp.generated.resources.label_vfs_capability_watch
import onyx.composeapp.generated.resources.label_vfs_capability_write_content
import onyx.composeapp.generated.resources.label_vfs_capability_write_random_access
import onyx.composeapp.generated.resources.label_vfs_protocol_archive
import onyx.composeapp.generated.resources.label_vfs_protocol_collection
import onyx.composeapp.generated.resources.label_vfs_protocol_local
import org.jetbrains.compose.resources.stringResource

/**
 * 返回协议在连接反馈与结构化错误中共用的本地化名称。
 *
 * @param protocol 统一 VFS 协议。
 * @return 当前界面语言下的协议名称。
 */
@Composable
internal fun vfsProtocolLabel(protocol: VfsProtocol): String {
    val resource = when (protocol) {
        VfsProtocol.SYSTEM -> Res.string.label_vfs_protocol_system
        VfsProtocol.LOCAL -> Res.string.label_vfs_protocol_local
        VfsProtocol.ARCHIVE -> Res.string.label_vfs_protocol_archive
        VfsProtocol.COLLECTION -> Res.string.label_vfs_protocol_collection
        VfsProtocol.SMB -> Res.string.label_remote_protocol_smb
        VfsProtocol.WEBDAV -> Res.string.label_remote_protocol_webdav
        VfsProtocol.S3 -> Res.string.label_remote_protocol_s3
    }
    return stringResource(resource)
}

/**
 * 返回协议能力在连接反馈与不支持操作错误中共用的本地化名称。
 *
 * @param capability 统一 VFS 能力。
 * @return 当前界面语言下的能力名称，不暴露枚举标识。
 */
@Composable
internal fun vfsProviderCapabilityLabel(capability: VfsProviderCapability): String {
    val resource = when (capability) {
        VfsProviderCapability.WATCH -> Res.string.label_vfs_capability_watch
        VfsProviderCapability.TRASH -> Res.string.label_vfs_capability_trash
        VfsProviderCapability.EXTERNAL_OPEN -> Res.string.label_vfs_capability_external_open
        VfsProviderCapability.READ_PREVIEW -> Res.string.label_vfs_capability_read_preview
        VfsProviderCapability.READ_CONTENT -> Res.string.label_vfs_capability_read_content
        VfsProviderCapability.WRITE_CONTENT -> Res.string.label_vfs_capability_write_content
        VfsProviderCapability.READ_RANDOM_ACCESS -> Res.string.label_vfs_capability_read_random_access
        VfsProviderCapability.WRITE_RANDOM_ACCESS -> Res.string.label_vfs_capability_write_random_access
        VfsProviderCapability.THUMBNAIL -> Res.string.label_vfs_capability_thumbnail
        VfsProviderCapability.CREATE_FILE -> Res.string.label_vfs_capability_create_file
        VfsProviderCapability.CREATE_DIRECTORY -> Res.string.label_vfs_capability_create_directory
        VfsProviderCapability.RENAME -> Res.string.label_vfs_capability_rename
        VfsProviderCapability.DELETE -> Res.string.label_vfs_capability_delete
        VfsProviderCapability.COPY -> Res.string.label_vfs_capability_copy
        VfsProviderCapability.MOVE -> Res.string.label_vfs_capability_move
    }
    return stringResource(resource)
}
