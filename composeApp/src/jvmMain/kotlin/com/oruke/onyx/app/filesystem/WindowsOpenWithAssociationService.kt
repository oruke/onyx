package com.oruke.onyx.app.filesystem

import com.oruke.onyx.vfs.api.OpenWithApp
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Windows Shell “打开方式”关联处理器服务。
 *
 * `SHAssocEnumHandlers` 返回的 `IAssocHandler` 是资源管理器“打开方式”菜单使用的应用来源，
 * 本服务只读取应用可执行路径、UI 名称和图标位置，执行阶段交给明确的应用启动器。
 *
 * @param launcher 使用用户已选应用直接打开文件的启动器。
 */
internal class WindowsOpenWithAssociationService(
    private val launcher: WindowsOpenWithLauncher = ProcessWindowsOpenWithLauncher(),
) {
    /** Shell 关联处理器专用线程池替换锁。 */
    private val shellExecutorLock = Any()

    /** Shell 关联处理器专用 STA 执行器。 */
    private var shellExecutor = newShellExecutor()

    /**
     * 查询指定扩展名的 Shell “打开方式”候选应用。
     *
     * @param extension 带点的文件扩展名，例如 `.yaml`。
     * @return Shell 返回的候选应用列表；失败或超时时返回空列表。
     */
    fun listApps(extension: String): List<OpenWithApp> {
        val normalizedExtension = extension.takeIf { value -> value.startsWith(".") } ?: return emptyList()
        return runCatching {
            callOnShellThread(WINDOWS_ASSOC_LIST_TIMEOUT_MS) {
                listAppsOnShellThread(normalizedExtension)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 判断应用命令是否来自 Shell 关联处理器。
     *
     * @param command `OpenWithApp.command` 中保存的平台命令。
     * @return 属于本服务命令格式时返回 `true`。
     */
    fun isAssociationCommand(command: String): Boolean {
        return command.startsWith(WINDOWS_ASSOC_COMMAND_PREFIX)
    }

    /**
     * 使用用户已选择的 Shell 关联应用直接打开目标文件。
     *
     * @param target 需要打开的本地文件路径。
     * @param app 用户选择的“打开方式”应用。
     * @return 应用进程启动结果。
     */
    fun openWith(
        target: Path,
        app: OpenWithApp,
    ): Result<Unit> {
        val executable = runCatching {
            require(isAssociationCommand(app.command)) {
                "Invalid Windows association command: ${app.command}"
            }
            val executablePath = app.command.removePrefix(WINDOWS_ASSOC_COMMAND_PREFIX)
            require(executablePath.isNotBlank()) {
                "Windows association executable is empty: ${app.displayName}"
            }
            Path.of(executablePath)
        }.getOrElse { failure -> return Result.failure(failure) }
        return launcher.launch(executable, target)
    }

    /**
     * 在 Shell STA 线程中查询候选应用。
     *
     * @param extension 带点的文件扩展名。
     * @return 去重后的候选应用列表。
     */
    private fun listAppsOnShellThread(extension: String): List<OpenWithApp> {
        return withComApartment {
            enumerateHandlers(extension, ASSOC_FILTER_RECOMMENDED)
                .distinctBy { handler -> handler.name.lowercase(Locale.ROOT) }
                .map { handler -> handler.toOpenWithApp() }
        }
    }

    /**
     * 枚举指定扩展名下的 Shell 关联处理器。
     *
     * @param extension 带点的文件扩展名。
     * @param filter Shell 关联过滤条件。
     * @return 当前过滤条件下的处理器信息。
     */
    private fun enumerateHandlers(
        extension: String,
        filter: Int,
    ): List<WindowsAssociationHandlerInfo> {
        val enumRef = PointerByReference()
        val result = Shell32Association.INSTANCE.SHAssocEnumHandlers(WString(extension), filter, enumRef)
        if (!result.succeeded() || enumRef.value == null) return emptyList()
        val enumerator = WindowsAssocHandlers(enumRef.value)
        return try {
            buildList {
                while (true) {
                    val handler = enumerator.nextHandler() ?: break
                    try {
                        handler.toInfo()?.let { info -> add(info) }
                    } finally {
                        handler.Release()
                    }
                }
            }
        } finally {
            enumerator.Release()
        }
    }

    /**
     * 初始化 COM Apartment 并在结束时清理。
     *
     * @param block 需要在 COM 初始化后执行的操作。
     * @return `block` 的返回值。
     */
    private fun <T> withComApartment(block: () -> T): T {
        val initializeResult = Ole32.INSTANCE.CoInitializeEx(
            Pointer.NULL,
            Ole32.COINIT_APARTMENTTHREADED or Ole32.COINIT_DISABLE_OLE1DDE,
        )
        val comInitialized = initializeResult.succeeded()
        check(comInitialized || initializeResult.toInt() == RPC_E_CHANGED_MODE) {
            "CoInitializeEx failed: ${initializeResult.toInt()}"
        }
        return try {
            block()
        } finally {
            if (comInitialized) {
                Ole32.INSTANCE.CoUninitialize()
            }
        }
    }

    /**
     * 将 Shell COM 任务提交到专用 STA 线程。
     *
     * @param timeoutMillis 等待任务完成的最长时间。
     * @param block 实际要执行的 COM 任务。
     * @return 任务返回值。
     */
    private fun <T> callOnShellThread(
        timeoutMillis: Long,
        block: () -> T,
    ): T {
        val executor = synchronized(shellExecutorLock) { shellExecutor }
        val future = executor.submit<T> { block() }
        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        } catch (failure: TimeoutException) {
            future.cancel(true)
            synchronized(shellExecutorLock) {
                if (shellExecutor === executor) {
                    shellExecutor.shutdownNow()
                    shellExecutor = newShellExecutor()
                }
            }
            throw IllegalStateException("Windows association handler timed out after ${timeoutMillis}ms", failure)
        }
    }

    /**
     * 创建 Shell COM 专用单线程执行器。
     *
     * @return 后台守护线程执行器。
     */
    private fun newShellExecutor(): ExecutorService {
        return Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "onyx-windows-open-with").apply {
                isDaemon = true
            }
        }
    }

    /**
     * 将 Shell 处理器信息转换为统一打开方式模型。
     *
     * @return 可供 UI 展示和点击执行的应用模型。
     */
    private fun WindowsAssociationHandlerInfo.toOpenWithApp(): OpenWithApp {
        return OpenWithApp(
            id = "windows-assoc:$name",
            displayName = uiName,
            command = "$WINDOWS_ASSOC_COMMAND_PREFIX$name",
            iconPath = iconPath,
        )
    }

    /**
     * 读取 Shell 分配的宽字符串并释放内存。
     *
     * @param pointer 字符串指针。
     * @return 读取到的字符串；空值返回 `null`。
     */
    private fun readCoTaskString(pointer: Pointer?): String? {
        if (pointer == null || Pointer.nativeValue(pointer) == 0L) return null
        return try {
            pointer.getWideString(0).takeIf { value -> value.isNotBlank() }
        } finally {
            Ole32.INSTANCE.CoTaskMemFree(pointer)
        }
    }

    /**
     * 判断 HRESULT 是否表示成功。
     *
     * @return 非错误 HRESULT 返回 `true`。
     */
    private fun WinNT.HRESULT.succeeded(): Boolean = toInt() >= 0

    /**
     * `IEnumAssocHandlers` 的最小 JNA 包装。
     *
     * @param pointer COM 对象指针。
     */
    private inner class WindowsAssocHandlers(pointer: Pointer) : Unknown(pointer) {
        /**
         * 读取下一个关联处理器。
         *
         * @return 下一个处理器；没有更多处理器时返回 `null`。
         */
        fun nextHandler(): WindowsAssocHandler? {
            val handlerRef = PointerByReference()
            val fetchedRef = IntByReference()
            val result = _invokeNativeObject(
                ENUM_ASSOC_HANDLERS_NEXT_VTABLE_INDEX,
                arrayOf(pointer, WinDef.ULONG(1), handlerRef, fetchedRef),
                WinNT.HRESULT::class.java,
            ) as WinNT.HRESULT
            if (!result.succeeded() || result.toInt() != S_OK || fetchedRef.value <= 0) return null
            return handlerRef.value?.let { handlerPointer -> WindowsAssocHandler(handlerPointer) }
        }
    }

    /**
     * `IAssocHandler` 的最小 JNA 包装。
     *
     * @param pointer COM 对象指针。
     */
    private inner class WindowsAssocHandler(pointer: Pointer) : Unknown(pointer) {
        /**
         * 读取处理器稳定名称。
         *
         * @return Shell 处理器名称；失败时返回 `null`。
         */
        fun name(): String? {
            return readStringResult(ASSOC_HANDLER_GET_NAME_VTABLE_INDEX)
        }

        /**
         * 转换为不可持有 COM 引用的值对象。
         *
         * @return 可缓存的处理器信息；名称为空时返回 `null`。
         */
        fun toInfo(): WindowsAssociationHandlerInfo? {
            val name = name() ?: return null
            val uiName = readStringResult(ASSOC_HANDLER_GET_UI_NAME_VTABLE_INDEX)
                ?.toWindowsAssociationLabel()
                ?: name.fallbackAssociationName()
            val iconPath = iconLocation()
            return WindowsAssociationHandlerInfo(
                name = name,
                uiName = uiName,
                iconPath = iconPath,
            )
        }

        /**
         * 读取处理器图标位置。
         *
         * @return 图标路径加资源索引；失败时返回 `null`。
         */
        private fun iconLocation(): String? {
            val pathRef = PointerByReference()
            val indexRef = IntByReference()
            val result = _invokeNativeObject(
                ASSOC_HANDLER_GET_ICON_LOCATION_VTABLE_INDEX,
                arrayOf(pointer, pathRef, indexRef),
                WinNT.HRESULT::class.java,
            ) as WinNT.HRESULT
            val path = if (result.succeeded()) readCoTaskString(pathRef.value) else null
            return path?.let { value ->
                if (indexRef.value == 0) value else "$value,${indexRef.value}"
            }
        }

        /**
         * 调用返回 `LPWSTR*` 的 `IAssocHandler` 方法。
         *
         * @param vtableIndex 方法在 COM vtable 中的索引。
         * @return Shell 分配的字符串；失败时返回 `null`。
         */
        private fun readStringResult(vtableIndex: Int): String? {
            val valueRef = PointerByReference()
            val result = _invokeNativeObject(
                vtableIndex,
                arrayOf(pointer, valueRef),
                WinNT.HRESULT::class.java,
            ) as WinNT.HRESULT
            if (!result.succeeded()) return null
            return readCoTaskString(valueRef.value)
        }
    }

    /**
     * Shell 关联处理器快照。
     */
    private data class WindowsAssociationHandlerInfo(
        /** `IAssocHandler.GetName` 返回的稳定名称。 */
        val name: String,
        /** `IAssocHandler.GetUIName` 返回的用户可见名称。 */
        val uiName: String,
        /** `IAssocHandler.GetIconLocation` 返回的图标位置。 */
        val iconPath: String?,
    )

    /**
     * Windows 关联处理器所需的 Shell32 原生函数。
     */
    private interface Shell32Association : StdCallLibrary {
        /**
         * 枚举指定扩展名的关联处理器。
         *
         * @param pszExtra 文件扩展名或协议。
         * @param afFilter 关联处理器过滤条件。
         * @param ppEnumHandler 输出枚举器。
         * @return COM 调用结果。
         */
        fun SHAssocEnumHandlers(
            pszExtra: WString,
            afFilter: Int,
            ppEnumHandler: PointerByReference,
        ): WinNT.HRESULT

        companion object {
            /** Shell32 原生库单例。 */
            val INSTANCE: Shell32Association = Native.load(
                "shell32",
                Shell32Association::class.java,
                W32APIOptions.UNICODE_OPTIONS,
            )
        }
    }

    private companion object {
        /** `OpenWithApp.command` 中标记 Shell 关联处理器的前缀。 */
        const val WINDOWS_ASSOC_COMMAND_PREFIX = "windows-assoc-handler:"

        /** `IEnumAssocHandlers.Next` 成功读取元素时的返回值。 */
        const val S_OK = 0

        /** 枚举推荐关联处理器。 */
        const val ASSOC_FILTER_RECOMMENDED = 1

        /** COM 初始化时线程模式冲突的 HRESULT。 */
        const val RPC_E_CHANGED_MODE = -2147417850

        /** Shell 关联处理器列表读取超时。 */
        const val WINDOWS_ASSOC_LIST_TIMEOUT_MS = 800L

        /** `IEnumAssocHandlers.Next` 在 COM vtable 中的索引。 */
        const val ENUM_ASSOC_HANDLERS_NEXT_VTABLE_INDEX = 3

        /** `IAssocHandler.GetName` 在 COM vtable 中的索引。 */
        const val ASSOC_HANDLER_GET_NAME_VTABLE_INDEX = 3

        /** `IAssocHandler.GetUIName` 在 COM vtable 中的索引。 */
        const val ASSOC_HANDLER_GET_UI_NAME_VTABLE_INDEX = 4

        /** `IAssocHandler.GetIconLocation` 在 COM vtable 中的索引。 */
        const val ASSOC_HANDLER_GET_ICON_LOCATION_VTABLE_INDEX = 5

        /**
         * 清理 Shell 处理器显示名称。
         *
         * @return 可显示名称；空值或资源引用返回 `null`。
         */
        fun String.toWindowsAssociationLabel(): String? {
            return trim()
                .takeIf { text -> text.isNotBlank() && !text.startsWith("@") }
                ?.replace("&", "")
                ?.takeIf { text -> text.isNotBlank() }
        }

        /**
         * 从 Shell 处理器稳定名称生成兜底应用名。
         *
         * @return 尽量可读的应用名称。
         */
        fun String.fallbackAssociationName(): String {
            return substringAfterLast('\\')
                .removeSuffix(".exe")
                .removePrefix("App.")
                .replace('_', ' ')
                .replace('-', ' ')
                .trim()
                .takeIf { value -> value.isNotBlank() }
                ?: this
        }
    }
}
