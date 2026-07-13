package com.oruke.onyx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.component.RemoteConnectionDraft
import com.oruke.onyx.core.model.S3AddressingStyle
import com.oruke.onyx.core.model.S3ProviderPreset
import com.oruke.onyx.core.model.allowsAddressingStyleSelection
import com.oruke.onyx.core.model.requiresExplicitEndpoint
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.hint_remote_connection_s3_addressing_fixed
import onyx.composeapp.generated.resources.hint_remote_connection_s3_credentials_required
import onyx.composeapp.generated.resources.hint_remote_connection_s3_endpoint_alibaba
import onyx.composeapp.generated.resources.hint_remote_connection_s3_endpoint_aws
import onyx.composeapp.generated.resources.hint_remote_connection_s3_endpoint_custom
import onyx.composeapp.generated.resources.hint_remote_connection_s3_endpoint_minio
import onyx.composeapp.generated.resources.hint_remote_connection_s3_endpoint_tencent
import onyx.composeapp.generated.resources.hint_remote_connection_s3_region_alibaba
import onyx.composeapp.generated.resources.hint_remote_connection_s3_region_aws
import onyx.composeapp.generated.resources.hint_remote_connection_s3_region_custom
import onyx.composeapp.generated.resources.hint_remote_connection_s3_region_minio
import onyx.composeapp.generated.resources.hint_remote_connection_s3_region_tencent
import onyx.composeapp.generated.resources.label_remote_connection_s3_access_key
import onyx.composeapp.generated.resources.label_remote_connection_s3_addressing_path
import onyx.composeapp.generated.resources.label_remote_connection_s3_addressing_style
import onyx.composeapp.generated.resources.label_remote_connection_s3_addressing_virtual
import onyx.composeapp.generated.resources.label_remote_connection_s3_endpoint_optional
import onyx.composeapp.generated.resources.label_remote_connection_s3_endpoint_required
import onyx.composeapp.generated.resources.label_remote_connection_s3_provider
import onyx.composeapp.generated.resources.label_remote_connection_s3_provider_alibaba
import onyx.composeapp.generated.resources.label_remote_connection_s3_provider_aws
import onyx.composeapp.generated.resources.label_remote_connection_s3_provider_custom
import onyx.composeapp.generated.resources.label_remote_connection_s3_provider_minio
import onyx.composeapp.generated.resources.label_remote_connection_s3_provider_tencent
import onyx.composeapp.generated.resources.label_remote_connection_s3_region
import onyx.composeapp.generated.resources.label_remote_connection_s3_secret_key
import org.jetbrains.compose.resources.stringResource

/**
 * 展示通用 S3 兼容连接参数和访问凭据。
 *
 * @param connectionDraft 当前连接草稿。
 * @param isEditing 是否编辑已有连接。
 * @param onDraftChange 草稿变化回调。
 * @param fontSize 正文字号。
 * @param enabled 是否允许编辑。
 */
@Composable
internal fun S3CredentialFields(
    connectionDraft: RemoteConnectionDraft,
    isEditing: Boolean,
    onDraftChange: (RemoteConnectionDraft) -> Unit,
    fontSize: TextUnit,
    enabled: Boolean,
) {
    val config = connectionDraft.s3Config
    RemoteConnectionFieldLabel(stringResource(Res.string.label_remote_connection_s3_provider))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        S3ProviderPreset.entries.forEach { provider ->
            SettingsOption(
                selected = config.provider == provider,
                text = s3ProviderLabel(provider),
                fontSize = fontSize,
                onClick = {
                    if (enabled) {
                        onDraftChange(connectionDraft.copy(s3Config = config.copy(provider = provider)))
                    }
                },
            )
        }
    }
    RemoteConnectionField(
        label = stringResource(
            if (config.provider.requiresExplicitEndpoint()) {
                Res.string.label_remote_connection_s3_endpoint_required
            } else {
                Res.string.label_remote_connection_s3_endpoint_optional
            },
        ),
        value = config.endpoint,
        onValueChange = { value ->
            onDraftChange(connectionDraft.copy(s3Config = config.copy(endpoint = value)))
        },
        supportingText = s3EndpointHint(config.provider),
        fontSize = fontSize,
        enabled = enabled,
    )
    RemoteConnectionField(
        label = stringResource(Res.string.label_remote_connection_s3_region),
        value = config.region,
        onValueChange = { value ->
            onDraftChange(connectionDraft.copy(s3Config = config.copy(region = value)))
        },
        supportingText = s3RegionHint(config.provider),
        fontSize = fontSize,
        enabled = enabled,
    )
    S3AddressingStyleFields(
        connectionDraft = connectionDraft,
        onDraftChange = onDraftChange,
        fontSize = fontSize,
        enabled = enabled,
    )
    CredentialPair(
        connectionDraft = connectionDraft,
        identityLabel = stringResource(Res.string.label_remote_connection_s3_access_key),
        secretLabel = stringResource(Res.string.label_remote_connection_s3_secret_key),
        isEditing = isEditing,
        onDraftChange = onDraftChange,
        fontSize = fontSize,
        enabled = enabled,
    )
    RemoteConnectionFieldHint(stringResource(Res.string.hint_remote_connection_s3_credentials_required))
}

