package com.oruke.onyx.app.platform.storage

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.mac.CoreFoundation
import com.sun.jna.platform.mac.DiskArbitration
import java.lang.ref.Reference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/** macOS 独立 Core Foundation 事件循环，原生回调只发信号，不跨线程传递磁盘对象。 */
internal object MacStorageRunLoop {
    /** 延迟加载 Core Foundation，其他系统不能在类装配时加载 macOS 动态库。 */
    val foundation: MacStorageFoundationLibrary by lazy {
        Native.load("CoreFoundation", MacStorageFoundationLibrary::class.java)
    }
    /** 原生磁盘变化回调接口。 */
    private val arbitration: MacStorageNotificationLibrary by lazy {
        Native.load("DiskArbitration", MacStorageNotificationLibrary::class.java)
    }

    /** @return 系统磁盘出现、消失、改名和挂载状态变化的通知流。 */
    fun observe(): Flow<Unit> = callbackFlow {
        val closed = AtomicBoolean()
        val loop = AtomicReference<Pointer?>()
        val worker =
            thread(name = "onyx-disk-arbitration", isDaemon = true) {
                runCatching { observeDisks(closed, loop) { trySend(Unit) } }.onFailure { close(it) }
            }
        awaitClose {
            closed.set(true)
            loop.get()?.let(foundation::CFRunLoopStop)
            worker.join(CLOSE_WAIT_MILLIS)
        }
    }
        .flowOn(Dispatchers.IO)

    /**
     * 注册并运行当前订阅的磁盘通知，退出时移除回调并释放会话。
     *
     * @param closed 订阅是否已经取消。
     * @param loop 当前订阅的原生事件循环引用。
     * @param changed 普通变化信号回调。
     */
    private fun observeDisks(
        closed: AtomicBoolean,
        loop: AtomicReference<Pointer?>,
        changed: () -> Unit,
    ) {
        val session = checkNotNull(DiskArbitration.INSTANCE.DASessionCreate(null))
        val mode =
            NativeLibrary.getInstance("CoreFoundation")
                .getGlobalVariableAddress("kCFRunLoopDefaultMode")
                .getPointer(0)
        val current = foundation.CFRunLoopGetCurrent()
        val appearance = MacStorageDiskCallback { _, _ -> changed() }
        val description = MacStorageDescriptionCallback { _, _, _ -> changed() }
        try {
            arbitration.DARegisterDiskAppearedCallback(session.pointer, null, appearance, null)
            arbitration.DARegisterDiskDisappearedCallback(session.pointer, null, appearance, null)
            arbitration.DARegisterDiskDescriptionChangedCallback(
                session.pointer,
                null,
                null,
                description,
                null,
            )
            arbitration.DASessionScheduleWithRunLoop(session.pointer, current, mode)
            loop.set(current)
            while (!closed.get()) foundation.CFRunLoopRunInMode(mode, RUN_LOOP_SLICE_SECONDS, 1)
        } finally {
            loop.set(null)
            arbitration.DASessionUnscheduleFromRunLoop(session.pointer, current, mode)
            arbitration.DAUnregisterCallback(session.pointer, appearance, null)
            arbitration.DAUnregisterCallback(session.pointer, description, null)
            CoreFoundation.INSTANCE.CFRelease(session)
            // JNA 回调由 native 持有指针，必须保留强引用直到最后一次注销完成。
            Reference.reachabilityFence(appearance)
            Reference.reachabilityFence(description)
        }
    }

    /** 事件循环定期检查取消，原生停止信号丢失时也能退出。 */
    private const val RUN_LOOP_SLICE_SECONDS = 0.5
    /** 释放订阅时允许的最长线程退出等待时间。 */
    private const val CLOSE_WAIT_MILLIS = 1_000L
}

