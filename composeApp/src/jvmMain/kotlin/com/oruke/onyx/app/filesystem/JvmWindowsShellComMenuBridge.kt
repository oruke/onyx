package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR
import com.sun.jna.platform.win32.COM.IShellFolder
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import com.oruke.onyx.vfs.api.SystemMenuAction

/**
 * 通过 Windows Shell COM 读取和执行系统右键菜单。
 *
 * 关键约束：
 * 1. 所有 COM 调用必须进入专用 STA 线程，避免 Compose/UI 线程与 Shell 扩展互相阻塞。
 * 2. 读取菜单有超时保护，超时后上层会回退到注册表菜单，不能卡住右键菜单展示。
 * 3. 只处理同一父目录下的本地系统文件，保持 `IShellFolder.GetUIObjectOf` 的调用契约清晰。
 */
internal class JvmWindowsShellComMenuBridge {
    /** Shell COM 专用线程池替换锁，超时后会丢弃被卡住的旧执行器。 */
    private val shellExecutorLock = Any()
    /** Shell COM 专用 STA 执行器，避免在任意协程线程上初始化 Shell 扩展。 */
    private var shellExecutor = newShellExecutor()

    /**
     * 读取选中文件的 Windows Shell 菜单动作。
     *
     * @param entries 需要查询右键菜单的虚拟文件列表，必须能物化为本地系统路径。
     * @return Shell 菜单动作查询结果；失败或超时会封装为 `Result.failure`。
     */
    fun listActions(entries: List<VFile>): Result<List<SystemMenuAction>> = runCatching {
        callOnShellThread(WINDOWS_COM_LIST_TIMEOUT_MS) { listActionsOnShellThread(entries) }
    }

    /**
     * 执行从 Shell COM 菜单读取到的动作。
     *
     * @param action 用户点击的 Shell 菜单动作，`command` 存储 Shell 命令偏移量。
     * @param entries 动作作用的虚拟文件列表，必须与读取菜单时的目标类型一致。
     * @return 执行结果；失败或超时会封装为 `Result.failure`。
     */
    fun execute(
        action: SystemMenuAction,
        entries: List<VFile>,
    ): Result<Unit> = runCatching {
        callOnShellThread(WINDOWS_COM_EXECUTE_TIMEOUT_MS) { executeOnShellThread(action, entries) }
    }

    /**
     * 在 Shell COM 线程中创建原生 `HMENU` 并读取菜单项。
     *
     * @param entries 需要转换为系统路径的文件条目。
     * @return 可展示到统一右键菜单中的系统菜单动作列表。
     */
    private fun listActionsOnShellThread(entries: List<VFile>): List<SystemMenuAction> {
        val paths = entries.toSystemPaths()
        if (paths.isEmpty()) return emptyList()
        if (!paths.shareParentDirectory()) return emptyList()
        return withShellContextMenu(paths) { session ->
            val menu = User32Menu.INSTANCE.CreatePopupMenu()
                ?: return@withShellContextMenu emptyList()
            try {
                val queryResult = session.contextMenu.queryContextMenu(
                    hMenu = menu,
                    indexMenu = 0,
                    idCmdFirst = COMMAND_ID_FIRST,
                    idCmdLast = COMMAND_ID_LAST,
                    flags = CMF_NORMAL,
                )
                if (!queryResult.succeeded()) {
                    emptyList()
                } else {
                    readMenuItems(menu, session)
                }
            } finally {
                User32Menu.INSTANCE.DestroyMenu(menu)
            }
        }
    }

