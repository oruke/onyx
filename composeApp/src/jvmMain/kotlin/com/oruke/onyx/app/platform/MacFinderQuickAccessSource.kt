package com.oruke.onyx.app.platform

import com.oruke.onyx.core.model.SystemQuickAccessLocation
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.nio.file.Files
import java.nio.file.Path

/**
 * 通过 macOS CoreServices 读取 Finder 侧栏收藏位置。
 */
internal class MacFinderQuickAccessSource : SystemQuickAccessSource {
    /**
     * 读取 Finder Favorite Items 共享文件列表。
     *
     * @return Finder 收藏中的本地目录。
     */
    override fun loadLocations(): Result<List<SystemQuickAccessLocation>> = runCatching {
        val listType = coreServicesNativeLibrary()
            .getGlobalVariableAddress(MAC_FINDER_FAVORITES_SYMBOL)
            .getPointer(0L)
            ?: return@runCatching emptyList()
        val sharedFileList = macSharedFileListLibrary()
            .LSSharedFileListCreate(null, listType, null)
            ?: return@runCatching emptyList()
        try {
            readSharedFileList(sharedFileList)
        } finally {
            macCoreFoundationLibrary().CFRelease(sharedFileList)
        }
    }

    /**
     * 读取共享文件列表快照并转换每个 Finder 收藏项。
     *
     * @param sharedFileList Finder 收藏共享文件列表引用。
     * @return 可访问的本地收藏目录。
     */
    private fun readSharedFileList(sharedFileList: Pointer): List<SystemQuickAccessLocation> {
        val snapshot = macSharedFileListLibrary()
            .LSSharedFileListCopySnapshot(sharedFileList, null)
            ?: return emptyList()
        return try {
            val count = macCoreFoundationLibrary().CFArrayGetCount(snapshot).toInt().coerceAtLeast(0)
            buildList {
                repeat(count) { index ->
                    val item = macCoreFoundationLibrary()
                        .CFArrayGetValueAtIndex(snapshot, NativeLong(index.toLong()))
                        ?: return@repeat
                    resolveFinderFavorite(item)?.let(::add)
                }
            }
        } finally {
            macCoreFoundationLibrary().CFRelease(snapshot)
        }
    }

    /**
     * 解析 Finder 收藏项的显示名称和 POSIX 路径。
     *
     * @param item Finder 收藏项引用。
     * @return 收藏项指向真实目录时返回统一位置，否则返回 null。
     */
    private fun resolveFinderFavorite(item: Pointer): SystemQuickAccessLocation? {
        val displayName = macSharedFileListLibrary()
            .LSSharedFileListItemCopyDisplayName(item)
            ?.useCopiedReference(::readCoreFoundationString)
        val path = resolveFinderFavoritePath(item)?.takeIf(Files::isDirectory) ?: return null
        return SystemQuickAccessLocation(
            displayName = displayName?.takeIf(String::isNotBlank),
            location = path.toString(),
        )
    }

    /**
     * 将 Finder 收藏项解析为本地 POSIX 路径，且不触发交互或卷挂载。
     *
     * @param item Finder 收藏项引用。
     * @return 成功解析的绝对路径；不可解析时返回 null。
     */
    private fun resolveFinderFavoritePath(item: Pointer): Path? {
        val errorReference = PointerByReference()
        val resolvedUrl = macSharedFileListLibrary().LSSharedFileListItemCopyResolvedURL(
            item,
            MAC_SHARED_FILE_LIST_NO_UI or MAC_SHARED_FILE_LIST_DO_NOT_MOUNT,
            errorReference,
        )
        errorReference.value?.let(macCoreFoundationLibrary()::CFRelease)
        if (resolvedUrl == null) return null
        return try {
            macCoreFoundationLibrary()
                .CFURLCopyFileSystemPath(resolvedUrl, MAC_POSIX_PATH_STYLE)
                ?.useCopiedReference(::readCoreFoundationString)
                ?.let(Path::of)
                ?.normalize()
                ?.toAbsolutePath()
        } finally {
            macCoreFoundationLibrary().CFRelease(resolvedUrl)
        }
    }

