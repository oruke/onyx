package com.oruke.onyx.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.oruke.onyx.app.OnyxLogger
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Window
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Windows 任务栏分组与窗口应用身份统一绑定服务。
 *
 * 负责解决 Windows 10/11 下多窗口与升级安装后的任务栏图标分裂问题：
 * 1. 进程冷启动最早期通过 `SetCurrentProcessExplicitAppUserModelID` 声明跨版本稳定的显式身份 `Oruke.Onyx`；
 * 2. 每个顶层窗口（主窗口、新打开的目录窗口、搜索窗口、任务中心、图片查看器）创建原生 `HWND` 时，
 *    通过 `SHGetPropertyStoreForWindow` 显式写入 `System.AppUserModel.ID`（以及打包态 `Relaunch*` 元数据），
 *    消除 `jpackage` 父子启动器跳转或覆盖升级后因启发式路径漂移导致的任务栏无法合并；
 * 3. 窗口销毁前严格按 Win32 规范将属性重置为 `VT_EMPTY`，防止系统全局窗口属性空间泄漏。
 */
internal object WindowsTaskbarGroupingService {

    /** 跨版本稳定、符合 Microsoft `CompanyName.ProductName` 规范的应用用户模型 ID。 */
    const val ONYX_APP_USER_MODEL_ID: String = "Oruke.Onyx"

    /** 任务栏固定快捷方式与重启元数据的应用显示名称。 */
    const val ONYX_DISPLAY_NAME: String = "Onyx"

    /** 打包分发版原生启动器文件名。 */
    private const val PACKAGED_LAUNCHER_FILE_NAME: String = "Onyx.exe"

    /** 当前运行宿主是否为 Windows 平台。 */
    internal val isWindowsHost: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /**
     * 在应用启动最早期（创建任何 AWT/Compose 窗口前）绑定当前进程的显式 `AppUserModelID`。
     *
     * 非 Windows 平台安全降级为空操作并直接返回 `false`。
     *
     * @param appUserModelId 目标应用标识，默认使用 [ONYX_APP_USER_MODEL_ID]。
     * @return 成功在 Windows 宿主设置进程标识时返回 `true`。
     */
    fun initializeProcessIdentity(appUserModelId: String = ONYX_APP_USER_MODEL_ID): Boolean {
        if (!isWindowsHost || appUserModelId.isBlank()) return false
        return runCatching {
            val hr = Shell32Taskbar.INSTANCE.SetCurrentProcessExplicitAppUserModelID(WString(appUserModelId))
            val succeeded = isHResultSucceeded(hr)
            if (!succeeded) {
                OnyxLogger.warn(
                    "Taskbar",
                    "设置进程显式 AppUserModelID 失败 (hr=0x${hr.toInt().toUInt().toString(HEX_RADIX)})",
                )
            }
            succeeded
        }.onFailure { failure ->
            OnyxLogger.warn("Taskbar", "调用 SetCurrentProcessExplicitAppUserModelID 异常", failure)
        }.getOrDefault(false)
    }

    /**
     * 读取当前进程已声明的显式 `AppUserModelID`（供测试验证与运行诊断）。
     *
     * @return 当前进程的显式 `AppUserModelID`；非 Windows 或未设置时返回 `null`。
     */
    fun currentProcessAppUserModelId(): String? {
        if (!isWindowsHost) return null
        return runCatching {
            val ref = PointerByReference()
            val hr = Shell32Taskbar.INSTANCE.GetCurrentProcessExplicitAppUserModelID(ref)
            val pointer = ref.value
            if (!isHResultSucceeded(hr) || pointer == null) {
                null
            } else {
                try {
                    pointer.getWideString(0L)
                } finally {
                    Ole32.INSTANCE.CoTaskMemFree(pointer)
                }
            }
        }.getOrNull()
    }