/**
 * 展示 Bucket 寻址方式；固定虚拟主机寻址的预设只展示约束说明。
 *
 * @param connectionDraft 当前连接草稿。
 * @param onDraftChange 草稿变化回调。
 * @param fontSize 选项字号。
 * @param enabled 是否允许编辑。
 */
@Composable
private fun S3AddressingStyleFields(
    connectionDraft: RemoteConnectionDraft,
    onDraftChange: (RemoteConnectionDraft) -> Unit,
    fontSize: TextUnit,
    enabled: Boolean,
) {
    val config = connectionDraft.s3Config
    RemoteConnectionFieldLabel(stringResource(Res.string.label_remote_connection_s3_addressing_style))
    if (config.provider.allowsAddressingStyleSelection()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            S3AddressingStyle.entries.forEach { style ->
                SettingsOption(
                    selected = config.addressingStyle == style,
                    text = s3AddressingStyleLabel(style),
                    fontSize = fontSize,
                    onClick = {
                        if (enabled) {
                            onDraftChange(connectionDraft.copy(s3Config = config.copy(addressingStyle = style)))
                        }
                    },
                )
            }
        }
    } else {
        RemoteConnectionFieldHint(stringResource(Res.string.hint_remote_connection_s3_addressing_fixed))
    }
}

/**
 * 返回 S3 服务商预设的本地化名称。
 *
 * @param provider 服务商预设。
 * @return 本地化名称。
 */
@Composable
private fun s3ProviderLabel(provider: S3ProviderPreset): String = when (provider) {
    S3ProviderPreset.AMAZON_S3 -> stringResource(Res.string.label_remote_connection_s3_provider_aws)
    S3ProviderPreset.TENCENT_COS -> stringResource(Res.string.label_remote_connection_s3_provider_tencent)
    S3ProviderPreset.ALIBABA_OSS -> stringResource(Res.string.label_remote_connection_s3_provider_alibaba)
    S3ProviderPreset.MINIO -> stringResource(Res.string.label_remote_connection_s3_provider_minio)
    S3ProviderPreset.CUSTOM -> stringResource(Res.string.label_remote_connection_s3_provider_custom)
}

/**
 * 返回 S3 寻址方式的本地化名称。
 *
 * @param style 寻址方式。
 * @return 本地化名称。
 */
@Composable
private fun s3AddressingStyleLabel(style: S3AddressingStyle): String = when (style) {
    S3AddressingStyle.VIRTUAL_HOSTED -> stringResource(Res.string.label_remote_connection_s3_addressing_virtual)
    S3AddressingStyle.PATH_STYLE -> stringResource(Res.string.label_remote_connection_s3_addressing_path)
}

/**
 * 返回服务商对应的 Endpoint 输入说明。
 *
 * @param provider 服务商预设。
 * @return 本地化 Endpoint 说明。
 */
@Composable
private fun s3EndpointHint(provider: S3ProviderPreset): String = when (provider) {
    S3ProviderPreset.AMAZON_S3 -> stringResource(Res.string.hint_remote_connection_s3_endpoint_aws)
    S3ProviderPreset.TENCENT_COS -> stringResource(Res.string.hint_remote_connection_s3_endpoint_tencent)
    S3ProviderPreset.ALIBABA_OSS -> stringResource(Res.string.hint_remote_connection_s3_endpoint_alibaba)
    S3ProviderPreset.MINIO -> stringResource(Res.string.hint_remote_connection_s3_endpoint_minio)
    S3ProviderPreset.CUSTOM -> stringResource(Res.string.hint_remote_connection_s3_endpoint_custom)
}

/**
 * 返回服务商对应的 Region 输入说明。
 *
 * @param provider 服务商预设。
 * @return 本地化 Region 说明。
 */
@Composable
private fun s3RegionHint(provider: S3ProviderPreset): String = when (provider) {
    S3ProviderPreset.AMAZON_S3 -> stringResource(Res.string.hint_remote_connection_s3_region_aws)
    S3ProviderPreset.TENCENT_COS -> stringResource(Res.string.hint_remote_connection_s3_region_tencent)
    S3ProviderPreset.ALIBABA_OSS -> stringResource(Res.string.hint_remote_connection_s3_region_alibaba)
    S3ProviderPreset.MINIO -> stringResource(Res.string.hint_remote_connection_s3_region_minio)
    S3ProviderPreset.CUSTOM -> stringResource(Res.string.hint_remote_connection_s3_region_custom)
}
