package com.oruke.onyx.app.cache

import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinReg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchService
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.math.min

/** 平台菜单来源监听服务，用于在系统菜单来源变化时唤醒缓存失效检查。 */
internal class PlatformMenuSourceWatchService(
    /** 返回当前宿主平台标识的函数。 */
    private val platformProvider: () -> String,

    /** 返回当前平台可监听菜单来源目录的函数。 */
    private val directoryProvider: () -> List<Path>,

    /** 返回当前平台可监听注册表键路径的函数。 */
    private val registryKeyProvider: () -> List<String>,
) {
    /**
     * 使用指定目录提供器创建文件系统监听器，主要用于单元测试。
     *
     * @param directoryProvider 返回测试目录集合的函数。
     */
    constructor(directoryProvider: () -> List<Path>) : this(
        platformProvider = { PLATFORM_OTHER },
        directoryProvider = directoryProvider,
        registryKeyProvider = { emptyList() },
    )

    /**
     * 使用平台菜单来源指纹服务创建监听器。
     *
     * @param fingerprintService 提供平台来源目录的指纹服务。
     */
    constructor(fingerprintService: PlatformMenuSourceFingerprintService) : this(
        platformProvider = fingerprintService::currentPlatform,
        directoryProvider = fingerprintService::currentWatchDirectories,
        registryKeyProvider = fingerprintService::currentRegistryWatchKeyPaths,
    )

    /**
     * 等待菜单来源发生系统变化。
     *
     * Windows 使用注册表原生通知；Linux/macOS 使用文件系统目录事件；没有可监听源时会等待同样的超时时间。
     *
     * @param timeoutMillis 本轮最长等待时间，单位毫秒。
     * @return `true` 表示监听到目录变化，`false` 表示超时或当前平台无可用文件监听源。
     */
    suspend fun awaitChange(timeoutMillis: Long): Boolean {
        if (timeoutMillis <= 0L) return false
        if (platformProvider() == PLATFORM_WINDOWS) {
            return awaitRegistryChange(timeoutMillis)
        }
        return awaitDirectoryChange(timeoutMillis)
    }

    /**
     * 等待文件系统目录变化。
     *
     * @param timeoutMillis 本轮最长等待时间，单位毫秒。
     * @return `true` 表示监听到目录变化。
     */
    private suspend fun awaitDirectoryChange(timeoutMillis: Long): Boolean {
        val sourceDirectories = directoryProvider().distinctBy { path -> path.toAbsolutePath().normalize() }
        if (sourceDirectories.isEmpty()) {
            delay(timeoutMillis)
            return false
        }
        val watchResult = runCatching {
            withContext(Dispatchers.IO) {
                awaitWatchEvent(sourceDirectories, timeoutMillis)
            }
        }.getOrNull()
        if (watchResult == null) {
            delay(timeoutMillis)
        }
        return watchResult == true
    }

    /**
     * 等待 Windows 注册表菜单来源变化。
     *
     * @param timeoutMillis 本轮最长等待时间，单位毫秒。
     * @return `true` 表示注册表键发生变化。
     */
    private suspend fun awaitRegistryChange(timeoutMillis: Long): Boolean {
        val registryKeys = registryKeyProvider().distinct()
        if (registryKeys.isEmpty()) {
            delay(timeoutMillis)
            return false
        }
        val watchResult = runCatching {
            withContext(Dispatchers.IO) {
                awaitRegistryEvent(registryKeys, timeoutMillis)
            }
        }.getOrNull()
        if (watchResult == null) {
            delay(timeoutMillis)
        }
        return watchResult == true
    }

    /**
     * 创建一次性 WatchService 并等待任意有效目录事件。
     *
     * @param sourceDirectories 需要监听的菜单来源目录。
     * @param timeoutMillis 本轮最长等待时间，单位毫秒。
     * @return `true` 表示有变化，`false` 表示超时，`null` 表示没有成功注册任何目录。
     */
    private suspend fun awaitWatchEvent(
        sourceDirectories: List<Path>,
        timeoutMillis: Long,
    ): Boolean? {
        FileSystems.getDefault().newWatchService().use { watchService ->
            val registeredCount = registerWatchDirectories(watchService, sourceDirectories)
            if (registeredCount == 0) return null

            val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (currentCoroutineContext().isActive) {
                val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
                if (remainingMillis <= 0L) return false
                val key = watchService.poll(
                    min(remainingMillis, WATCH_POLL_SLICE_MILLIS),
                    TimeUnit.MILLISECONDS,
                ) ?: continue
                val changed = key.pollEvents().any { event -> event.kind() != OVERFLOW }
                key.reset()
                if (changed) return true
            }
        }
        return false
    }

    /**
     * 注册 Windows 右键菜单相关注册表键并等待任意键变化。
     *
     * @param registryKeyPaths 注册表键路径列表。
     * @param timeoutMillis 本轮最长等待时间，单位毫秒。
     * @return `true` 表示注册表发生变化，`false` 表示超时，`null` 表示没有成功注册任何键。
     */
    private suspend fun awaitRegistryEvent(
        registryKeyPaths: List<String>,
        timeoutMillis: Long,
    ): Boolean? {
        val registrations = mutableListOf<WindowsRegistryWatchRegistration>()
        try {
            registryKeyPaths.mapNotNull(::parseWindowsRegistryKey).forEach { key ->
                openWindowsRegistryWatch(key)?.let(registrations::add)
            }
            if (registrations.isEmpty()) return null

            val eventHandles = registrations.map { registration -> registration.eventHandle }.toTypedArray()
            val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (currentCoroutineContext().isActive) {
                val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
                if (remainingMillis <= 0L) return false
                val waitResult = Kernel32.INSTANCE.WaitForMultipleObjects(
                    eventHandles.size,
                    eventHandles,
                    false,
                    min(remainingMillis, WATCH_POLL_SLICE_MILLIS).toInt(),
                )
                when {
                    waitResult in WinBase.WAIT_OBJECT_0 until WinBase.WAIT_OBJECT_0 + eventHandles.size -> return true
                    waitResult == WinError.WAIT_TIMEOUT -> continue
                    waitResult == WinBase.WAIT_FAILED -> return null
                    else -> return null
                }
            }
        } finally {
            registrations.forEach(WindowsRegistryWatchRegistration::close)
        }
        return false
    }

    /**
     * 打开一个 Windows 注册表键并注册异步变更事件。
     *
     * @param key 需要监听的注册表键。
     * @return 成功注册后的键句柄和事件句柄；失败时返回 `null`。
     */
    private fun openWindowsRegistryWatch(key: WindowsRegistryWatchKey): WindowsRegistryWatchRegistration? {
        val keyReference = WinReg.HKEYByReference()
        val openResult = Advapi32.INSTANCE.RegOpenKeyEx(
            key.root,
            key.subKey,
            0,
            WinNT.KEY_NOTIFY,
            keyReference,
        )
        if (openResult != WinError.ERROR_SUCCESS) return null
        val keyHandle = keyReference.value
        val eventHandle = Kernel32.INSTANCE.CreateEvent(null, false, false, null)
        if (eventHandle == null) {
            Advapi32.INSTANCE.RegCloseKey(keyHandle)
            return null
        }
        val notifyResult = Advapi32.INSTANCE.RegNotifyChangeKeyValue(
            keyHandle,
            true,
            WINDOWS_REGISTRY_NOTIFY_FILTER,
            eventHandle,
            true,
        )
        if (notifyResult != WinError.ERROR_SUCCESS) {
            Advapi32.INSTANCE.RegCloseKey(keyHandle)
            Kernel32.INSTANCE.CloseHandle(eventHandle)
            return null
        }
        return WindowsRegistryWatchRegistration(keyHandle = keyHandle, eventHandle = eventHandle)
    }

    /**
     * 解析常见 Windows 注册表根键路径。
     *
     * @param keyPath 形如 `HKCU\Software\Classes\*\shell` 的注册表键路径。
     * @return 可传给 Win32 API 的根键与子路径；无法识别时返回 `null`。
     */
    private fun parseWindowsRegistryKey(keyPath: String): WindowsRegistryWatchKey? {
        val rootName = keyPath.substringBefore('\\', missingDelimiterValue = keyPath)
        val subKey = keyPath.substringAfter('\\', missingDelimiterValue = "")
        val root = when (rootName.uppercase(Locale.ROOT)) {
            "HKCR", "HKEY_CLASSES_ROOT" -> WinReg.HKEY_CLASSES_ROOT
            "HKCU", "HKEY_CURRENT_USER" -> WinReg.HKEY_CURRENT_USER
            "HKLM", "HKEY_LOCAL_MACHINE" -> WinReg.HKEY_LOCAL_MACHINE
            "HKU", "HKEY_USERS" -> WinReg.HKEY_USERS
            else -> return null
        }
        return WindowsRegistryWatchKey(root = root, subKey = subKey)
    }

    /**
     * 注册来源目录及其有限层级子目录。
     *
     * @param watchService 本轮文件系统监听器。
     * @param sourceDirectories 平台菜单来源目录。
     * @return 成功注册的目录数量。
     */
    private fun registerWatchDirectories(
        watchService: WatchService,
        sourceDirectories: List<Path>,
    ): Int {
        return sourceDirectories
            .flatMap(::collectWatchDirectories)
            .distinctBy { path -> path.toAbsolutePath().normalize() }
            .count { directory ->
                runCatching {
                    directory.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
                }.isSuccess
            }
    }

    /**
     * 收集一个来源目录下需要监听的现有目录。
     *
     * WatchService 按目录注册，递归注册能覆盖 KDE ServiceMenu、macOS `.app` 内部 Services 等嵌套来源。
     *
     * @param sourceDirectory 平台菜单来源根目录。
     * @return 可注册监听的目录集合。
     */
    private fun collectWatchDirectories(sourceDirectory: Path): List<Path> {
        if (!sourceDirectory.exists() || !sourceDirectory.isDirectory()) return emptyList()
        return runCatching {
            Files.walk(sourceDirectory, MAX_WATCH_DEPTH)
                .use { stream ->
                    stream
                        .filter { path -> path.isDirectory() }
                        .limit(MAX_WATCH_DIRECTORIES)
                        .collect(Collectors.toList())
                }
        }.getOrDefault(listOf(sourceDirectory))
    }

    private companion object {
        /** Windows 平台标识。 */
        const val PLATFORM_WINDOWS = "windows"

        /** 非 Windows 测试平台标识。 */
        const val PLATFORM_OTHER = "other"

        /** 单次监听最多等待的阻塞分片，保证协程取消时不会卡住整段轮询周期。 */
        const val WATCH_POLL_SLICE_MILLIS = 1_000L

        /** 递归注册监听的最大目录深度。 */
        const val MAX_WATCH_DEPTH = 4

        /** 单轮最多注册的监听目录数，避免大型 Applications 目录导致后台开销过高。 */
        const val MAX_WATCH_DIRECTORIES = 256L

        /** 注册表变更过滤器，覆盖右键菜单键的创建、删除与命令值变化。 */
        const val WINDOWS_REGISTRY_NOTIFY_FILTER = WinNT.REG_NOTIFY_CHANGE_NAME or WinNT.REG_NOTIFY_CHANGE_LAST_SET
    }

    /**
     * Windows 注册表监听目标。
     */
    private data class WindowsRegistryWatchKey(
        /** 注册表根键句柄。 */
        val root: WinReg.HKEY,

        /** 根键下的相对路径。 */
        val subKey: String,
    )

    /**
     * 已注册的 Windows 注册表监听句柄组。
     */
    private data class WindowsRegistryWatchRegistration(
        /** 已打开的注册表键句柄。 */
        val keyHandle: WinReg.HKEY,

        /** 注册表变化事件句柄。 */
        val eventHandle: WinNT.HANDLE,
    ) {
        /**
         * 释放注册表键句柄与事件句柄。
         */
        fun close() {
            Advapi32.INSTANCE.RegCloseKey(keyHandle)
            Kernel32.INSTANCE.CloseHandle(eventHandle)
        }
    }
}
