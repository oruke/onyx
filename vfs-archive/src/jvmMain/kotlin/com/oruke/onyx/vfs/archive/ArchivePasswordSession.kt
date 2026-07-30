package com.oruke.onyx.vfs.archive

import java.util.concurrent.ConcurrentHashMap

/**
 * 管理当前进程内已验证的归档访问密码。
 *
 * 密码仅用于同一进程内后续的归档读取，不会写入配置、磁盘或日志。
 */
internal class ArchivePasswordSession {
    /** 按归档原始位置保存的已验证密码。 */
    private val passwords = ConcurrentHashMap<String, String>()

    /**
     * 查询指定归档的当前会话密码。
     *
     * @param archivePath 归档在统一 VFS 中的原始位置。
     * @return 已验证的会话密码；不存在时返回 `null`。
     */
    fun passwordFor(archivePath: String): String? = passwords[archivePath]

    /**
     * 判断指定归档是否已有可复用的会话密码。
     *
     * @param archivePath 归档在统一 VFS 中的原始位置。
     * @return 已保存密码时返回 `true`。
     */
    fun hasPassword(archivePath: String): Boolean = passwords.containsKey(archivePath)

    /**
     * 记录已完成验证的归档密码。
     *
     * @param archivePath 归档在统一 VFS 中的原始位置。
     * @param password 已由归档引擎校验通过的密码。
     * @return 无返回值。
     */
    fun remember(archivePath: String, password: String) {
        passwords[archivePath] = password
    }

    /**
     * 移除指定归档的会话密码。
     *
     * @param archivePath 归档在统一 VFS 中的原始位置。
     * @return 无返回值。
     */
    fun forget(archivePath: String) {
        passwords.remove(archivePath)
    }
}