    /**
     * 读取 Core Foundation UTF-8 字符串。
     *
     * @param stringReference CFStringRef 指针。
     * @return 对应 JVM 字符串。
     */
    private fun readCoreFoundationString(stringReference: Pointer): String {
        val library = macCoreFoundationLibrary()
        val length = library.CFStringGetLength(stringReference)
        val maxSize = library.CFStringGetMaximumSizeForEncoding(length, MAC_CF_STRING_ENCODING_UTF8)
        val bufferSize = maxSize.toLong() + 1L
        require(bufferSize > 0L) { "Finder 收藏名称长度无效" }
        val buffer = Memory(bufferSize)
        check(
            library.CFStringGetCString(
                stringReference,
                buffer,
                NativeLong(bufferSize),
                MAC_CF_STRING_ENCODING_UTF8,
            ).toInt() != 0
        ) { "Finder 收藏名称 UTF-8 转换失败" }
        return buffer.getString(0L, Charsets.UTF_8.name())
    }

    /**
     * 使用后释放遵循 Copy/Create 规则返回的 Core Foundation 引用。
     *
     * @param transform 在引用有效期间执行的读取函数。
     * @return 读取函数的结果。
     */
    private inline fun <T> Pointer.useCopiedReference(transform: (Pointer) -> T): T {
        return try {
            transform(this)
        } finally {
            macCoreFoundationLibrary().CFRelease(this)
        }
    }
}

/** macOS LSSharedFileList 原生函数映射。 */
internal interface MacSharedFileListLibrary : Library {
    /**
     * 创建指定类型的共享文件列表。
     *
     * @param allocator Core Foundation 分配器；null 使用默认值。
     * @param listType 共享文件列表类型常量。
     * @param options 可选创建参数。
     * @return 共享文件列表引用。
     */
    fun LSSharedFileListCreate(allocator: Pointer?, listType: Pointer, options: Pointer?): Pointer?

    /**
     * 复制共享文件列表当前快照。
     *
     * @param list 共享文件列表引用。
     * @param seedValue 可选变更种子输出。
     * @return CFArrayRef 快照。
     */
    fun LSSharedFileListCopySnapshot(list: Pointer, seedValue: IntByReference?): Pointer?

    /**
     * 复制共享文件项的 Finder 显示名称。
     *
     * @param item 共享文件项引用。
     * @return CFStringRef 名称。
     */
    fun LSSharedFileListItemCopyDisplayName(item: Pointer): Pointer?

    /**
     * 将共享文件项解析为文件 URL。
     *
     * @param item 共享文件项引用。
     * @param flags 禁止交互和挂载等解析标记。
     * @param error 可选 CFErrorRef 输出。
     * @return 解析后的 CFURLRef。
     */
    fun LSSharedFileListItemCopyResolvedURL(
        item: Pointer,
        flags: Int,
        error: PointerByReference?,
    ): Pointer?
}

/** macOS Core Foundation 本任务所需函数映射。 */
internal interface MacQuickAccessCoreFoundationLibrary : Library {
    /**
     * 读取数组元素数量。
     *
     * @param array CFArrayRef 引用。
     * @return 数组元素数。
     */
    fun CFArrayGetCount(array: Pointer): NativeLong

    /**
     * 按索引读取数组元素。
     *
     * @param array CFArrayRef 引用。
     * @param index 元素索引。
     * @return 元素引用。
     */
    fun CFArrayGetValueAtIndex(array: Pointer, index: NativeLong): Pointer?

    /**
     * 将文件 URL 转换为 POSIX 路径字符串。
     *
     * @param url CFURLRef 引用。
     * @param pathStyle Core Foundation 路径样式。
     * @return CFStringRef 路径。
     */
    fun CFURLCopyFileSystemPath(url: Pointer, pathStyle: Int): Pointer?

