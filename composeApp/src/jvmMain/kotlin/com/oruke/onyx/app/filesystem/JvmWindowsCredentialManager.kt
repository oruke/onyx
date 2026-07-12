package com.oruke.onyx.app.filesystem

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.nio.charset.StandardCharsets

/**
 * Windows Credential Manager 的最小封装，用于远程连接凭据的系统级保存。
 *
 * 只使用 Generic Credential，目标名由上层生成，凭据内容按 UTF-16LE 写入 `CredentialBlob`。
 */
internal class JvmWindowsCredentialManager {
    /**
     * 判断当前进程是否能访问 Windows Credential Manager。
     *
     * @return `true` 表示 advapi32 凭据 API 可加载。
     */
    fun isAvailable(): Boolean {
        return runCatching { WinCred.INSTANCE }.isSuccess
    }

    /**
     * 读取指定目标名的凭据内容。
     *
     * @param targetName Credential Manager 中的目标名。
     * @return 已保存的凭据内容；不存在或读取失败返回 `null`。
     */
    fun read(targetName: String): String? {
        val credentialRef = PointerByReference()
        val read = WinCred.INSTANCE.CredReadW(
            targetName = WString(targetName),
            type = CRED_TYPE_GENERIC,
            flags = 0,
            credential = credentialRef,
        )
        val pointer = credentialRef.value.takeIf { read }
        return pointer?.let(::readCredential)
    }

    /**
     * 从系统分配的原生指针读取凭据，并确保内存最终释放。
     *
     * @param pointer `CredReadW` 返回的凭据结构指针。
     * @return UTF-16LE 凭据内容；凭据没有内容时返回 `null`。
     */
    private fun readCredential(pointer: Pointer): String? {
        return try {
            val credential = Credential(pointer).apply { read() }
            credential.CredentialBlob?.let { blobPointer ->
                val bytes = blobPointer.getByteArray(0, credential.CredentialBlobSize)
                String(bytes, StandardCharsets.UTF_16LE).trimEnd('\u0000')
            }
        } finally {
            WinCred.INSTANCE.CredFree(pointer)
        }
    }

    /**
     * 写入或更新指定目标名的凭据内容。
     *
     * @param targetName Credential Manager 中的目标名。
     * @param userName 显示在系统凭据条目里的用户名。
     * @param secret 需要保存的序列化凭据内容。
     * @return `true` 表示写入成功。
     */
    fun write(
        targetName: String,
        userName: String,
        secret: String,
    ): Boolean {
        val bytes = (secret + "\u0000").toByteArray(StandardCharsets.UTF_16LE)
        val blob = Memory(bytes.size.toLong()).apply {
            write(0, bytes, 0, bytes.size)
        }
        val credential = Credential().apply {
            Type = CRED_TYPE_GENERIC
            TargetName = WString(targetName)
            CredentialBlobSize = bytes.size
            CredentialBlob = blob
            Persist = CRED_PERSIST_LOCAL_MACHINE
            UserName = WString(userName)
            write()
        }
        return WinCred.INSTANCE.CredWriteW(credential, 0)
    }

    /**
     * 删除指定目标名的凭据。
     *
     * @param targetName Credential Manager 中的目标名。
     * @return `true` 表示删除成功或凭据已经不存在。
     */
    fun delete(targetName: String): Boolean {
        return WinCred.INSTANCE.CredDeleteW(
            targetName = WString(targetName),
            type = CRED_TYPE_GENERIC,
            flags = 0,
        ) || Native.getLastError() == ERROR_NOT_FOUND
    }

    /**
     * Windows `CREDENTIALW` 结构体映射。
     *
     * JNA 会从 `Structure` 包外反射访问字段，因此声明类不能使用 JVM 私有可见性。
     * 字段名称必须逐字匹配 Windows SDK，不能改为 Kotlin 小驼峰命名。
     */
    @Structure.FieldOrder(
        "Flags",
        "Type",
        "TargetName",
        "Comment",
        "LastWritten",
        "CredentialBlobSize",
        "CredentialBlob",
        "Persist",
        "AttributeCount",
        "Attributes",
        "TargetAlias",
        "UserName",
    )
    @Suppress("VariableNaming")
    internal class Credential : Structure {
        /** Windows 凭据标记。 */
        @JvmField var Flags: Int = 0
        /** 凭据类型，本实现固定使用 Generic Credential。 */
        @JvmField var Type: Int = CRED_TYPE_GENERIC
        /** 系统凭据目标名称。 */
        @JvmField var TargetName: WString? = null
        /** 可选的凭据注释。 */
        @JvmField var Comment: WString? = null
        /** 系统维护的最后写入时间。 */
        @JvmField var LastWritten: WinBase.FILETIME = WinBase.FILETIME()
        /** 凭据内容的字节长度。 */
        @JvmField var CredentialBlobSize: Int = 0
        /** 凭据内容的原生内存指针。 */
        @JvmField var CredentialBlob: Pointer? = null
        /** 凭据持久化范围。 */
        @JvmField var Persist: Int = CRED_PERSIST_LOCAL_MACHINE
        /** 附加属性数量。 */
        @JvmField var AttributeCount: Int = 0
        /** 附加属性数组指针。 */
        @JvmField var Attributes: Pointer? = null
        /** 可选的目标别名。 */
        @JvmField var TargetAlias: WString? = null
        /** 凭据条目显示的用户名。 */
        @JvmField var UserName: WString? = null

        /**
         * 创建用于写入的空结构体。
         */
        constructor() : super()

        /**
         * 从原生指针创建用于读取的结构体。
         *
         * @param pointer `CredReadW` 返回的结构体指针。
         */
        constructor(pointer: Pointer) : super(pointer)
    }

    /**
     * advapi32 凭据 API。
     */
    private interface WinCred : StdCallLibrary {
        /**
         * 读取 Generic Credential。
         *
         * @param targetName 目标名。
         * @param type 凭据类型。
         * @param flags 调用标记。
         * @param credential 输出凭据结构体指针。
         * @return 是否读取成功。
         */
        fun CredReadW(
            targetName: WString,
            type: Int,
            flags: Int,
            credential: PointerByReference,
        ): Boolean

        /**
         * 写入 Generic Credential。
         *
         * @param credential 凭据结构体。
         * @param flags 调用标记。
         * @return 是否写入成功。
         */
        fun CredWriteW(
            credential: Credential,
            flags: Int,
        ): Boolean

        /**
         * 删除 Generic Credential。
         *
         * @param targetName 目标名。
         * @param type 凭据类型。
         * @param flags 调用标记。
         * @return 是否删除成功。
         */
        fun CredDeleteW(
            targetName: WString,
            type: Int,
            flags: Int,
        ): Boolean

        /**
         * 释放 `CredReadW` 返回的内存。
         *
         * @param buffer 需要释放的原生指针。
         */
        fun CredFree(buffer: Pointer)

        companion object {
            val INSTANCE: WinCred = Native.load(
                "Advapi32",
                WinCred::class.java,
                W32APIOptions.UNICODE_OPTIONS,
            )
        }
    }

    private companion object {
        const val CRED_TYPE_GENERIC = 1
        const val CRED_PERSIST_LOCAL_MACHINE = 2
        const val ERROR_NOT_FOUND = 1168
    }
}
