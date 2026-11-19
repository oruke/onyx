package com.oruke.onyx.shared.usecase

/**
 * 将搜索目录位置解析为用于防重复扫描与符号链接回环检测的规范键。
 *
 * 在 JVM 本地文件系统上会在 I/O 调度器优先解析真实物理路径（跟随符号链接/联接），
 * 无法解析或属于远程/虚拟协议时回退为规范化的路径字符串。
 *
 * @param location 待解析的目录位置。
 * @return 用于去重的规范目录键。
 */
internal expect suspend fun resolveCanonicalSearchDirectoryKey(location: String): String

/**
 * 将协议路径或不可解析的本地路径规范化为统一的去重键。
 *
 * @param location 原始位置。
 * @return 规范化后的去重键。
 */
internal fun normalizeFallbackSearchDirectoryKey(location: String): String {
    val trimmed = location.trim()
    val schemeIndex = trimmed.indexOf("://")
    if (schemeIndex >= 0) {
        val scheme = trimmed.substring(0, schemeIndex).lowercase()
        val remainder = trimmed.substring(schemeIndex + 3).replace('\\', '/').trimEnd('/')
        return "$scheme://$remainder"
    }
    val unified = trimmed.replace('\\', '/')
    return if (unified.length > 1) unified.trimEnd('/') else unified
}