    /**
     * 在 Shell COM 线程中重新创建 `IContextMenu` 并执行命令。
     *
     * @param action 待读取命令偏移量的系统菜单动作。
     * @param entries 动作对应的文件条目。
     */
    private fun executeOnShellThread(
        action: SystemMenuAction,
        entries: List<VFile>,
    ) {
        val command = WindowsShellComCommand.parse(action.command)
            ?: throw IllegalArgumentException("Invalid Windows Shell COM command: ${action.command}")
        val paths = entries.toSystemPaths()
        if (paths.isEmpty()) throw IllegalArgumentException("Windows Shell COM command requires files")
        if (!paths.shareParentDirectory()) throw IllegalArgumentException("Windows Shell COM command requires one folder")
        withShellContextMenu(paths) { session ->
            val menu = User32Menu.INSTANCE.CreatePopupMenu()
                ?: throw IllegalStateException("CreatePopupMenu failed")
            try {
                val queryResult = session.contextMenu.queryContextMenu(
                    hMenu = menu,
                    indexMenu = 0,
                    idCmdFirst = COMMAND_ID_FIRST,
                    idCmdLast = COMMAND_ID_LAST,
                    flags = CMF_NORMAL,
                )
                if (!queryResult.succeeded()) {
                    throw IllegalStateException("IContextMenu.QueryContextMenu failed: ${queryResult.toInt()}")
                }
                initializeMenuPath(menu, command.menuPath, session)
                val directoryMemory = paths.first().parent?.toString()?.toNativeWideString()
                val invokeInfo = InvokeCommandInfoEx().apply {
                    cbSize = size()
                    fMask = CMIC_MASK_UNICODE or CMIC_MASK_ASYNCOK
                    hwnd = null
                    lpVerb = Pointer.createConstant(command.offset.toLong())
                    lpVerbW = Pointer.createConstant(command.offset.toLong())
                    lpParameters = null
                    lpParametersW = null
                    lpDirectory = null
                    lpDirectoryW = directoryMemory
                    lpTitle = null
                    lpTitleW = null
                    nShow = SW_SHOWNORMAL
                    write()
                }
                val invokeResult = session.contextMenu.invokeCommand(invokeInfo)
                if (!invokeResult.succeeded()) {
                    throw IllegalStateException("IContextMenu.InvokeCommand failed: ${invokeResult.toInt()}")
                }
            } finally {
                User32Menu.INSTANCE.DestroyMenu(menu)
            }
        }
    }

