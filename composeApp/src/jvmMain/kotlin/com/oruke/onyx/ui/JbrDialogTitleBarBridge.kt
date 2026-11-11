package com.oruke.onyx.ui

import java.awt.Dialog
import java.awt.Window
import java.lang.reflect.Method

/**
 * 封装 JetBrains Runtime (JBR) 针对模态 [Dialog] 的自定义沉浸式标题栏反射桥接。
 *
 * `jbr-api` 在 Jewel 中为 `compileOnly` 依赖，通过一次性解析并缓存方法句柄，既能在 JBR 运行时启用与
 * `DecoratedWindow` 同源的无蓝框系统标题栏，又能在标准 OpenJDK 下安全降级为无框自绘窗口。
 */
internal object JbrDialogTitleBarBridge {

    private val handles: ReflectionHandles? by lazy { resolveReflectionHandles() }

    /**
     * 判断当前平台与 JVM 运行时是否支持 JBR [Dialog] 自定义标题栏装饰。
     */
    fun isSupported(): Boolean {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        val isWindowsOrMac = osName.contains("windows") || osName.contains("mac")
        return isWindowsOrMac && handles != null
    }

    /**
     * 创建一个绑定的 JBR 自定义标题栏控制器；不支持时返回 `null`。
     */
    fun createTitleBar(): JbrCustomDialogTitleBar? = handles?.let { activeHandles ->
        runCatching {
            val decorations = activeHandles.getWindowDecorations.invoke(null)
            val instance = decorations?.let { activeHandles.createCustomTitleBar.invoke(it) }
            if (decorations != null && instance != null) {
                JbrCustomDialogTitleBar(activeHandles, decorations, instance)
            } else {
                null
            }
        }.getOrNull()
    }

    /**
     * 在支持 JBR 窗口移动扩展的平台上启动跟随鼠标移动窗口。
     *
     * @param window 目标窗口。
     * @param mouseButton AWT 鼠标按键常量。
     * @return 若成功交由 JBR 移动则返回 `true`。
     */
    fun tryStartMovingWithMouse(window: Window, mouseButton: Int): Boolean = runCatching {
        val jbrClass = Class.forName("com.jetbrains.JBR")
        val available = jbrClass.getMethod("isAvailable").invoke(null) as? Boolean ?: false
        val moveSupported = jbrClass.getMethod("isWindowMoveSupported").invoke(null) as? Boolean ?: false
        if (!available || !moveSupported) {
            false
        } else {
            val windowMove = jbrClass.getMethod("getWindowMove").invoke(null)
            val moveClass = Class.forName("com.jetbrains.WindowMove")
            moveClass
                .getMethod("startMovingTogetherWithMouse", Window::class.java, Int::class.javaPrimitiveType)
                .invoke(windowMove, window, mouseButton)
            true
        }
    }.getOrDefault(false)

    private fun resolveReflectionHandles(): ReflectionHandles? = runCatching {
        val jbrClass = Class.forName("com.jetbrains.JBR")
        val isAvailable = jbrClass.getMethod("isAvailable").invoke(null) as? Boolean ?: false
        val isSupported = jbrClass.getMethod("isWindowDecorationsSupported").invoke(null) as? Boolean ?: false
        if (!isAvailable || !isSupported) {
            null
        } else {
            val decorationsClass = Class.forName("com.jetbrains.WindowDecorations")
            val customTitleBarClass = Class.forName("com.jetbrains.WindowDecorations\$CustomTitleBar")
            ReflectionHandles(
                getWindowDecorations = jbrClass.getMethod("getWindowDecorations"),
                createCustomTitleBar = decorationsClass.getMethod("createCustomTitleBar"),
                setDialogCustomTitleBar = decorationsClass.getMethod(
                    "setCustomTitleBar",
                    Dialog::class.java,
                    customTitleBarClass,
                ),
                setHeight = customTitleBarClass.getMethod("setHeight", Float::class.javaPrimitiveType),
                putProperty = customTitleBarClass.getMethod("putProperty", String::class.java, Any::class.java),
                getLeftInset = customTitleBarClass.getMethod("getLeftInset"),
                getRightInset = customTitleBarClass.getMethod("getRightInset"),
                forceHitTest = customTitleBarClass.getMethod("forceHitTest", Boolean::class.javaPrimitiveType),
            )
        }
    }.getOrNull()
}

/**
 * 单个对话框持有的 JBR 自定义标题栏包装实例。
 */
internal class JbrCustomDialogTitleBar(
    private val handles: ReflectionHandles,
    private val decorationsInstance: Any,
    private val titleBarInstance: Any,
) {
    /** 左侧系统控制按钮避让宽度（逻辑像素）。 */
    val leftInset: Float
        get() = (handles.getLeftInset.invoke(titleBarInstance) as? Number)?.toFloat() ?: 0f

    /** 右侧系统控制按钮避让宽度（逻辑像素）。 */
    val rightInset: Float
        get() = (handles.getRightInset.invoke(titleBarInstance) as? Number)?.toFloat() ?: 0f

    /**
     * 将自定义标题栏高度、明暗按钮属性应用并绑定到目标 [dialog]。
     */
    fun bindToDialog(dialog: Dialog, heightDp: Float, darkControls: Boolean) {
        handles.setHeight.invoke(titleBarInstance, heightDp)
        handles.putProperty.invoke(titleBarInstance, "controls.dark", darkControls)
        handles.setDialogCustomTitleBar.invoke(decorationsInstance, dialog, titleBarInstance)
    }

    /**
     * 通知 JBR 标题栏当前指针事件由客户端控件处理还是交由系统执行窗口拖拽。
     */
    fun forceHitTest(clientAreaControl: Boolean) {
        handles.forceHitTest.invoke(titleBarInstance, clientAreaControl)
    }
}

internal class ReflectionHandles(
    val getWindowDecorations: Method,
    val createCustomTitleBar: Method,
    val setDialogCustomTitleBar: Method,
    val setHeight: Method,
    val putProperty: Method,
    val getLeftInset: Method,
    val getRightInset: Method,
    val forceHitTest: Method,
)
