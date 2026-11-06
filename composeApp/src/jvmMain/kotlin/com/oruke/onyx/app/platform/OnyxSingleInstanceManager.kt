package com.oruke.onyx.app.platform

import com.oruke.onyx.BuildConfig
import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.storage.OnyxDataDirectories
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

/**
 * 单实例本地回环 IPC 管理器，支持像 Directory Opus 一样将后续启动请求在数毫秒内转发给已驻留的热实例。
 */
internal object OnyxSingleInstanceManager {
    /** 后台静默预热启动参数，启动后保持主窗口隐藏驻留。 */
    const val BACKGROUND_FLAG = "--background"

    /** 本地回环地址。 */
    private const val LOOPBACK_HOST = "127.0.0.1"

    /** IPC 连接超时（毫秒）。 */
    private const val CONNECT_TIMEOUT_MS = 150

    /** IPC 读写超时（毫秒）。 */
    internal const val READ_TIMEOUT_MS = 500

    /** 服务端监听队列长度。 */
    private const val SERVER_BACKLOG = 16

    /** 唤醒主窗口指令。 */
    internal const val CMD_ACTIVATE = "ACTIVATE"

    /** 在活动面板打开指定路径并唤醒主窗口指令前缀。 */
    internal const val CMD_OPEN = "OPEN"

    /** 探活指令（用于 `--background` 遇到已运行同版本实例时直接退出）。 */
    private const val CMD_PING = "PING"

    /** 请求旧版本驻留进程退出指令。 */
    internal const val CMD_SHUTDOWN = "SHUTDOWN"

    /** 服务端处理成功响应。 */
    internal const val RESPONSE_OK = "OK"

    /** 默认端点文件路径。 */
    internal val defaultEndpointFile: Path
        get() = OnyxDataDirectories.stateDirectory().resolve("instance.endpoint")

    /**
     * 判断启动参数是否请求后台静默驻留模式。
     *
     * @param args 命令行参数。
     * @return 若包含 `--background` 则返回 `true`。
     */
    fun isBackgroundLaunch(args: Array<String>): Boolean = args.any { arg -> arg == BACKGROUND_FLAG }

    /**
     * 从命令行参数中提取首个非选项路径参数。
     *
     * @param args 命令行参数。
     * @return 待打开的初始路径；未提供时返回 `null`。
     */
    fun extractInitialLocation(args: Array<String>): String? =
        args.firstOrNull { arg -> arg.isNotBlank() && !arg.startsWith("--") }

    /**
     * 尝试将当前进程启动请求转发给已运行的常驻实例。
     *
     * - 若已运行实例与当前构建指纹一致，发送唤醒或打开路径指令并返回 `true`（当前进程立即退出，耗时 < 10ms）。
     * - 若已运行实例版本或构建指纹不同（例如应用刚完成升级），发送退出指令让旧实例关闭，并返回 `false` 由当前新进程接管。
     *
     * @param args 进程启动参数。
     * @param endpointFile IPC 端点文件路径。
     * @param currentFingerprint 当前进程构建指纹。
     * @return 已成功转发给常驻实例并可立即退出当前进程时返回 `true`。
     */
    fun tryForwardToRunningInstance(
        args: Array<String>,
        endpointFile: Path = defaultEndpointFile,
        currentFingerprint: String = currentBuildFingerprint(),
    ): Boolean {
        val endpoint = readEndpoint(endpointFile) ?: return false
        if (endpoint.fingerprint != currentFingerprint) {
            sendCommand(endpoint.port, CMD_SHUTDOWN)
            runCatching { Files.deleteIfExists(endpointFile) }
            return false
        }
        val location = extractInitialLocation(args)
        val command = when {
            isBackgroundLaunch(args) -> CMD_PING
            location != null -> "$CMD_OPEN\t$location"
            else -> CMD_ACTIVATE
        }
        val forwarded = sendCommand(endpoint.port, command)
        if (!forwarded) {
            runCatching { Files.deleteIfExists(endpointFile) }
        }
        return forwarded
    }

    /**
     * 启动本地回环 IPC 服务端并写入端点文件，接收后续进程发来的秒起唤醒请求。
     *
     * @param endpointFile IPC 端点文件路径。
     * @param currentFingerprint 当前进程构建指纹。
     * @param onActivate 收到唤醒请求时的 EDT 回调，参数为可选的待打开目录路径。
     * @param onShutdown 收到版本升级替换退出请求时的 EDT 回调。
     * @return 用于关闭监听并清理端点文件的句柄。
     */
    @Suppress("TooGenericExceptionCaught")
    fun startServer(
        endpointFile: Path = defaultEndpointFile,
        currentFingerprint: String = currentBuildFingerprint(),
        onActivate: (String?) -> Unit,
        onShutdown: () -> Unit,
    ): AutoCloseable {
        if (endpointFile == defaultEndpointFile && isSandboxedWindowsDesktop()) {
            OnyxLogger.info("Startup", "检测到非 Default 沙箱桌面环境，跳过全局单实例端点注册")
            return AutoCloseable {}
        }
        val serverSocket = try {
            ServerSocket(0, SERVER_BACKLOG, InetAddress.getByName(LOOPBACK_HOST))
        } catch (failure: Exception) {
            OnyxLogger.warn("Startup", "无法绑定单实例回环端口，降级为普通多进程模式", failure)
            return AutoCloseable {}
        }
        val port = serverSocket.localPort
        writeEndpoint(endpointFile, port, currentFingerprint)
        thread(
            start = true,
            isDaemon = true,
            name = "onyx-single-instance-ipc",
        ) {
            acceptLoop(serverSocket, onActivate, onShutdown)
        }
        return AutoCloseable {
            runCatching { serverSocket.close() }
            val current = readEndpoint(endpointFile)
            if (current?.port == port) {
                runCatching { Files.deleteIfExists(endpointFile) }
            }
        }
    }

