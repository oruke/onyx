package com.oruke.onyx.app.filesystem

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.mac.CoreFoundation
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * macOS Keychain 原生访问器，通过 Security.framework 保存通用密码。
 *
 * 密钥只以内存缓冲传给原生 API，不进入命令行参数或临时文件。
 */
internal class JvmMacOsKeychainManager {
    /** 当前宿主是否为 macOS。 */
    private val isMacOs = System.getProperty("os.name").lowercase(Locale.ROOT).let { osName ->
        osName.contains("mac") || osName.contains("darwin")
    }

    /** 延迟加载的 Security.framework 接口，非 macOS 平台不会尝试加载。 */
    private val security: MacOsSecurityLibrary? by lazy {
        if (!isMacOs) {
            null
        } else {
            runCatching {
                Native.load(MACOS_SECURITY_FRAMEWORK, MacOsSecurityLibrary::class.java)
            }.getOrNull()
        }
    }

    /**
     * 检查当前进程是否可访问 macOS Security.framework。
     *
     * @return 原生钥匙串接口可用时返回 true。
     */
    fun isAvailable(): Boolean = security != null

    /**
     * 读取指定服务和账户的通用密码。
     *
     * @param serviceName 钥匙串服务名。
     * @param accountName 钥匙串账户名。
     * @return 解密后的密码；未找到或读取失败时返回空。
     */
    fun read(serviceName: String, accountName: String): String? {
        val library = security
        return if (library == null) {
            null
        } else {
            readSecret(library, serviceName, accountName)
        }
    }

    /**
     * 调用 Security.framework 读取已确认可用的钥匙串。
     *
     * @param library Security.framework 接口。
     * @param serviceName 钥匙串服务名。
     * @param accountName 钥匙串账户名。
     * @return 解密后的密码；未找到时返回空。
     */
    private fun readSecret(
        library: MacOsSecurityLibrary,
        serviceName: String,
        accountName: String,
    ): String? {
        val serviceBytes = serviceName.toByteArray(StandardCharsets.UTF_8)
        val accountBytes = accountName.toByteArray(StandardCharsets.UTF_8)
        val passwordLength = IntByReference()
        val passwordData = PointerByReference()
        val status = library.SecKeychainFindGenericPassword(
            null,
            serviceBytes.size,
            serviceBytes,
            accountBytes.size,
            accountBytes,
            passwordLength,
            passwordData,
            null,
        )
        val pointer = passwordData.value
        return if (status != ERR_SEC_SUCCESS || pointer == null) {
            null
        } else {
            try {
                String(pointer.getByteArray(0, passwordLength.value), StandardCharsets.UTF_8)
            } finally {
                library.SecKeychainItemFreeContent(null, pointer)
            }
        }
    }

    /**
     * 新增或更新指定服务和账户的通用密码。
     *
     * @param serviceName 钥匙串服务名。
     * @param accountName 钥匙串账户名。
     * @param secret 需要保存的密钥。
     * @return 写入成功时返回 true。
     */
    fun write(serviceName: String, accountName: String, secret: String): Boolean {
        val library = security
        return library != null && writeSecret(library, serviceName, accountName, secret)
    }

    /**
     * 调用 Security.framework 新增或更新凭据。
     *
     * @param library Security.framework 接口。
     * @param serviceName 钥匙串服务名。
     * @param accountName 钥匙串账户名。
     * @param secret 待保存密钥。
     * @return 写入成功时返回 true。
     */
    private fun writeSecret(
        library: MacOsSecurityLibrary,
        serviceName: String,
        accountName: String,
        secret: String,
    ): Boolean {
        val serviceBytes = serviceName.toByteArray(StandardCharsets.UTF_8)
        val accountBytes = accountName.toByteArray(StandardCharsets.UTF_8)
        val secretBytes = secret.toByteArray(StandardCharsets.UTF_8)
        val itemReference = findItemReference(library, serviceBytes, accountBytes)
        return itemReference?.useReference { itemPointer ->
            library.SecKeychainItemModifyAttributesAndData(
                itemPointer,
                null,
                secretBytes.size,
                secretBytes,
            ) == ERR_SEC_SUCCESS
        } ?: (library.SecKeychainAddGenericPassword(
            null,
            serviceBytes.size,
            serviceBytes,
            accountBytes.size,
            accountBytes,
            secretBytes.size,
            secretBytes,
            null,
        ) == ERR_SEC_SUCCESS)
    }