    /**
     * 为指定 AWT/Compose 顶层窗口安装任务栏分组标识监听，并在句柄关闭前自动清理窗口属性。
     *
     * @param window 待绑定的顶层窗口。
     * @param appUserModelId 目标应用标识。
     * @param relaunchExecutablePath 可选的打包启动器路径；非空时同步写入任务栏固定重启元数据。
     * @return 卸载监听器并在 `HWND` 仍有效时擦除窗口属性的句柄。
     */
    fun installWindowIdentity(
        window: Window,
        appUserModelId: String = ONYX_APP_USER_MODEL_ID,
        relaunchExecutablePath: String? = resolvePackagedExecutablePath(),
    ): AutoCloseable {
        if (!isWindowsHost) return AutoCloseable {}
        var boundHandle: WinDef.HWND? = null

        fun syncWindowBinding() {
            val currentHandle = resolveWindowHandle(window)
            if (currentHandle == null) {
                boundHandle = null
                return
            }
            if (!sameWindowHandle(boundHandle, currentHandle)) {
                if (applyWindowTaskbarProperties(currentHandle, appUserModelId, relaunchExecutablePath)) {
                    boundHandle = currentHandle
                }
            }
        }

        val hierarchyListener = HierarchyListener { event ->
            val displayabilityChanged = (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong()) != 0L
            if (displayabilityChanged) {
                syncWindowBinding()
            }
        }
        window.addHierarchyListener(hierarchyListener)
        syncWindowBinding()

        return AutoCloseable {
            window.removeHierarchyListener(hierarchyListener)
            val handleToClear = boundHandle ?: resolveWindowHandle(window)
            boundHandle = null
            if (handleToClear != null && User32.INSTANCE.IsWindow(handleToClear)) {
                clearWindowTaskbarProperties(handleToClear)
            }
        }
    }

    /**
     * 直接读取指定窗口 `HWND` 当前存储的 `System.AppUserModel.ID` 属性值（供单元测试与诊断验证）。
     *
     * @param window 目标窗口。
     * @return 窗口级 `AppUserModelID`；未设置或窗口无原生句柄时返回 `null`。
     */
    internal fun readWindowAppUserModelId(window: Window): String? {
        val hwnd = resolveWindowHandle(window) ?: return null
        return withWindowPropertyStore(hwnd) { store ->
            store.getStringValue(WindowsPropertyStore.PKEY_APP_USER_MODEL_ID)
        }
    }

    /**
     * 直接读取指定窗口 `HWND` 当前存储的 `System.AppUserModel.RelaunchCommand` 属性值（供测试验证）。
     *
     * @param window 目标窗口。
     * @return 窗口级重启命令字符串；未设置或窗口无原生句柄时返回 `null`。
     */
    internal fun readWindowRelaunchCommand(window: Window): String? {
        val hwnd = resolveWindowHandle(window) ?: return null
        return withWindowPropertyStore(hwnd) { store ->
            store.getStringValue(WindowsPropertyStore.PKEY_RELAUNCH_COMMAND)
        }
    }

    /**
     * 解析当前进程是否为打包后的 `Onyx.exe` 启动器，若是则返回规范化绝对路径。
     *
     * 开发态通过 `java.exe` / `javaw.exe` 启动时返回 `null`，避免把裸 JVM 命令写入任务栏固定属性。
     *
     * @param rawCommandPath 待校验的进程命令路径，默认取自 [ProcessHandle.current]。
     * @return 匹配的 `Onyx.exe` 绝对路径；不匹配时返回 `null`。
     */
    internal fun resolvePackagedExecutablePath(
        rawCommandPath: String? = ProcessHandle.current().info().command().orElse(null),
    ): String? {
        val trimmed = rawCommandPath?.trim()?.trim('"').orEmpty()
        val normalizedPath = trimmed
            .takeIf(String::isNotEmpty)
            ?.let { candidate ->
                try {
                    Path.of(candidate).toAbsolutePath().normalize()
                } catch (_: InvalidPathException) {
                    null
                }
            }
        val matchesPackagedLauncher = normalizedPath
            ?.fileName
            ?.toString()
            ?.equals(PACKAGED_LAUNCHER_FILE_NAME, ignoreCase = true) == true
        return if (matchesPackagedLauncher) normalizedPath.toString() else null
    }

    /**
     * 从 AWT [Window] 解析底层 Win32 `HWND`；窗口尚未创建原生 Peer 时返回 `null`。
     */
    internal fun resolveWindowHandle(window: Window): WinDef.HWND? {
        if (!isWindowsHost || !window.isDisplayable) return null
        val pointer = runCatching { Native.getComponentPointer(window) }.getOrNull() ?: return null
        return if (Pointer.nativeValue(pointer) == 0L) null else WinDef.HWND(pointer)
    }

    /** 十六进制格式化基数。 */
    private const val HEX_RADIX = 16
}

/**
 * 将重启元数据与 `System.AppUserModel.ID` 按微软推荐顺序写入目标窗口属性表。
 *
 * 注意：`Relaunch*` 属性必须在 `PKEY_APP_USER_MODEL_ID` 之前写入，因为任务栏仅响应 `PKEY_APP_USER_MODEL_ID` 的更新事件。
 */