    /**
     * 计算当前进程的构建指纹（版本号 + 代码源最后修改时间），确保升级或重编译后自动替换旧驻留进程。
     *
     * @return 构建指纹字符串。
     */
    internal fun currentBuildFingerprint(): String {
        val timestamp = runCatching {
            val location = OnyxSingleInstanceManager::class.java.protectionDomain?.codeSource?.location
            if (location != null) {
                Files.getLastModifiedTime(Path.of(location.toURI())).toMillis()
            } else {
                0L
            }
        }.getOrDefault(0L)
        return "${BuildConfig.VERSION}:$timestamp"
    }

    /** 持续监听并响应本地回环连接。 */
    @Suppress("TooGenericExceptionCaught")
    private fun acceptLoop(
        serverSocket: ServerSocket,
        onActivate: (String?) -> Unit,
        onShutdown: () -> Unit,
    ) {
        while (!serverSocket.isClosed) {
            val client = try {
                serverSocket.accept()
            } catch (_: Exception) {
                break
            }
            try {
                handleClientSocket(client, onActivate, onShutdown)
            } catch (failure: Exception) {
                OnyxLogger.warn("Startup", "处理单实例唤醒请求失败", failure)
            }
        }
    }

    /** 向指定本地端口发送单行命令并校验 `OK` 响应。 */
    private fun sendCommand(port: Int, command: String): Boolean = runCatching {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = READ_TIMEOUT_MS
            socket.connect(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), port), CONNECT_TIMEOUT_MS)
            val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            writer.write("$command\n")
            writer.flush()
            reader.readLine()?.trim() == RESPONSE_OK
        }
    }.getOrDefault(false)

    /** 读取并解析端点文件。 */
    private fun readEndpoint(endpointFile: Path): InstanceEndpoint? = runCatching {
        if (!Files.isRegularFile(endpointFile)) return null
        val raw = Files.readString(endpointFile, StandardCharsets.UTF_8).trim()
        val separatorIndex = raw.indexOf('|')
        if (separatorIndex <= 0) return null
        val port = raw.substring(0, separatorIndex).toIntOrNull() ?: return null
        val fingerprint = raw.substring(separatorIndex + 1)
        if (port !in MIN_VALID_PORT..MAX_VALID_PORT || fingerprint.isBlank()) return null
        InstanceEndpoint(port = port, fingerprint = fingerprint)
    }.getOrNull()

    /** 将监听端口与构建指纹写入端点文件。 */
    private fun writeEndpoint(endpointFile: Path, port: Int, fingerprint: String) {
        runCatching {
            endpointFile.parent?.let(Files::createDirectories)
            Files.writeString(
                endpointFile,
                "$port|$fingerprint",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        }
    }

    /** 有效 TCP 端口下界。 */
    private const val MIN_VALID_PORT = 1

    /** 有效 TCP 端口上界。 */
    private const val MAX_VALID_PORT = 65_535

    /** 已解析的实例端点信息。 */
    private data class InstanceEndpoint(
        /** 监听端口。 */
        val port: Int,
        /** 构建指纹。 */
        val fingerprint: String,
    )
}

/** 处理单个本地回环客户端连接，立即返回 `OK` 并在 Swing EDT 上派发窗口唤醒或退出动作。 */
private fun handleClientSocket(
    client: Socket,
    onActivate: (String?) -> Unit,
    onShutdown: () -> Unit,
) {
    client.use { socket ->
        socket.soTimeout = OnyxSingleInstanceManager.READ_TIMEOUT_MS
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
        val line = reader.readLine()?.trim().orEmpty()
        writer.write("${OnyxSingleInstanceManager.RESPONSE_OK}\n")
        writer.flush()
        when {
            line == OnyxSingleInstanceManager.CMD_ACTIVATE -> SwingUtilities.invokeLater { onActivate(null) }
            line.startsWith("${OnyxSingleInstanceManager.CMD_OPEN}\t") -> {
                val path = line.substringAfter('\t').takeIf(String::isNotBlank)
                SwingUtilities.invokeLater { onActivate(path) }
            }
            line == OnyxSingleInstanceManager.CMD_SHUTDOWN -> SwingUtilities.invokeLater(onShutdown)
        }
    }
}

