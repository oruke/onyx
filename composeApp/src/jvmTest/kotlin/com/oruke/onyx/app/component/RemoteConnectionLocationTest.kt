package com.oruke.onyx.app.component

import com.oruke.onyx.app.component.delegate.credentialValidationError
import com.oruke.onyx.core.model.RemoteConnectionProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 网络位置协议表单的地址与凭据规则测试。 */
class RemoteConnectionLocationTest {
    /** 校验 Windows UNC 可规范化为支持中文路径的 SMB URI。 */
    @Test
    fun normalizesWindowsUncLocation() {
        val normalized = RemoteConnectionLocation.normalize(
            RemoteConnectionProtocol.SMB,
            "\\\\server\\共享目录",
        )

        assertEquals("smb://server/%E5%85%B1%E4%BA%AB%E7%9B%AE%E5%BD%95/", normalized)
    }

    /** 校验 WebDAV 可接受与所选安全级别一致的 HTTP(S) 地址。 */
    @Test
    fun normalizesWebDavHttpAliases() {
        assertEquals(
            "webdav://server:8080/dav/",
            RemoteConnectionLocation.normalize(
                RemoteConnectionProtocol.WEBDAV,
                "http://server:8080/dav",
            ),
        )
        assertEquals(
            "webdavs://server/dav/",
            RemoteConnectionLocation.normalize(
                RemoteConnectionProtocol.WEBDAVS,
                "https://server/dav",
            ),
        )
        assertFalse(
            RemoteConnectionLocation.isValid(
                RemoteConnectionProtocol.WEBDAVS,
                "http://server/dav",
            ),
        )
    }

    /** 校验当前 AWS S3 Provider 只接受 Bucket URI，不接受自定义端口或查询参数。 */
    @Test
    fun validatesCurrentS3LocationBoundary() {
        assertTrue(RemoteConnectionLocation.isValid(RemoteConnectionProtocol.S3, "bucket/prefix"))
        assertFalse(RemoteConnectionLocation.isValid(RemoteConnectionProtocol.S3, "s3://bucket:9000/prefix"))
        assertFalse(RemoteConnectionLocation.isValid(RemoteConnectionProtocol.S3, "s3://bucket/prefix?key=value"))
    }

    /** 校验 WebDAV 与 WebDAVS 切换仅调整安全 scheme，并保留同一认证族配置。 */
    @Test
    fun preservesConfigurationAcrossWebDavSecuritySwitch() {
        val draft = RemoteConnectionDraft(
            protocol = RemoteConnectionProtocol.WEBDAV,
            location = "webdav://server/dav/",
            username = "user",
            secret = "password",
            secretChanged = true,
        )

        val switched = RemoteConnectionLocation.switchProtocol(draft, RemoteConnectionProtocol.WEBDAVS)

        assertEquals("webdavs://server/dav/", switched.location)
        assertEquals("user", switched.username)
        assertEquals("password", switched.secret)
    }

    /** 校验跨认证协议族切换时清空地址和凭据，禁止隐式复用旧密钥。 */
    @Test
    fun clearsCredentialsAcrossProtocolFamilies() {
        val draft = RemoteConnectionDraft(
            protocol = RemoteConnectionProtocol.SMB,
            location = "smb://server/share/",
            username = "user",
            secret = "password",
            domain = "DOMAIN",
        )

        val switched = RemoteConnectionLocation.switchProtocol(draft, RemoteConnectionProtocol.S3)

        assertEquals("", switched.location)
        assertEquals("", switched.username)
        assertEquals("", switched.secret)
        assertEquals("", switched.domain)
        assertTrue(switched.secretChanged)
    }

    /** 校验新建 S3 连接必须提供完整 AWS 访问密钥。 */
    @Test
    fun requiresCompleteS3CredentialsForNewConnection() {
        val missingAccessKey = RemoteConnectionDraft(
            protocol = RemoteConnectionProtocol.S3,
            secret = "secret",
        )
        val missingSecret = RemoteConnectionDraft(
            protocol = RemoteConnectionProtocol.S3,
            username = "access-key",
        )

        assertEquals(
            RemoteConnectionDialogError.USERNAME_EMPTY,
            missingAccessKey.credentialValidationError(canReuseStoredSecret = false),
        )
        assertEquals(
            RemoteConnectionDialogError.SECRET_EMPTY,
            missingSecret.credentialValidationError(canReuseStoredSecret = false),
        )
        assertEquals(null, missingSecret.credentialValidationError(canReuseStoredSecret = true))
    }

    /** 校验 WebDAV 不会把隐藏的 SMB 域写入 Basic 认证元数据。 */
    @Test
    fun dropsDomainForWebDav() {
        val draft = RemoteConnectionDraft(
            protocol = RemoteConnectionProtocol.WEBDAVS,
            domain = "SHOULD_NOT_BE_USED",
        )

        assertEquals("", draft.domainForProtocol())
    }
}