private fun applyWindowTaskbarProperties(
    hwnd: WinDef.HWND,
    appUserModelId: String,
    relaunchExecutablePath: String? = null,
): Boolean = withWindowPropertyStore(hwnd) { store ->
    val normalizedExe = relaunchExecutablePath?.trim()?.takeIf(String::isNotEmpty)
    if (normalizedExe != null) {
        store.setStringValue(WindowsPropertyStore.PKEY_RELAUNCH_COMMAND, "\"$normalizedExe\"")
        store.setStringValue(
            WindowsPropertyStore.PKEY_RELAUNCH_DISPLAY_NAME_RESOURCE,
            WindowsTaskbarGroupingService.ONYX_DISPLAY_NAME,
        )
        store.setStringValue(WindowsPropertyStore.PKEY_RELAUNCH_ICON_RESOURCE, "$normalizedExe,0")
    }
    store.setStringValue(WindowsPropertyStore.PKEY_APP_USER_MODEL_ID, appUserModelId)
} ?: false

/**
 * 在窗口销毁前将其属性表中的所有 `AppUserModel` 属性重置为 `VT_EMPTY`。
 */
private fun clearWindowTaskbarProperties(hwnd: WinDef.HWND): Boolean =
    withWindowPropertyStore(hwnd) { store ->
        store.clearValue(WindowsPropertyStore.PKEY_RELAUNCH_COMMAND)
        store.clearValue(WindowsPropertyStore.PKEY_RELAUNCH_DISPLAY_NAME_RESOURCE)
        store.clearValue(WindowsPropertyStore.PKEY_RELAUNCH_ICON_RESOURCE)
        store.clearValue(WindowsPropertyStore.PKEY_APP_USER_MODEL_ID)
    } ?: false

private fun sameWindowHandle(
    first: WinDef.HWND?,
    second: WinDef.HWND?,
): Boolean {
    if (first == null || second == null) return false
    return Pointer.nativeValue(first.pointer) == Pointer.nativeValue(second.pointer)
}

private inline fun <T> withWindowPropertyStore(
    hwnd: WinDef.HWND,
    block: (WindowsPropertyStore) -> T,
): T? = runCatching {
    withWindowsComInitialized {
        val storeRef = PointerByReference()
        val hr = Shell32Taskbar.INSTANCE.SHGetPropertyStoreForWindow(
            hwnd,
            Guid.REFIID(WindowsPropertyStore.IID_I_PROPERTY_STORE),
            storeRef,
        )
        val storePointer = storeRef.value
        if (!isHResultSucceeded(hr) || storePointer == null) {
            null
        } else {
            WindowsPropertyStore(storePointer).use(block)
        }
    }
}.onFailure { failure ->
    OnyxLogger.warn("Taskbar", "访问窗口 IPropertyStore 失败", failure)
}.getOrNull()

/**
 * 为当前 Compose 顶层窗口绑定统一的 Windows 任务栏分组标识，并在组合卸载时自动回收窗口原生属性。
 *
 * @param window 当前窗口作用域内的原生窗口实例。
 */
@Composable
internal fun BindWindowsTaskbarIdentity(window: Window) {
    DisposableEffect(window) {
        val binding = WindowsTaskbarGroupingService.installWindowIdentity(window)
        onDispose(binding::close)
    }
}

/**
 * `shell32.dll` 中进程与窗口级 `AppUserModelID` 管理所需的原生函数映射。
 */
private interface Shell32Taskbar : StdCallLibrary {
    /**
     * 为当前进程声明显式 `AppUserModelID`。
     */
    fun SetCurrentProcessExplicitAppUserModelID(appID: WString): WinNT.HRESULT

    /**
     * 查询当前进程已声明的显式 `AppUserModelID`。
     */
    fun GetCurrentProcessExplicitAppUserModelID(appID: PointerByReference): WinNT.HRESULT

    /**
     * 获取指定窗口句柄对应的 `IPropertyStore` 接口。
     */
    fun SHGetPropertyStoreForWindow(
        hwnd: WinDef.HWND,
        riid: Guid.REFIID,
        ppv: PointerByReference,
    ): WinNT.HRESULT

    companion object {
        val INSTANCE: Shell32Taskbar by lazy {
            Native.load("shell32", Shell32Taskbar::class.java, W32APIOptions.UNICODE_OPTIONS)
        }
    }
}