/** `GetUserObjectInformationW` 的对象名称查询索引（`UOI_NAME`）。 */
private const val UOI_NAME_INDEX = 2

/** 桌面名称缓冲区字符数。 */
private const val DESKTOP_NAME_MAX_CHARS = 128

/** 用于查询当前线程所属 Windows 桌面名称的最小 Win32 `user32` 绑定。 */
@Suppress("FunctionName")
private interface Win32DesktopUser32 : StdCallLibrary {
    /** 获取指定线程关联的桌面句柄。 */
    fun GetThreadDesktop(dwThreadId: Int): Pointer?

    /** 读取内核用户对象信息（如桌面名）。 */
    fun GetUserObjectInformationW(
        hObj: Pointer,
        nIndex: Int,
        pvInfo: CharArray,
        nLength: Int,
        lpnLengthNeeded: IntByReference,
    ): Boolean

    companion object {
        /** `user32` 单例实例。 */
        val INSTANCE: Win32DesktopUser32 by lazy {
            Native.load("user32", Win32DesktopUser32::class.java)
        }
    }
}

/** 检测当前 Windows 进程是否运行在非交互式沙箱桌面（如 `exebox-*`）。 */
internal fun isSandboxedWindowsDesktop(): Boolean = runCatching {
    val osName = System.getProperty("os.name").orEmpty()
    if (!osName.startsWith("Windows", ignoreCase = true)) return false
    val threadId = Kernel32.INSTANCE.GetCurrentThreadId()
    val desktop = Win32DesktopUser32.INSTANCE.GetThreadDesktop(threadId) ?: return false
    val buffer = CharArray(DESKTOP_NAME_MAX_CHARS)
    val needed = IntByReference()
    val ok = Win32DesktopUser32.INSTANCE.GetUserObjectInformationW(
        desktop,
        UOI_NAME_INDEX,
        buffer,
        buffer.size * Char.SIZE_BYTES,
        needed,
    )
    if (!ok) return false
    val desktopName = String(buffer).substringBefore('\u0000')
    desktopName.isNotBlank() && !desktopName.equals("Default", ignoreCase = true)
}.getOrDefault(false)

/**
 * 早期单实例 IPC 中继器，在 `main()` 冷启动首毫秒即抢先绑定单实例端口，
 * 拦截用户在冷启动期间的重复双击并立即触发启动动画脉冲反馈，待主窗口管理器就绪后平滑移交控制权。
 */
internal class OnyxSingleInstanceRelay(
    endpointFile: Path = OnyxSingleInstanceManager.defaultEndpointFile,
    currentFingerprint: String = OnyxSingleInstanceManager.currentBuildFingerprint(),
    onEarlyRepeatedActivate: () -> Unit = OnyxStartupSplash::notifyRepeatedActivation,
) : AutoCloseable {
    /** 同步锁。 */
    private val lock = Any()

    /** 已绑定的主窗口唤醒回调。 */
    private var activateHandler: ((String?) -> Unit)? = null

    /** 已绑定的应用退出回调。 */
    private var shutdownHandler: (() -> Unit)? = null

    /** 冷启动期间收到的待打开目录（若有）。 */
    private var pendingOpenLocation: String? = null

    /** 冷启动期间是否收到过外部唤醒请求。 */
    private var pendingActivation: Boolean = false

    /** 底层 IPC 服务端句柄。 */
    private val serverHandle: AutoCloseable = OnyxSingleInstanceManager.startServer(
        endpointFile = endpointFile,
        currentFingerprint = currentFingerprint,
        onActivate = { location ->
            val handler = synchronized(lock) {
                val bound = activateHandler
                if (bound == null) {
                    pendingActivation = true
                    if (!location.isNullOrBlank()) {
                        pendingOpenLocation = location
                    }
                }
                bound
            }
            if (handler != null) {
                handler(location)
            } else {
                onEarlyRepeatedActivate()
            }
        },
        onShutdown = {
            val handler = synchronized(lock) { shutdownHandler }
            if (handler != null) {
                handler()
            } else {
                close()
            }
        },
    )

    /**
     * 绑定 Compose 主窗口管理器的唤醒与退出回调，并立即重放冷启动期间缓存的唤醒路径。
     *
     * @param onActivate 主窗口唤醒回调。
     * @param onShutdown 应用完全退出回调。
     */
    fun bindWindowCallbacks(
        onActivate: (String?) -> Unit,
        onShutdown: () -> Unit,
    ) {
        val replayLocation: String?
        val shouldReplay: Boolean
        synchronized(lock) {
            activateHandler = onActivate
            shutdownHandler = onShutdown
            replayLocation = pendingOpenLocation
            shouldReplay = pendingActivation
            pendingOpenLocation = null
            pendingActivation = false
        }
        if (shouldReplay) {
            SwingUtilities.invokeLater {
                onActivate(replayLocation)
            }
        }
    }

    override fun close() {
        serverHandle.close()
    }
}