    /**
     * 读取 Core Foundation 字符串字符数。
     *
     * @param string CFStringRef 引用。
     * @return UTF-16 字符数。
     */
    fun CFStringGetLength(string: Pointer): NativeLong

    /**
     * 计算指定编码所需的最大字节数。
     *
     * @param length 字符串字符数。
     * @param encoding Core Foundation 字符编码。
     * @return 最大字节数。
     */
    fun CFStringGetMaximumSizeForEncoding(length: NativeLong, encoding: Int): NativeLong

    /**
     * 将 Core Foundation 字符串写入 C 字符缓冲区。
     *
     * @param string CFStringRef 引用。
     * @param buffer 目标缓冲区。
     * @param bufferSize 缓冲区大小。
     * @param encoding Core Foundation 字符编码。
     * @return 转换成功时返回非零值。
     */
    fun CFStringGetCString(
        string: Pointer,
        buffer: Pointer,
        bufferSize: NativeLong,
        encoding: Int,
    ): Byte

    /**
     * 释放遵循 Copy/Create 所有权规则的 Core Foundation 引用。
     *
     * @param value 待释放引用。
     */
    fun CFRelease(value: Pointer)
}

/**
 * 延迟加载 CoreServices 原生库，避免非 macOS 平台触发链接。
 *
 * @return CoreServices 原生库句柄。
 */
private fun coreServicesNativeLibrary(): NativeLibrary = MacQuickAccessLibraries.coreServices

/**
 * 延迟加载 Finder 共享文件列表函数。
 *
 * @return LSSharedFileList 函数映射。
 */
private fun macSharedFileListLibrary(): MacSharedFileListLibrary = MacQuickAccessLibraries.sharedFileList

/**
 * 延迟加载 Core Foundation 函数。
 *
 * @return Core Foundation 函数映射。
 */
private fun macCoreFoundationLibrary(): MacQuickAccessCoreFoundationLibrary = MacQuickAccessLibraries.coreFoundation

/** macOS 原生库的延迟初始化容器。 */
private object MacQuickAccessLibraries {
    /** CoreServices 动态库句柄。 */
    val coreServices: NativeLibrary by lazy { NativeLibrary.getInstance(MAC_CORE_SERVICES_LIBRARY) }

    /** Finder 共享文件列表函数映射。 */
    val sharedFileList: MacSharedFileListLibrary by lazy {
        Native.load(MAC_CORE_SERVICES_LIBRARY, MacSharedFileListLibrary::class.java)
    }

    /** Core Foundation 函数映射。 */
    val coreFoundation: MacQuickAccessCoreFoundationLibrary by lazy {
        Native.load(MAC_CORE_FOUNDATION_LIBRARY, MacQuickAccessCoreFoundationLibrary::class.java)
    }
}

/** CoreServices Framework 短名称。 */
private const val MAC_CORE_SERVICES_LIBRARY = "CoreServices"

/** CoreFoundation Framework 短名称。 */
private const val MAC_CORE_FOUNDATION_LIBRARY = "CoreFoundation"

/** Finder 收藏共享文件列表全局常量名称。 */
private const val MAC_FINDER_FAVORITES_SYMBOL = "kLSSharedFileListFavoriteItems"

/** 禁止解析 Finder 收藏时弹出用户交互。 */
private const val MAC_SHARED_FILE_LIST_NO_UI = 1

/** 禁止解析 Finder 收藏时自动挂载卷。 */
private const val MAC_SHARED_FILE_LIST_DO_NOT_MOUNT = 2

/** Core Foundation POSIX 路径样式。 */
private const val MAC_POSIX_PATH_STYLE = 0

/** Core Foundation UTF-8 编码常量。 */
private const val MAC_CF_STRING_ENCODING_UTF8 = 0x08000100