    /**
     * 删除指定服务和账户的通用密码。
     *
     * @param serviceName 钥匙串服务名。
     * @param accountName 钥匙串账户名。
     * @return 删除成功或条目原本不存在时返回 true。
     */
    fun delete(serviceName: String, accountName: String): Boolean {
        val library = security
        return if (library == null) {
            false
        } else {
            val serviceBytes = serviceName.toByteArray(StandardCharsets.UTF_8)
            val accountBytes = accountName.toByteArray(StandardCharsets.UTF_8)
            findItemReference(library, serviceBytes, accountBytes)?.useReference { itemPointer ->
                library.SecKeychainItemDelete(itemPointer) == ERR_SEC_SUCCESS
            } ?: true
        }
    }

    /**
     * 查找指定服务和账户对应的钥匙串条目引用。
     *
     * @param library Security.framework 接口。
     * @param serviceBytes UTF-8 服务名。
     * @param accountBytes UTF-8 账户名。
     * @return 需要由调用方释放的条目引用；未找到时返回空。
     */
    private fun findItemReference(
        library: MacOsSecurityLibrary,
        serviceBytes: ByteArray,
        accountBytes: ByteArray,
    ): KeychainItemReference? {
        val itemReference = PointerByReference()
        val status = library.SecKeychainFindGenericPassword(
            null,
            serviceBytes.size,
            serviceBytes,
            accountBytes.size,
            accountBytes,
            null,
            null,
            itemReference,
        )
        return when (status) {
            ERR_SEC_SUCCESS -> itemReference.value?.let(::KeychainItemReference)
            ERR_SEC_ITEM_NOT_FOUND -> null
            else -> null
        }
    }

    /** Security.framework 的 JNA 函数映射。 */
    private interface MacOsSecurityLibrary : Library {
        /** 查找通用密码或对应条目引用。 */
        fun SecKeychainFindGenericPassword(
            keychainOrArray: Pointer?,
            serviceNameLength: Int,
            serviceName: ByteArray,
            accountNameLength: Int,
            accountName: ByteArray,
            passwordLength: IntByReference?,
            passwordData: PointerByReference?,
            itemReference: PointerByReference?,
        ): Int

        /** 新增通用密码。 */
        fun SecKeychainAddGenericPassword(
            keychain: Pointer?,
            serviceNameLength: Int,
            serviceName: ByteArray,
            accountNameLength: Int,
            accountName: ByteArray,
            passwordLength: Int,
            passwordData: ByteArray,
            itemReference: PointerByReference?,
        ): Int

        /** 更新已有条目的密码数据。 */
        fun SecKeychainItemModifyAttributesAndData(
            itemReference: Pointer,
            attributes: Pointer?,
            dataLength: Int,
            data: ByteArray,
        ): Int

        /** 删除钥匙串条目。 */
        fun SecKeychainItemDelete(itemReference: Pointer): Int

        /** 释放读取密码时由 Security.framework 分配的内容缓冲。 */
        fun SecKeychainItemFreeContent(attributes: Pointer?, data: Pointer?): Int
    }

    /** 负责释放 CoreFoundation 条目引用的轻量包装。 */
    private class KeychainItemReference(
        /** 原生 SecKeychainItemRef 指针。 */
        private val pointer: Pointer,
    ) {
        /**
         * 使用条目引用并在结束后释放 CoreFoundation 所有权。
         *
         * @param block 使用原生条目指针的代码块。
         * @return 代码块结果。
         */
        fun <T> useReference(block: (Pointer) -> T): T {
            return try {
                block(pointer)
            } finally {
                CoreFoundation.CFTypeRef(pointer).release()
            }
        }
    }

    private companion object {
        /** macOS Security.framework 动态库路径。 */
        const val MACOS_SECURITY_FRAMEWORK = "/System/Library/Frameworks/Security.framework/Security"

        /** Security.framework 成功状态码。 */
        const val ERR_SEC_SUCCESS = 0

        /** Security.framework 条目不存在状态码。 */
        const val ERR_SEC_ITEM_NOT_FOUND = -25_300
    }
}
