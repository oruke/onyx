package com.oruke.onyx.app.platform

import com.oruke.onyx.app.OnyxLogger
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 单个 Windows `.lnk` 快捷方式的 `AppUserModelID` 对齐结果。
 */
internal enum class ShortcutReconcileOutcome {
    /** 快捷方式文件不存在或不可读。 */
    MISSING,

    /** 快捷方式目标不指向预期的可执行文件，安全跳过不修改。 */
    SKIPPED_TARGET_MISMATCH,

    /** 快捷方式已具有匹配的 `AppUserModelID`，无需重复写盘。 */
    ALREADY_ALIGNED,

    /** 已成功补齐 `AppUserModelID` 并落盘通知 Shell 刷新。 */
    UPDATED,

    /** 非 Windows 平台或底层 COM 调用失败。 */
    FAILED,
}

/**
 * Windows 快捷方式 `System.AppUserModel.ID` 自愈对齐器。
 *
 * `jpackage` 生成的 MSI 开始菜单快捷方式以及用户早期固定到任务栏的 `Onyx.lnk` 默认不包含显式 `AppUserModelID`。
 * 根据 Microsoft Win32 任务栏规范，当进程与窗口启用显式 `AppUserModelID` 后，关联快捷方式也必须声明相同的 ID，
 * 否则从固定图标启动应用时固定按钮与运行窗口将分裂为两个任务栏图标。
 *
 * 本组件在真实交互式桌面且宿主为打包版 `Onyx.exe` 时，于后台线程对指向当前 `Onyx.exe` 的快捷方式执行幂等补齐。
 */
internal object WindowsShortcutAppIdReconciler {

    /**
     * 在后台守护线程异步对齐当前用户标准位置下指向 `Onyx.exe` 的快捷方式。
     *
     * 非 Windows 宿主、沙箱测试桌面或开发态 `java.exe` 进程均立即安全跳过。
     */
    fun reconcileInstalledShortcutsAsync() {
        if (!WindowsTaskbarGroupingService.isWindowsHost || isSandboxedWindowsDesktop()) return
        val executablePath = WindowsTaskbarGroupingService.resolvePackagedExecutablePath() ?: return
        val candidates = defaultShortcutCandidates()
        if (candidates.isEmpty()) return
        thread(
            start = true,
            isDaemon = true,
            name = "onyx-windows-shortcut-appid-reconciler",
        ) {
            var updatedCount = 0
            candidates.forEach { shortcut ->
                val outcome = reconcileShortcut(
                    shortcutPath = shortcut,
                    expectedExecutablePath = executablePath,
                    appUserModelId = WindowsTaskbarGroupingService.ONYX_APP_USER_MODEL_ID,
                )
                if (outcome == ShortcutReconcileOutcome.UPDATED) {
                    updatedCount++
                }
            }
            if (updatedCount > 0) {
                OnyxLogger.info("Taskbar", "已对齐 $updatedCount 个 Onyx 快捷方式的 AppUserModelID")
            }
        }
    }

    /**
     * 校验并在必要时更新单个 `.lnk` 快捷方式的 `System.AppUserModel.ID`。
     *
     * @param shortcutPath `.lnk` 文件路径。
     * @param expectedExecutablePath 期望匹配的快捷方式目标可执行文件路径。
     * @param appUserModelId 待写入的目标 `AppUserModelID`。
     * @return 对齐执行结果 [ShortcutReconcileOutcome]。
     */
    fun reconcileShortcut(
        shortcutPath: Path,
        expectedExecutablePath: String,
        appUserModelId: String = WindowsTaskbarGroupingService.ONYX_APP_USER_MODEL_ID,
    ): ShortcutReconcileOutcome {
        if (!WindowsTaskbarGroupingService.isWindowsHost) return ShortcutReconcileOutcome.FAILED
        if (!Files.isRegularFile(shortcutPath)) return ShortcutReconcileOutcome.MISSING
        val normalizedExpected = normalizeWindowsPath(expectedExecutablePath)
        if (normalizedExpected.isEmpty() || appUserModelId.isBlank()) return ShortcutReconcileOutcome.FAILED

        return runCatching {
            withWindowsComInitialized {
                reconcileShortcutInCom(shortcutPath, normalizedExpected, appUserModelId)
            }
        }.onFailure { failure ->
            OnyxLogger.warn("Taskbar", "对齐快捷方式 AppUserModelID 失败: $shortcutPath", failure)
        }.getOrDefault(ShortcutReconcileOutcome.FAILED)
    }

