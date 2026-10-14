package com.oruke.onyx.app.platform

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.SystemFlavorMap

/** 文件剪贴板与系统拖放共用的数据格式，集中维护 Windows 剪切标记与应用内载荷标识。 */
internal object FileClipboardFormats {
    /** 标准文件 URI 列表。 */
    val uriList = DataFlavor("text/uri-list;class=java.lang.String")

    /** GNOME 文件列表及 copy/cut 操作头。 */
    val gnomeFiles = DataFlavor("x-special/gnome-copied-files;class=java.nio.ByteBuffer")

    /** Windows Shell 的 DWORD 传输偏好，以四个小端字节表示。 */
    val preferredDropEffect = DataFlavor("application/x-onyx-drop-effect;class=\"[B\"")

    /** 仅在当前 JVM 内识别 Onyx 完整 VFS 载荷，外部重写剪贴板后标识失效。 */
    val ownerId = DataFlavor("${DataFlavor.javaJVMLocalObjectMimeType};class=java.lang.String")

    /** Windows Shell 的复制操作值。 */
    const val COPY_EFFECT = 1

    /** Windows Shell 的移动操作值，只有明确的移动偏好才解释为剪切。 */
    const val MOVE_EFFECT = 2

    /** 系统格式映射已经完成注册，避免多窗口或多条目发布时重复修改映射表。 */
    private var platformFormatsRegistered = false

    /**
     * 在系统剪贴板取得 Transferable 前注册 Windows 剪切格式，保证首个外部剪切也能被识别。
     * @return 无返回值，已注册或非 Windows 平台不会重复添加映射。
     */
    @Synchronized
    fun registerPlatformFormats() {
        if (platformFormatsRegistered) return
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            val formats = SystemFlavorMap.getDefaultFlavorMap() as SystemFlavorMap
            // AWT 默认不映射 Preferred DropEffect；双向注册才能读写资源管理器的剪切语义。
            formats.addUnencodedNativeForFlavor(preferredDropEffect, "Preferred DropEffect")
            formats.addFlavorForUnencodedNative("Preferred DropEffect", preferredDropEffect)
        }
        platformFormatsRegistered = true
    }
}