/** 单个磁盘出现或消失的原生回调签名。 */
internal fun interface MacStorageDiskCallback : Callback {
    /**
     * 仅在原生循环线程接收事件。
     *
     * @param disk 系统借用的磁盘引用，不能保存。
     * @param context 注册时的上下文。
     */
    fun invoke(disk: Pointer?, context: Pointer?)
}

/** 磁盘描述变化的原生回调签名。 */
internal fun interface MacStorageDescriptionCallback : Callback {
    /**
     * 接收卷名、挂载状态和其他描述变化。
     *
     * @param disk 系统借用的磁盘引用。
     * @param keys 本次变化的描述键。
     * @param context 注册时的上下文。
     */
    fun invoke(disk: Pointer?, keys: Pointer?, context: Pointer?)
}

/** Disk Arbitration 中 JNA 平台库尚未声明的事件 API。 */
internal interface MacStorageNotificationLibrary : Library {
    /**
     * 将会话加入专用循环。
     *
     * @param session 磁盘会话。
     * @param loop 目标事件循环。
     * @param mode 循环模式。
     */
    fun DASessionScheduleWithRunLoop(session: Pointer, loop: Pointer, mode: Pointer)

    /**
     * 从专用循环移除会话。
     *
     * @param session 磁盘会话。
     * @param loop 已注册循环。
     * @param mode 已注册模式。
     */
    fun DASessionUnscheduleFromRunLoop(session: Pointer, loop: Pointer, mode: Pointer)

    /**
     * 观察磁盘出现。
     *
     * @param session 磁盘会话。
     * @param match 可选匹配字典。
     * @param callback 事件回调。
     * @param context 可选上下文。
     */
    fun DARegisterDiskAppearedCallback(
        session: Pointer,
        match: Pointer?,
        callback: MacStorageDiskCallback,
        context: Pointer?,
    )

    /**
     * 观察磁盘移除。
     *
     * @param session 磁盘会话。
     * @param match 可选匹配字典。
     * @param callback 事件回调。
     * @param context 可选上下文。
     */
    fun DARegisterDiskDisappearedCallback(
        session: Pointer,
        match: Pointer?,
        callback: MacStorageDiskCallback,
        context: Pointer?,
    )

    /**
     * 观察磁盘描述变化。
     *
     * @param session 磁盘会话。
     * @param match 可选匹配字典。
     * @param keys 可选描述键集合。
     * @param callback 事件回调。
     * @param context 可选上下文。
     */
    fun DARegisterDiskDescriptionChangedCallback(
        session: Pointer,
        match: Pointer?,
        keys: Pointer?,
        callback: MacStorageDescriptionCallback,
        context: Pointer?,
    )

    /**
     * 移除会话中与函数和上下文匹配的全部回调。
     *
     * @param session 磁盘会话。
     * @param callback 已注册的回调。
     * @param context 注册时的上下文。
     */
    fun DAUnregisterCallback(session: Pointer, callback: Callback, context: Pointer?)
}

/** 本次设备观察所需的 Core Foundation API。 */
internal interface MacStorageFoundationLibrary : Library {
    /** @return 当前线程的原生事件循环。 */
    fun CFRunLoopGetCurrent(): Pointer

    /**
     * 运行一次有时间预算的事件循环。
     *
     * @param mode 当前循环模式。
     * @param seconds 最长运行秒数。
     * @param returnAfterSourceHandled 处理事件后是否立即返回。
     * @return 原生循环返回原因。
     */
    fun CFRunLoopRunInMode(mode: Pointer, seconds: Double, returnAfterSourceHandled: Byte): Int

    /**
     * 唤醒并停止指定循环。
     *
     * @param loop 本订阅持有的事件循环。
     */
    fun CFRunLoopStop(loop: Pointer)

    /**
     * 将系统 UUID 复制为标准文本。
     *
     * @param allocator 可选内存分配器。
     * @param uuid 借用的 UUID 引用。
     * @return 由调用方释放的字符串引用。
     */
    fun CFUUIDCreateString(allocator: Pointer?, uuid: Pointer): Pointer?
}