    /**
     * 读取指定 `.lnk` 快捷方式当前的 `System.AppUserModel.ID`（供单元测试验证）。
     *
     * @param shortcutPath `.lnk` 文件路径。
     * @return 已存储的 `AppUserModelID`；未设置或读取失败时返回 `null`。
     */
    internal fun readShortcutAppUserModelId(shortcutPath: Path): String? {
        if (!WindowsTaskbarGroupingService.isWindowsHost || !Files.isRegularFile(shortcutPath)) return null
        return runCatching {
            withWindowsComInitialized {
                withShellLink { shellLink ->
                    shellLink.queryPersistFile()?.use { persistFile ->
                        if (!persistFile.load(shortcutPath, STGM_READ)) return@withShellLink null
                        shellLink.queryPropertyStore()?.use { store ->
                            store.getStringValue(WindowsPropertyStore.PKEY_APP_USER_MODEL_ID)
                        }
                    }
                }
            }
        }.getOrNull()
    }

    /**
     * 创建一个指向指定目标路径的标准 `.lnk` 快捷方式（仅供单元测试构造真实 ShellLink 夹具）。
     *
     * @param shortcutPath 待生成的 `.lnk` 文件路径。
     * @param targetExecutablePath 快捷方式指向的可执行文件路径。
     * @param initialAppUserModelId 可选的初始 `AppUserModelID`。
     * @return 创建成功时返回 `true`。
     */
    internal fun createShortcutForTest(
        shortcutPath: Path,
        targetExecutablePath: Path,
        initialAppUserModelId: String? = null,
    ): Boolean {
        if (!WindowsTaskbarGroupingService.isWindowsHost) return false
        return runCatching {
            shortcutPath.parent?.let(Files::createDirectories)
            withWindowsComInitialized {
                withShellLink { shellLink ->
                    val targetAssigned = shellLink.setPath(
                        targetExecutablePath.toAbsolutePath().normalize().toString(),
                    )
                    val appIdCommitted = initialAppUserModelId.isNullOrBlank() ||
                        (shellLink.queryPropertyStore()?.use { store ->
                            store.setStringValue(WindowsPropertyStore.PKEY_APP_USER_MODEL_ID, initialAppUserModelId) &&
                                store.commit()
                        } ?: false)
                    val saved = targetAssigned && appIdCommitted && (
                        shellLink.queryPersistFile()?.use { persistFile ->
                            persistFile.save(shortcutPath)
                        } ?: false
                        )
                    saved
                } ?: false
            }
        }.getOrDefault(false)
    }
}

private fun reconcileShortcutInCom(
    shortcutPath: Path,
    normalizedExpectedExe: String,
    appUserModelId: String,
): ShortcutReconcileOutcome {
    val inspection = inspectShortcutTargetAndAppId(shortcutPath)
    return when {
        inspection == null -> ShortcutReconcileOutcome.FAILED
        normalizeWindowsPath(inspection.first) != normalizedExpectedExe ->
            ShortcutReconcileOutcome.SKIPPED_TARGET_MISMATCH
        inspection.second == appUserModelId -> ShortcutReconcileOutcome.ALREADY_ALIGNED
        writeShortcutAppId(shortcutPath, appUserModelId) -> {
            notifyShellItemUpdated(shortcutPath)
            ShortcutReconcileOutcome.UPDATED
        }
        else -> ShortcutReconcileOutcome.FAILED
    }
}