    /**
     * 将 Shell COM 任务提交到专用线程，并在超时后重建执行器。
     *
     * 这样做是为了防止某个第三方 Shell 扩展永久阻塞后续菜单读取。
     *
     * @param timeoutMillis 本次调用允许等待的毫秒数。
     * @param block 实际需要在 Shell COM 线程执行的任务。
     * @return 任务执行结果。
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
            throw IllegalStateException("Windows Shell COM timed out after ${timeoutMillis}ms", failure)
        }
    }

    /**
     * 创建 Shell COM 专用单线程执行器。
     *
     * @return 后台守护线程执行器。
     */
    private fun newShellExecutor(): ExecutorService {
        return Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "onyx-windows-shell-com").apply {
                isDaemon = true
            }
        }
    }

    /**
     * 将虚拟文件转换为 Windows Shell 可识别的本地路径。
     *
     * @return 本地路径列表。
     */
    private fun List<VFile>.toSystemPaths(): List<Path> {
        return map { entry -> entry.requireSystemLocalPath("Windows Shell context menu") }
    }

    /**
     * 判断选中路径是否共享同一个父目录。
     *
     * @return `true` 表示可通过同一个 `IShellFolder` 获取上下文菜单。
     */
    private fun List<Path>.shareParentDirectory(): Boolean {
        val firstParent = firstOrNull()?.parent ?: return false
        return all { path -> path.parent == firstParent }
    }

    /**
     * 初始化 COM、绑定父 Shell 文件夹并获取 `IContextMenu` 会话。
     *
     * @param paths 同一父目录下的本地路径集合。
     * @param block 拿到菜单会话后执行的业务逻辑。
     * @return `block` 的返回值。
     */
    private fun <T> withShellContextMenu(
        paths: List<Path>,
        block: (WindowsShellMenuSession) -> T,
    ): T {
        val initializeResult = Ole32.INSTANCE.CoInitializeEx(
            Pointer.NULL,
            Ole32.COINIT_APARTMENTTHREADED or Ole32.COINIT_DISABLE_OLE1DDE,
        )
        val comInitialized = initializeResult.succeeded()
        if (!comInitialized && initializeResult.toInt() != RPC_E_CHANGED_MODE) {
            throw IllegalStateException("CoInitializeEx failed: ${initializeResult.toInt()}")
        }
        val fullPidls = mutableListOf<Pointer>()
        val parentFolders = mutableListOf<IShellFolder>()
        var contextMenu: WindowsContextMenu? = null
        var messageHandler: WindowsContextMenuMessageHandler? = null
        try {
            val childPidls = mutableListOf<Pointer>()
            paths.forEach { path ->
                val fullPidl = parseDisplayName(path)
                fullPidls += fullPidl
                val parentFolderRef = PointerByReference()
                val childPidlRef = PointerByReference()
                val bindResult = Shell32Context.INSTANCE.SHBindToParent(
                    fullPidl,
                    Guid.REFIID(IShellFolder.IID_ISHELLFOLDER),
                    parentFolderRef,
                    childPidlRef,
                )
                if (!bindResult.succeeded()) {
                    throw IllegalStateException("SHBindToParent failed: ${bindResult.toInt()}")
                }
                parentFolders += IShellFolder.Converter.PointerToIShellFolder(parentFolderRef)
                childPidls += childPidlRef.value ?: throw IllegalStateException("SHBindToParent returned null child PIDL")
            }
            val parentFolder = parentFolders.firstOrNull()
                ?: throw IllegalStateException("No parent shell folder")
            val contextMenuRef = PointerByReference()
            val childPidlArray = childPidls.toPointerArray()
            val uiObjectResult = parentFolder.GetUIObjectOf(
                null,
                childPidls.size,
                childPidlArray,
                Guid.REFIID(IID_I_CONTEXT_MENU),
                null,
                contextMenuRef,
            )
            if (!uiObjectResult.succeeded()) {
                throw IllegalStateException("IShellFolder.GetUIObjectOf(IContextMenu) failed: ${uiObjectResult.toInt()}")
            }
            contextMenu = WindowsContextMenu(contextMenuRef.value)
            messageHandler = contextMenu.queryMessageHandler()
            return block(WindowsShellMenuSession(contextMenu, messageHandler))
        } finally {
            messageHandler?.Release()
            contextMenu?.Release()
            parentFolders.forEach { folder -> folder.Release() }
            fullPidls.forEach { pidl -> Ole32.INSTANCE.CoTaskMemFree(pidl) }
            if (comInitialized) {
                Ole32.INSTANCE.CoUninitialize()
            }
        }
    }

    /**
     * 将本地路径解析为 Shell PIDL。
     *
     * @param path 需要交给 Shell 解析的本地路径。
     * @return Shell 分配的 PIDL 指针，调用方负责释放。
     */
    private fun parseDisplayName(path: Path): Pointer {
        val pidlRef = PointerByReference()
        val result = Shell32Context.INSTANCE.SHParseDisplayName(
            WString(path.toString()),
            null,
            pidlRef,
            0,
            null,
        )
        if (!result.succeeded()) {
            throw IllegalStateException("SHParseDisplayName failed for $path: ${result.toInt()}")
        }
        return pidlRef.value ?: throw IllegalStateException("SHParseDisplayName returned null PIDL")
    }

    /**
     * 将 PIDL 指针列表写入连续内存。
     *
     * @return 可传给 `GetUIObjectOf` 的指针数组内存。
     */
    private fun List<Pointer>.toPointerArray(): Pointer {
        val memory = Memory((Native.POINTER_SIZE * size).toLong())
        forEachIndexed { index, pointer ->
            memory.setPointer((Native.POINTER_SIZE * index).toLong(), pointer)
        }
        return memory
    }

    /**
     * 递归读取原生菜单项。
     *
     * @param menu 需要读取的原生菜单句柄。
     * @param session 当前 Shell 菜单会话。
     * @return 可展示的系统菜单动作列表。
     */
    private fun readMenuItems(
        menu: WinDef.HMENU,
        session: WindowsShellMenuSession,
        menuPath: List<Int> = emptyList(),
    ): List<SystemMenuAction> {
        val count = User32Menu.INSTANCE.GetMenuItemCount(menu)
        if (count <= 0) return emptyList()
        return buildList {
            repeat(count) { index ->
                readMenuItem(menu, index, session, menuPath)?.let { action -> add(action) }
            }
        }
    }

    /**
     * 读取单个原生菜单项并转换为统一菜单动作。
     *
     * @param menu 菜单句柄。
     * @param index 菜单项位置索引。
     * @param session 当前 Shell 菜单会话。
     * @return 可展示菜单动作；分隔线、禁用项或无效命令返回 `null`。
     */
    private fun readMenuItem(
        menu: WinDef.HMENU,
        index: Int,
        session: WindowsShellMenuSession,
        menuPath: List<Int>,
    ): SystemMenuAction? {
        val info = MenuItemInfo().apply {
            cbSize = size()
            fMask = MIIM_FTYPE or MIIM_STATE or MIIM_ID or MIIM_SUBMENU
            write()
        }
        if (!User32Menu.INSTANCE.GetMenuItemInfoW(menu, index, true, info)) return null
        info.read()
        if ((info.fType and MFT_SEPARATOR) != 0) return null
        if ((info.fState and MFS_DISABLED) != 0) return null
        val label = User32Menu.INSTANCE.getMenuString(menu, index).toShellMenuLabel() ?: return null
        val subMenu = info.hSubMenu?.takeUnless { handle -> handle.isNullPointer() }
        val childMenuPath = menuPath + index
        val children = subMenu?.let { handle ->
            session.messageHandler?.handleInitMenuPopup(handle, index)
            readMenuItems(handle, session, childMenuPath)
        }.orEmpty()
        if (children.isNotEmpty()) {
            return SystemMenuAction(
                id = "$WINDOWS_COM_ACTION_PREFIX:submenu:${childMenuPath.joinToString("/")}:${label.hashCode()}",
                displayName = label,
                command = "",
                children = children,
            )
        }
        val offset = info.wID - COMMAND_ID_FIRST
        if (offset < 0 || info.wID > COMMAND_ID_LAST) return null
        val verb = session.contextMenu.commandString(offset, GCS_VERBW)
        return SystemMenuAction(
            id = "$WINDOWS_COM_ACTION_PREFIX:${verb ?: offset}:${menuPath.joinToString("/")}:${label.hashCode()}",
            displayName = label,
            command = WindowsShellComCommand(offset = offset, menuPath = menuPath).serialize(),
        )
    }

    /**
     * 按读取菜单时记录的级联路径重新初始化动态子菜单。
     *
     * Windows Shell 扩展经常只在 `WM_INITMENUPOPUP` 后注册子菜单命令；执行阶段如果不重放这条路径，
     * `InvokeCommand` 会拿到一个尚未初始化的命令偏移量，表现为点击级联项无效果。
     *
     * @param rootMenu `QueryContextMenu` 填充后的根菜单句柄。
     * @param menuPath 从根菜单到叶子菜单父级的菜单项索引路径。
     * @param session 当前 Shell 菜单会话。
     */
    private fun initializeMenuPath(
        rootMenu: WinDef.HMENU,
        menuPath: List<Int>,
        session: WindowsShellMenuSession,
    ) {
        var currentMenu = rootMenu
        menuPath.forEach { index ->
            val info = MenuItemInfo().apply {
                cbSize = size()
                fMask = MIIM_SUBMENU
                write()
            }
            if (!User32Menu.INSTANCE.GetMenuItemInfoW(currentMenu, index, true, info)) {
                throw IllegalStateException("Windows Shell submenu is not available at index $index")
            }
            info.read()
            val subMenu = info.hSubMenu?.takeUnless { handle -> handle.isNullPointer() }
                ?: throw IllegalStateException("Windows Shell submenu is empty at index $index")
            session.messageHandler?.handleInitMenuPopup(subMenu, index)
            currentMenu = subMenu
        }
    }

    /**
     * 按位置读取原生菜单文本。
     *
     * @param menu 菜单句柄。
     * @param index 菜单项位置索引。
     * @return 原始菜单文本。
     */
    private fun User32Menu.getMenuString(menu: WinDef.HMENU, index: Int): String {
        val buffer = CharArray(MAX_MENU_TEXT_LENGTH)
        val copied = GetMenuStringW(menu, index, buffer, buffer.size, MF_BYPOSITION)
        if (copied <= 0) return ""
        return Native.toString(buffer)
    }

    /**
     * 清理 Shell 菜单文本中的快捷键与助记符标记。
     *
     * @return 可显示的菜单名称；空文本返回 `null`。
     */
    private fun String.toShellMenuLabel(): String? {
        val text = substringBefore('\t')
            .replace("&&", "\u0000")
            .replace("&", "")
            .replace("\u0000", "&")
            .trim()
        return text.takeIf { value -> value.isNotBlank() && !value.isUnavailableShellLabel() }
    }

    /**
     * 判断 Shell 返回的文本是否只是注册表空值占位。
     *
     * @return `true` 表示不应作为菜单项展示。
     */
    private fun String.isUnavailableShellLabel(): Boolean {
        return contains("not set", ignoreCase = true) ||
            contains("value not set", ignoreCase = true) ||
            contains("数值未设置") ||
            contains("未设置") ||
            contains("未設定") ||
            contains("値が設定されていません")
    }

    /**
     * 将 Kotlin 字符串复制到 Windows 宽字符内存。
     *
     * @return 以空字符结尾的 UTF-16 指针内存。
     */
    private fun String.toNativeWideString(): Memory {
        val memory = Memory(((length + 1) * Native.WCHAR_SIZE).toLong())
        memory.setWideString(0, this)
        return memory
    }

    /**
     * 判断 HRESULT 是否表示成功。
     *
     * @return `true` 表示 COM 调用成功或返回非错误状态。
     */
    private fun WinNT.HRESULT.succeeded(): Boolean = toInt() >= 0

    /**
     * 判断菜单句柄是否为空。
     *
     * @return `true` 表示没有有效原生菜单。
     */
    private fun WinDef.HMENU.isNullPointer(): Boolean {
        return pointer == null || Pointer.nativeValue(pointer) == 0L
    }

    /**
     * Shell 菜单读取会话，集中持有上下文菜单与动态子菜单消息处理接口。
     *
     * @property contextMenu 当前 `IContextMenu` 包装对象。
     * @property messageHandler 可选的 `IContextMenu2/3` 消息处理对象。
     */
    private data class WindowsShellMenuSession(
        val contextMenu: WindowsContextMenu,
        val messageHandler: WindowsContextMenuMessageHandler?,
    )

    /**
     * `IContextMenu` 的最小 JNA 包装。
     *
     * @param pointer COM 对象指针。
     */
    private class WindowsContextMenu(pointer: Pointer) : Unknown(pointer) {
        /**
         * 调用 `IContextMenu.QueryContextMenu` 填充原生菜单。
         *
         * @param hMenu 目标菜单句柄。
         * @param indexMenu 插入起始位置。
         * @param idCmdFirst 命令 ID 起始值。
         * @param idCmdLast 命令 ID 最大值。
         * @param flags Shell 菜单查询标记。
         * @return COM 调用结果。
         */
        fun queryContextMenu(
            hMenu: WinDef.HMENU,
            indexMenu: Int,
            idCmdFirst: Int,
            idCmdLast: Int,
            flags: Int,
        ): WinNT.HRESULT {
            return _invokeNativeObject(
                3,
                arrayOf(pointer, hMenu, WinDef.UINT(indexMenu.toLong()), WinDef.UINT(idCmdFirst.toLong()),
                    WinDef.UINT(idCmdLast.toLong()), WinDef.UINT(flags.toLong())),
                WinNT.HRESULT::class.java,
            ) as WinNT.HRESULT
        }

        /**
         * 调用 `IContextMenu.InvokeCommand` 执行命令。
         *
         * @param info 命令执行结构体。
         * @return COM 调用结果。
         */
        fun invokeCommand(info: InvokeCommandInfoEx): WinNT.HRESULT {
            return _invokeNativeObject(
                4,
                arrayOf(pointer, info.pointer),
                WinNT.HRESULT::class.java,
            ) as WinNT.HRESULT
        }

        /**
         * 读取 Shell 命令字符串，主要用于拿 canonical verb 辅助生成稳定 ID。
         *
         * @param offset 命令相对偏移量。
         * @param type `GetCommandString` 查询类型。
         * @return 命令字符串；读取失败返回 `null`。
         */
        fun commandString(
            offset: Int,
            type: Int,
        ): String? {
            val buffer = Memory((MAX_COMMAND_STRING_LENGTH * Char.SIZE_BYTES).toLong())
            val result = _invokeNativeObject(
                5,
                arrayOf(pointer, WinDef.UINT_PTR(offset.toLong()), WinDef.UINT(type.toLong()), Pointer.NULL, buffer,
                    WinDef.UINT(MAX_COMMAND_STRING_LENGTH.toLong())),
                WinNT.HRESULT::class.java,
            ) as WinNT.HRESULT
            if (!isSucceeded(result)) return null
            return buffer.getWideString(0).takeIf { value -> value.isNotBlank() }
        }

        /**
         * 查询动态菜单消息处理接口。
         *
         * @return 支持 `IContextMenu2/3` 时返回处理器，否则返回 `null`。
         */
        fun queryMessageHandler(): WindowsContextMenuMessageHandler? {
            listOf(
                IID_I_CONTEXT_MENU3 to WindowsContextMenuMessageHandler.Level.THREE,
                IID_I_CONTEXT_MENU2 to WindowsContextMenuMessageHandler.Level.TWO,
            ).forEach { (iid, level) ->
                val ref = PointerByReference()
                val result = QueryInterface(Guid.REFIID(iid), ref)
                if (isSucceeded(result) && ref.value != null) {
                    return WindowsContextMenuMessageHandler(ref.value, level)
                }
            }
            return null
        }
    }

    /**
     * `IContextMenu2/3` 动态菜单消息处理包装。
     *
     * @param pointer COM 对象指针。
     * @param level 实际拿到的接口级别。
     */
    private class WindowsContextMenuMessageHandler(
        pointer: Pointer,
        private val level: Level,
    ) : Unknown(pointer) {
        /**
         * 通知 Shell 扩展初始化级联子菜单。
         *
         * @param menu 待初始化的子菜单句柄。
         * @param index 子菜单在父菜单中的位置。
         */
        fun handleInitMenuPopup(
            menu: WinDef.HMENU,
            index: Int,
        ) {
            when (level) {
                Level.THREE -> handleMenuMsg2(
                    message = WM_INITMENUPOPUP,
                    wParam = WinDef.WPARAM(Pointer.nativeValue(menu.pointer)),
                    lParam = WinDef.LPARAM(index.toLong()),
                )
                Level.TWO -> handleMenuMsg(
                    message = WM_INITMENUPOPUP,
                    wParam = WinDef.WPARAM(Pointer.nativeValue(menu.pointer)),
                    lParam = WinDef.LPARAM(index.toLong()),
                )
            }
        }

        /**
         * 调用 `IContextMenu2.HandleMenuMsg`。
         *
         * @param message Windows 菜单消息。
         * @param wParam 消息参数。
         * @param lParam 消息参数。
         * @return COM 调用结果。
         */
        private fun handleMenuMsg(
            message: Int,
            wParam: WinDef.WPARAM,
            lParam: WinDef.LPARAM,
        ): WinNT.HRESULT {
            return _invokeNativeObject(
                6,
                arrayOf(pointer, WinDef.UINT(message.toLong()), wParam, lParam),
                WinNT.HRESULT::class.java,
            ) as WinNT.HRESULT
        }

        /**
         * 调用 `IContextMenu3.HandleMenuMsg2`。
         *
         * @param message Windows 菜单消息。
         * @param wParam 消息参数。
         * @param lParam 消息参数。
         * @return COM 调用结果。
         */
        private fun handleMenuMsg2(
            message: Int,
            wParam: WinDef.WPARAM,
            lParam: WinDef.LPARAM,
        ): WinNT.HRESULT {
            val resultRef = PointerByReference()
            return _invokeNativeObject(
                7,
                arrayOf(pointer, WinDef.UINT(message.toLong()), wParam, lParam, resultRef),
                WinNT.HRESULT::class.java,
            ) as WinNT.HRESULT
        }

        /**
         * 动态菜单接口级别。
         */
        enum class Level {
            TWO,
            THREE,
        }
    }

    @Structure.FieldOrder(
        "cbSize",
        "fMask",
        "hwnd",
        "lpVerb",
        "lpParameters",
        "lpDirectory",
        "nShow",
        "dwHotKey",
        "hIcon",
        "lpTitle",
        "lpVerbW",
        "lpParametersW",
        "lpDirectoryW",
        "lpTitleW",
        "ptInvoke",
    )
    /**
     * `CMINVOKECOMMANDINFOEX` 的 JNA 结构体映射。
     */
    private class InvokeCommandInfoEx : Structure() {
        @JvmField var cbSize: Int = size()
        @JvmField var fMask: Int = 0
        @JvmField var hwnd: WinDef.HWND? = null
        @JvmField var lpVerb: Pointer? = null
        @JvmField var lpParameters: Pointer? = null
        @JvmField var lpDirectory: Pointer? = null
        @JvmField var nShow: Int = SW_SHOWNORMAL
        @JvmField var dwHotKey: Int = 0
        @JvmField var hIcon: Pointer? = null
        @JvmField var lpTitle: Pointer? = null
        @JvmField var lpVerbW: Pointer? = null
        @JvmField var lpParametersW: Pointer? = null
        @JvmField var lpDirectoryW: Pointer? = null
        @JvmField var lpTitleW: Pointer? = null
        @JvmField var ptInvoke: WinDef.POINT = WinDef.POINT()
    }

    @Structure.FieldOrder(
        "cbSize",
        "fMask",
        "fType",
        "fState",
        "wID",
        "hSubMenu",
        "hbmpChecked",
        "hbmpUnchecked",
        "dwItemData",
        "dwTypeData",
        "cch",
        "hbmpItem",
    )
    /**
     * `MENUITEMINFO` 的 JNA 结构体映射。
     */
    private class MenuItemInfo : Structure() {
        @JvmField var cbSize: Int = size()
        @JvmField var fMask: Int = 0
        @JvmField var fType: Int = 0
        @JvmField var fState: Int = 0
        @JvmField var wID: Int = 0
        @JvmField var hSubMenu: WinDef.HMENU? = null
        @JvmField var hbmpChecked: Pointer? = null
        @JvmField var hbmpUnchecked: Pointer? = null
        @JvmField var dwItemData: ULONG_PTR = ULONG_PTR(0)
        @JvmField var dwTypeData: Pointer? = null
        @JvmField var cch: Int = 0
        @JvmField var hbmpItem: Pointer? = null
    }

    /**
     * Shell32 中右键菜单读取需要的原生函数。
     */
    private interface Shell32Context : StdCallLibrary {
        /**
         * 解析路径为 PIDL。
         *
         * @param pszName 路径字符串。
         * @param pbc 绑定上下文，本实现传 `null`。
         * @param ppidl 输出 PIDL。
         * @param sfgaoIn 属性查询输入标记。
         * @param psfgaoOut 属性查询输出标记。
         * @return COM 调用结果。
         */
        fun SHParseDisplayName(
            pszName: WString,
            pbc: Pointer?,
            ppidl: PointerByReference,
            sfgaoIn: Int,
            psfgaoOut: IntByReference?,
        ): WinNT.HRESULT

        /**
         * 从完整 PIDL 绑定父 Shell 文件夹并取最后一级子 PIDL。
         *
         * @param pidl 完整 PIDL。
         * @param riid 需要查询的接口 ID。
         * @param ppv 输出父文件夹接口。
         * @param ppidlLast 输出子 PIDL。
         * @return COM 调用结果。
         */
        fun SHBindToParent(
            pidl: Pointer,
            riid: Guid.REFIID,
            ppv: PointerByReference,
            ppidlLast: PointerByReference,
        ): WinNT.HRESULT

        companion object {
            val INSTANCE: Shell32Context = Native.load(
                "shell32",
                Shell32Context::class.java,
                W32APIOptions.UNICODE_OPTIONS,
            )
        }
    }

    /**
     * User32 中原生菜单读取需要的函数。
     */
    private interface User32Menu : StdCallLibrary {
        /**
         * 创建空弹出菜单。
         *
         * @return 菜单句柄；失败返回 `null`。
         */
        fun CreatePopupMenu(): WinDef.HMENU?

        /**
         * 销毁菜单句柄。
         *
         * @param hMenu 需要销毁的菜单句柄。
         * @return 是否销毁成功。
         */
        fun DestroyMenu(hMenu: WinDef.HMENU): Boolean

        /**
         * 获取菜单项数量。
         *
         * @param hMenu 菜单句柄。
         * @return 菜单项数量。
         */
        fun GetMenuItemCount(hMenu: WinDef.HMENU): Int

        /**
         * 按位置读取菜单项信息。
         *
         * @param hMenu 菜单句柄。
         * @param uItem 菜单项索引。
         * @param fByPosition 是否按位置读取。
         * @param lpmii 输出菜单项信息。
         * @return 是否读取成功。
         */
        fun GetMenuItemInfoW(
            hMenu: WinDef.HMENU,
            uItem: Int,
            fByPosition: Boolean,
            lpmii: MenuItemInfo,
        ): Boolean

        /**
         * 按位置读取菜单项文本。
         *
         * @param hMenu 菜单句柄。
         * @param uIDItem 菜单项索引。
         * @param lpString 输出缓冲区。
         * @param cchMax 缓冲区字符数。
         * @param flags 读取标记。
         * @return 写入的字符数。
         */
        fun GetMenuStringW(
            hMenu: WinDef.HMENU,
            uIDItem: Int,
            lpString: CharArray,
            cchMax: Int,
            flags: Int,
        ): Int

        companion object {
            val INSTANCE: User32Menu = Native.load(
                "user32",
                User32Menu::class.java,
                W32APIOptions.UNICODE_OPTIONS,
            )
        }
    }

    companion object {
        const val WINDOWS_COM_ACTION_PREFIX = "windows-com"

        private val IID_I_CONTEXT_MENU = Guid.IID("{000214E4-0000-0000-C000-000000000046}")
        private val IID_I_CONTEXT_MENU2 = Guid.IID("{000214F4-0000-0000-C000-000000000046}")
        private val IID_I_CONTEXT_MENU3 = Guid.IID("{BCFCE0A0-EC17-11D0-8D10-00A0C90F2719}")

        private const val COMMAND_ID_FIRST = 1
        private const val COMMAND_ID_LAST = 0x7FFF
        private const val MAX_MENU_TEXT_LENGTH = 512
        private const val MAX_COMMAND_STRING_LENGTH = 256
        private const val WINDOWS_COM_LIST_TIMEOUT_MS = 1_800L
        private const val WINDOWS_COM_EXECUTE_TIMEOUT_MS = 5_000L

        private const val CMF_NORMAL = 0x00000000
        private const val CMIC_MASK_UNICODE = 0x00004000
        private const val CMIC_MASK_ASYNCOK = 0x00100000
        private const val GCS_VERBW = 0x00000004
        private const val RPC_E_CHANGED_MODE = -2147417850
        private const val SW_SHOWNORMAL = 1
        private const val WM_INITMENUPOPUP = 0x0117

        private const val MF_BYPOSITION = 0x00000400
        private const val MIIM_STATE = 0x00000001
        private const val MIIM_ID = 0x00000002
        private const val MIIM_SUBMENU = 0x00000004
        private const val MIIM_FTYPE = 0x00000100
        private const val MFT_SEPARATOR = 0x00000800
        private const val MFS_DISABLED = 0x00000003

        private fun isSucceeded(result: WinNT.HRESULT): Boolean = result.toInt() >= 0
    }
}
