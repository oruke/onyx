package com.oruke.onyx.app.filesystem

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Windows Credential Manager JNA 结构体映射测试。
 */
class JvmWindowsCredentialManagerTest {
    /**
     * 校验 JNA 能通过反射访问凭据结构体字段。
     *
     * @return 无返回值。
     */
    @Test
    fun exposesCredentialStructureToJnaReflection() {
        val credential = JvmWindowsCredentialManager.Credential()

        credential.write()

        assertTrue(
            Modifier.isPublic(credential.javaClass.modifiers),
            "JNA Structure 的声明类必须在 JVM 字节码中公开可访问",
        )
    }
}