private fun inspectShortcutTargetAndAppId(shortcutPath: Path): Pair<String, String?>? =
    withShellLink { shellLink ->
        shellLink.queryPersistFile()?.use { persistFile ->
            if (!persistFile.load(shortcutPath, STGM_READ)) {
                null
            } else {
                val rawTarget = shellLink.getPath().orEmpty()
                val currentAppId = shellLink.queryPropertyStore()?.use { store ->
                    store.getStringValue(WindowsPropertyStore.PKEY_APP_USER_MODEL_ID)
                }
                rawTarget to currentAppId
            }
        }
    }

private fun writeShortcutAppId(
    shortcutPath: Path,
    appUserModelId: String,
): Boolean = withShellLink { shellLink ->
    shellLink.queryPersistFile()?.use { persistFile ->
        val loaded = persistFile.load(shortcutPath, STGM_READWRITE)
        val committed = loaded && (
            shellLink.queryPropertyStore()?.use { store ->
                store.setStringValue(WindowsPropertyStore.PKEY_APP_USER_MODEL_ID, appUserModelId) &&
                    store.commit()
            } ?: false
            )
        committed && persistFile.save(shortcutPath)
    } ?: false
} ?: false

private fun defaultShortcutCandidates(): List<Path> {
    val results = mutableListOf<Path>()
    val appData = System.getenv("APPDATA")?.takeIf(String::isNotBlank)
    if (appData != null) {
        runCatching {
            val roaming = Path.of(appData)
            results.add(roaming.resolve("Microsoft/Windows/Start Menu/Programs/Onyx/Onyx.lnk"))
            results.add(roaming.resolve("Microsoft/Windows/Start Menu/Programs/Onyx.lnk"))
            results.add(roaming.resolve("Microsoft/Internet Explorer/Quick Launch/User Pinned/TaskBar/Onyx.lnk"))
        }
    }
    val userHome = System.getProperty("user.home")?.takeIf(String::isNotBlank)
    if (userHome != null) {
        runCatching {
            results.add(Path.of(userHome).resolve("Desktop/Onyx.lnk"))
        }
    }
    return results.distinctBy { candidate -> normalizeWindowsPath(candidate.toString()) }
}

private fun normalizeWindowsPath(rawPath: String): String {
    val cleaned = rawPath.trim().trim('"')
    if (cleaned.isEmpty()) return ""
    val normalized = try {
        Path.of(cleaned).toAbsolutePath().normalize().toString()
    } catch (_: InvalidPathException) {
        cleaned.replace('/', '\\')
    }
    return normalized.lowercase(Locale.ROOT)
}

private fun notifyShellItemUpdated(shortcutPath: Path) {
    runCatching {
        Shell32Notify.INSTANCE.SHChangeNotify(
            SHCNE_UPDATEITEM,
            SHCNF_PATHW,
            WString(shortcutPath.toAbsolutePath().normalize().toString()),
            null,
        )
    }
}

private inline fun <T> withShellLink(block: (ShellLinkComObject) -> T): T? {
    val ref = PointerByReference()
    val hr = Ole32.INSTANCE.CoCreateInstance(
        CLSID_SHELL_LINK,
        Pointer.NULL,
        WTypes.CLSCTX_INPROC_SERVER,
        IID_I_SHELL_LINK_W,
        ref,
    )
    val pointer = ref.value
    if (!isHResultSucceeded(hr) || pointer == null) return null
    return ShellLinkComObject(pointer).use(block)
}

/** `CLSID_ShellLink`。 */
private val CLSID_SHELL_LINK = Guid.CLSID("{00021401-0000-0000-C000-000000000046}")

/** `IID_IShellLinkW`。 */
private val IID_I_SHELL_LINK_W = Guid.IID("{000214F9-0000-0000-C000-000000000046}")

/** `IID_IPersistFile`。 */
private val IID_I_PERSIST_FILE = Guid.IID("{0000010B-0000-0000-C000-000000000046}")

/** `STGM_READ`。 */
private const val STGM_READ = 0

/** `STGM_READWRITE`。 */
private const val STGM_READWRITE = 2

/** `SHCNE_UPDATEITEM`。 */
private const val SHCNE_UPDATEITEM = 0x00002000

/** `SHCNF_PATHW`。 */
private const val SHCNF_PATHW = 0x0005

/**
 * `IShellLinkW` 的轻量 COM 包装。
 */
