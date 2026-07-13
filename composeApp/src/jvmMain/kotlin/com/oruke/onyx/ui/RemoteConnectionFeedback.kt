package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.RemoteConnectionDialogError
import com.oruke.onyx.app.component.RemoteConnectionTestState
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.resolve
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_remote_connection_error_location_required
import onyx.composeapp.generated.resources.label_remote_connection_error_s3_endpoint_invalid
import onyx.composeapp.generated.resources.label_remote_connection_error_s3_region_required
import onyx.composeapp.generated.resources.label_remote_connection_error_location_invalid
import onyx.composeapp.generated.resources.label_remote_connection_error_name_required
import onyx.composeapp.generated.resources.label_remote_connection_error_secret_required
import onyx.composeapp.generated.resources.label_remote_connection_error_username_required
import onyx.composeapp.generated.resources.label_remote_connection_error_credential_save_failed
import onyx.composeapp.generated.resources.label_remote_connection_saving
import onyx.composeapp.generated.resources.label_remote_connection_test_failed
import onyx.composeapp.generated.resources.label_remote_connection_test_ready
import onyx.composeapp.generated.resources.label_remote_connection_test_success
import onyx.composeapp.generated.resources.label_remote_connection_testing
import onyx.composeapp.generated.resources.label_remote_credentials_system_keyring_unavailable
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/**
 * 展示表单错误或连接测试状态。
 *
 * @param testState 当前连接测试状态。
 * @param error 当前表单校验错误。
 * @param saving 是否正在保存连接与凭据。
 */
@Composable
internal fun RemoteConnectionFeedback(
    testState: RemoteConnectionTestState,
    error: RemoteConnectionDialogError?,
    saving: Boolean,
) {
    val palette = LocalOnyxPalette.current
    val feedback = when {
        error != null -> RemoteConnectionFeedbackValue(remoteConnectionErrorText(error), REMOTE_ERROR_COLOR)
        saving -> RemoteConnectionFeedbackValue(
            text = stringResource(Res.string.label_remote_connection_saving),
            color = palette.accent,
        )
        else -> when (testState) {
            RemoteConnectionTestState.Idle -> RemoteConnectionFeedbackValue(
                text = stringResource(Res.string.label_remote_connection_test_ready),
                color = palette.mutedForeground,
            )
            RemoteConnectionTestState.Testing -> RemoteConnectionFeedbackValue(
                text = stringResource(Res.string.label_remote_connection_testing),
                color = palette.accent,
            )
            is RemoteConnectionTestState.Reachable -> RemoteConnectionFeedbackValue(
                text = stringResource(
                    Res.string.label_remote_connection_test_success,
                    testState.capabilities.joinToString(", "),
                ),
                color = REMOTE_SUCCESS_COLOR,
            )
            is RemoteConnectionTestState.Failed -> RemoteConnectionFeedbackValue(
                text = stringResource(Res.string.label_remote_connection_test_failed, testState.reason.resolve()),
                color = REMOTE_ERROR_COLOR,
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(modifier = Modifier.size(7.dp).background(feedback.color, CircleShape))
        Text(
            text = feedback.text,
            modifier = Modifier.weight(1f),
            fontSize = 10.sp,
            color = feedback.color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 网络连接反馈文本及强调色。 */
private data class RemoteConnectionFeedbackValue(
    /** 反馈文本。 */
    val text: String,
    /** 反馈强调色。 */
    val color: Color,
)

/**
 * 返回网络位置表单错误的本地化文本。
 *
 * @param error 网络位置表单错误。
 * @return 本地化错误文本。
 */
@Composable
private fun remoteConnectionErrorText(error: RemoteConnectionDialogError): String {
    return when (error) {
        RemoteConnectionDialogError.NAME_EMPTY ->
            stringResource(Res.string.label_remote_connection_error_name_required)
        RemoteConnectionDialogError.LOCATION_EMPTY ->
            stringResource(Res.string.label_remote_connection_error_location_required)
        RemoteConnectionDialogError.LOCATION_INVALID ->
            stringResource(Res.string.label_remote_connection_error_location_invalid)
        RemoteConnectionDialogError.S3_ENDPOINT_INVALID ->
            stringResource(Res.string.label_remote_connection_error_s3_endpoint_invalid)
        RemoteConnectionDialogError.S3_REGION_EMPTY ->
            stringResource(Res.string.label_remote_connection_error_s3_region_required)
        RemoteConnectionDialogError.USERNAME_EMPTY ->
            stringResource(Res.string.label_remote_connection_error_username_required)
        RemoteConnectionDialogError.SECRET_EMPTY ->
            stringResource(Res.string.label_remote_connection_error_secret_required)
        RemoteConnectionDialogError.SYSTEM_KEYRING_UNAVAILABLE ->
            stringResource(Res.string.label_remote_credentials_system_keyring_unavailable)
        RemoteConnectionDialogError.CREDENTIAL_SAVE_FAILED ->
            stringResource(Res.string.label_remote_connection_error_credential_save_failed)
    }
}

/** 连接成功状态颜色。 */
private val REMOTE_SUCCESS_COLOR = Color(0xFF2E8B57)

/** 连接失败或表单错误状态颜色。 */
private val REMOTE_ERROR_COLOR = Color(0xFFD74E4E)