private class ShellLinkComObject(pointer: Pointer) : Unknown(pointer), AutoCloseable {
    /** 读取快捷方式目标路径（`IShellLinkW::GetPath`，vtable 索引 3）。 */
    fun getPath(): String? {
        val buffer = CharArray(MAX_TARGET_PATH_CHARS)
        val hr = _invokeNativeObject(
            VTABLE_GET_PATH_INDEX,
            arrayOf(pointer, buffer, buffer.size, null, 0),
            WinNT.HRESULT::class.java,
        ) as WinNT.HRESULT
        if (!isHResultSucceeded(hr)) return null
        return String(buffer).substringBefore('\u0000').takeIf(String::isNotBlank)
    }

    /** 设置快捷方式目标路径（`IShellLinkW::SetPath`，vtable 索引 20）。 */
    fun setPath(targetPath: String): Boolean {
        val hr = _invokeNativeObject(
            VTABLE_SET_PATH_INDEX,
            arrayOf(pointer, WString(targetPath)),
            WinNT.HRESULT::class.java,
        ) as WinNT.HRESULT
        return isHResultSucceeded(hr)
    }

    /** 查询 `IPersistFile` 接口。 */
    fun queryPersistFile(): PersistFileComObject? {
        val ref = PointerByReference()
        val hr = QueryInterface(Guid.REFIID(IID_I_PERSIST_FILE), ref)
        val value = ref.value
        return if (isHResultSucceeded(hr) && value != null) PersistFileComObject(value) else null
    }

    /** 查询 `IPropertyStore` 接口。 */
    fun queryPropertyStore(): WindowsPropertyStore? {
        val ref = PointerByReference()
        val hr = QueryInterface(Guid.REFIID(WindowsPropertyStore.IID_I_PROPERTY_STORE), ref)
        val value = ref.value
        return if (isHResultSucceeded(hr) && value != null) WindowsPropertyStore(value) else null
    }

    override fun close() {
        Release()
    }

    private companion object {
        const val MAX_TARGET_PATH_CHARS = 1024
        const val VTABLE_GET_PATH_INDEX = 3
        const val VTABLE_SET_PATH_INDEX = 20
    }
}

/**
 * `IPersistFile` 的轻量 COM 包装。
 */
private class PersistFileComObject(pointer: Pointer) : Unknown(pointer), AutoCloseable {
    /** 加载 `.lnk` 文件（`IPersistFile::Load`，vtable 索引 5）。 */
    fun load(
        filePath: Path,
        mode: Int,
    ): Boolean {
        val hr = _invokeNativeObject(
            VTABLE_LOAD_INDEX,
            arrayOf(pointer, WString(filePath.toAbsolutePath().normalize().toString()), mode),
            WinNT.HRESULT::class.java,
        ) as WinNT.HRESULT
        return isHResultSucceeded(hr)
    }

    /** 保存 `.lnk` 文件（`IPersistFile::Save`，vtable 索引 6）。 */
    fun save(filePath: Path): Boolean {
        val hr = _invokeNativeObject(
            VTABLE_SAVE_INDEX,
            arrayOf(pointer, WString(filePath.toAbsolutePath().normalize().toString()), 1),
            WinNT.HRESULT::class.java,
        ) as WinNT.HRESULT
        return isHResultSucceeded(hr)
    }

    override fun close() {
        Release()
    }

    private companion object {
        const val VTABLE_LOAD_INDEX = 5
        const val VTABLE_SAVE_INDEX = 6
    }
}

/**
 * `shell32.dll` 中通知外壳刷新快捷方式缓存的原生函数映射。
 */
private interface Shell32Notify : StdCallLibrary {
    /**
     * 通知 Windows Shell 某路径条目属性已更新。
     */
    fun SHChangeNotify(
        wEventId: Int,
        uFlags: Int,
        dwItem1: WString?,
        dwItem2: Pointer?,
    )

    companion object {
        val INSTANCE: Shell32Notify by lazy {
            Native.load("shell32", Shell32Notify::class.java, W32APIOptions.UNICODE_OPTIONS)
        }
    }
}
