package com.oruke.onyx.app.platform

import com.oruke.onyx.BuildConfig
import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.startupElapsedMillis
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.MouseInfo
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Arc2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.RoundRectangle2D
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 应用冷启动阶段枚举，用于驱动启动动画进度条与阶段提示文案。
 *
 * @property targetProgress 当前阶段对应的目标进度比例（0..1）。
 * @property statusText 当前阶段展示给用户的状态说明。
 */
@Suppress("MagicNumber")
internal enum class StartupSplashStage(
    val targetProgress: Float,
    val statusText: String,
) {
    /** 进程入口已就绪，开始启动。 */
    LAUNCHING(0.18f, "正在启动 Onyx..."),

    /** 后台预热 SQLite 设置、会话、磁盘设备与依赖图。 */
    PRELOADING_STORAGE(0.45f, "正在预热本地存储与会话..."),

    /** 初始化 Skia/Direct3D 渲染层与 Jewel 主题。 */
    INITIALIZING_UI(0.72f, "正在初始化图形引擎与主题..."),

    /** 装配根组件并恢复双面板目录。 */
    RESTORING_WORKSPACE(0.90f, "正在恢复工作区与文件面板..."),

    /** 主窗口首帧已就绪，准备淡出关闭。 */
    READY(1.0f, "准备就绪"),
}

/**
 * 启动动画状态快照（供单元测试与运行期观测验证）。
 *
 * @property stage 当前启动阶段。
 * @property repeatedActivationCount 启动期间收到用户重复点击/唤醒的次数。
 * @property dismissed 是否已请求关闭启动动画。
 */
internal data class StartupSplashSnapshot(
    val stage: StartupSplashStage = StartupSplashStage.LAUNCHING,
    val repeatedActivationCount: Int = 0,
    val dismissed: Boolean = false,
)

/**
 * 极速原生 AWT 启动动画控制器。
 *
 * 在 Compose Desktop / Skiko Direct3D 12 与 Koin 完成重型初始化前（约 150ms 内）
 * 立即在屏幕中央呈现高帧率矢量启动卡片，并在用户重复点击图标时给予脉冲波纹与文案反馈，
 * 避免用户在冷启动空窗期误以为程序卡死而连续点击。
 */
internal object OnyxStartupSplash {
    /** 状态快照原子引用。 */
    private val snapshotRef = AtomicReference(StartupSplashSnapshot())

    /** 缓存的应用图标图像。 */
    private val logoImageRef = AtomicReference<Image?>(null)

    /** 当前活动窗口宿主（仅在 EDT 访问或通过原子引用传递）。 */
    private val hostRef = AtomicReference<StartupSplashWindowHost?>(null)

    /**
     * 在交互式桌面启动时立即显示启动动画窗口；后台静默驻留模式（`--background`）、无头测试或沙箱桌面自动跳过。
     *
     * @param args 进程启动参数。
     */
    fun showIfInteractive(args: Array<String>) {
        if (OnyxSingleInstanceManager.isBackgroundLaunch(args)) return
        snapshotRef.set(StartupSplashSnapshot(stage = StartupSplashStage.LAUNCHING))
        if (GraphicsEnvironment.isHeadless() || isSandboxedWindowsDesktop()) return
        SwingUtilities.invokeLater {
            if (snapshotRef.get().dismissed) return@invokeLater
            val existing = hostRef.get()
            if (existing != null) return@invokeLater
            val created = StartupSplashWindowHost(logoImageRef.get())
            if (hostRef.compareAndSet(null, created)) {
                created.showWindow()
                OnyxLogger.info("Startup", "启动动画窗口已显示 (elapsed=${startupElapsedMillis()}ms)")
            }
        }
    }

    /**
     * 推进启动动画阶段；仅向后推进，不回退已完成阶段。
     *
     * @param stage 新启动阶段。
     */
    fun updateStage(stage: StartupSplashStage) {
        val updated = snapshotRef.updateAndGet { current ->
            if (current.dismissed || stage.ordinal <= current.stage.ordinal) {
                current
            } else {
                current.copy(stage = stage)
            }
        }
        SwingUtilities.invokeLater {
            hostRef.get()?.onStageChanged(updated.stage)
        }
    }

    /**
     * 注入后台预解码完成的应用 Logo 图像，立即刷新启动动画中心图标。
     *
     * @param image 已解码的图标图像。
     */
    fun updateLogoImage(image: Image?) {
        if (image == null) return
        logoImageRef.set(image)
        SwingUtilities.invokeLater {
            hostRef.get()?.onLogoLoaded(image)
        }
    }

    /**
     * 当用户在冷启动期间再次双击桌面图标或点击启动卡片时触发脉冲动画与安抚提示。
     */
    fun notifyRepeatedActivation() {
        val updated = snapshotRef.updateAndGet { current ->
            if (current.dismissed) {
                current
            } else {
                current.copy(repeatedActivationCount = current.repeatedActivationCount + 1)
            }
        }
        if (updated.dismissed) return
        SwingUtilities.invokeLater {
            hostRef.get()?.onRepeatedActivation()
        }
    }

    /**
     * 在主窗口首帧渲染完成后平滑淡出并销毁启动动画窗口。
     */
    fun dismiss() {
        val previous = snapshotRef.getAndUpdate { current ->
            current.copy(stage = StartupSplashStage.READY, dismissed = true)
        }
        if (previous.dismissed) return
        SwingUtilities.invokeLater {
            val host = hostRef.getAndSet(null) ?: return@invokeLater
            host.beginFadeOutAndDispose()
        }
    }

    /**
     * 获取当前启动动画状态快照（仅供测试断言与内部诊断）。
     *
     * @return 当前启动动画状态快照。
     */
    internal fun currentSnapshotForTest(): StartupSplashSnapshot = snapshotRef.get()

    /**
     * 重置测试状态并关闭可能存在的窗口。
     */
    internal fun resetForTest() {
        snapshotRef.set(StartupSplashSnapshot())
        SwingUtilities.invokeLater {
            hostRef.getAndSet(null)?.disposeImmediately()
        }
    }
}

/**
 * 承载无边框透明 AWT [JWindow] 与 60fps 动画计时器的内部宿主。
 */
@Suppress("MagicNumber")
private class StartupSplashWindowHost(initialLogo: Image?) {
    /** 画布组件。 */
    private val canvas = StartupSplashCanvas(initialLogo)

    /** 无边框透明启动窗口。 */
    private val window = JWindow().apply {
        background = Color(0, 0, 0, 0)
        isAlwaysOnTop = true
        focusableWindowState = false
        contentPane.add(canvas)
        pack()
        centerOnActiveScreen(this)
        addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent?) {
                    onRepeatedActivation()
                }
            },
        )
    }

    /** 是否已进入淡出阶段。 */
    private var fadingOut = false

    /** 当前窗口不透明度。 */
    private var windowOpacity = 1.0f

    /** 已运行帧数，用于超时保护。 */
    private var elapsedTicks = 0

    /** 60fps 动画驱动计时器。 */
    private val animationTimer = Timer(FRAME_INTERVAL_MS) {
        onAnimationTick()
    }

    /** 显示启动窗口并启动动画循环。 */
    fun showWindow() {
        runCatching {
            window.isVisible = true
            animationTimer.start()
        }.onFailure { failure ->
            OnyxLogger.warn("Startup", "显示启动动画窗口失败", failure)
            disposeImmediately()
        }
    }

    /** 更新阶段目标进度与文案。 */
    fun onStageChanged(stage: StartupSplashStage) {
        canvas.setStage(stage)
    }

    /** 更新中心图标。 */
    fun onLogoLoaded(image: Image) {
        canvas.setLogoImage(image)
    }

    /** 响应重复点击唤醒，置前启动卡片并触发脉冲光环。 */
    fun onRepeatedActivation() {
        if (fadingOut) return
        runCatching {
            window.toFront()
            canvas.triggerRepeatedClickPulse()
        }
    }

    /** 启动平滑淡出并在结束后销毁窗口。 */
    fun beginFadeOutAndDispose() {
        canvas.setStage(StartupSplashStage.READY)
        fadingOut = true
    }

    /** 立即停止计时器并销毁窗口。 */
    fun disposeImmediately() {
        animationTimer.stop()
        runCatching {
            window.isVisible = false
            window.dispose()
        }
    }

    /** 单帧动画更新。 */
    private fun onAnimationTick() {
        elapsedTicks++
        if (elapsedTicks > MAX_SAFETY_TICKS) {
            disposeImmediately()
            return
        }
        canvas.stepAnimation(fadingOut)
        if (fadingOut) {
            windowOpacity = (windowOpacity - FADE_OUT_STEP).coerceAtLeast(0f)
            runCatching { window.opacity = windowOpacity }
            if (windowOpacity <= MIN_VISIBLE_OPACITY) {
                disposeImmediately()
            }
        }
    }

    private companion object {
        /** 约 60fps 的帧间隔（毫秒）。 */
        const val FRAME_INTERVAL_MS = 16

        /** 每次淡出递减的不透明度步长（约 130ms 完成淡出）。 */
        const val FADE_OUT_STEP = 0.12f

        /** 视为完全透明的最小阈值。 */
        const val MIN_VISIBLE_OPACITY = 0.02f

        /** 最长兜底保护帧数（约 12 秒），防止异常未回调导致残留。 */
        const val MAX_SAFETY_TICKS = 750
    }
}

/**
 * 绘制 Onyx 深邃黑曜石风格启动卡片、双轨道旋转光环、脉冲反馈与流光进度条的画布。
 */
@Suppress("MagicNumber")
private class StartupSplashCanvas(
    private var logoImage: Image?,
) : JComponent() {
    /** 当前阶段。 */
    private var currentStage: StartupSplashStage = StartupSplashStage.LAUNCHING

    /** 平滑插值后的展示进度（0..1）。 */
    private var displayedProgress: Float = StartupSplashStage.LAUNCHING.targetProgress

    /** 动画相位角（弧度）。 */
    private var phaseRadians: Double = 0.0

    /** 重复点击触发的脉冲波进度（< 0 表示未激活，0..1 表示扩散中）。 */
    private var pulseProgress: Float = -1f

    /** 重复点击安抚文案剩余展示帧数。 */
    private var repeatedBannerTicks: Int = 0

    init {
        isOpaque = false
        preferredSize = Dimension(WINDOW_WIDTH, WINDOW_HEIGHT)
    }

    /** 设置当前启动阶段。 */
    fun setStage(stage: StartupSplashStage) {
        currentStage = stage
        repaint()
    }

    /** 设置预加载完成的图标。 */
    fun setLogoImage(image: Image) {
        logoImage = image
        repaint()
    }

    /** 触发重复点击扩散光环与提示文案。 */
    fun triggerRepeatedClickPulse() {
        pulseProgress = 0f
        repeatedBannerTicks = REPEATED_BANNER_DURATION_TICKS
        repaint()
    }

    /** 推进单帧动画参数并请求重绘。 */
    fun stepAnimation(fadingOut: Boolean) {
        phaseRadians += PHASE_STEP_RADIANS
        val creptProgress = min(MAX_CREEP_PROGRESS, displayedProgress + CREEP_STEP)
        val target = if (fadingOut) 1.0f else max(currentStage.targetProgress, creptProgress)
        displayedProgress += (target - displayedProgress) * PROGRESS_SMOOTHING
        if (pulseProgress >= 0f) {
            pulseProgress += PULSE_STEP
            if (pulseProgress > 1f) {
                pulseProgress = -1f
            }
        }
        if (repeatedBannerTicks > 0) {
            repeatedBannerTicks--
        }
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        val g2 = (graphics.create() as? Graphics2D) ?: return
        try {
            configureRenderingHints(g2)
            paintCardShadowAndSurface(g2)
            paintAmbientGlowAndOrbitalLogo(g2)
            paintTitleAndStatusText(g2)
            paintShimmerProgressBar(g2)
        } finally {
            g2.dispose()
        }
    }

    /** 配置抗锯齿与高质量插值渲染提示。 */
    private fun configureRenderingHints(g2: Graphics2D) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }

    /** 绘制多层柔和阴影、黑曜石渐变卡片底板与高光边框。 */
    private fun paintCardShadowAndSurface(g2: Graphics2D) {
        val cardX = CARD_MARGIN.toFloat()
        val cardY = CARD_MARGIN.toFloat()
        val cardW = (width - CARD_MARGIN * 2).toFloat()
        val cardH = (height - CARD_MARGIN * 2).toFloat()

        for (layer in 1..SHADOW_LAYERS) {
            val spread = layer * 2.2f
            val alpha = (SHADOW_BASE_ALPHA / layer).coerceIn(4, 40)
            g2.color = Color(0, 0, 0, alpha)
            g2.fill(
                RoundRectangle2D.Float(
                    cardX - spread,
                    cardY - spread + layer * 0.8f,
                    cardW + spread * 2f,
                    cardH + spread * 2f,
                    CARD_ARC + spread,
                    CARD_ARC + spread,
                ),
            )
        }

        val cardShape = RoundRectangle2D.Float(cardX, cardY, cardW, cardH, CARD_ARC, CARD_ARC)
        g2.paint = GradientPaint(0f, cardY, CARD_TOP_COLOR, 0f, cardY + cardH, CARD_BOTTOM_COLOR)
        g2.fill(cardShape)

        g2.stroke = BasicStroke(1.2f)
        g2.paint = GradientPaint(0f, cardY, CARD_BORDER_TOP_COLOR, 0f, cardY + cardH, CARD_BORDER_BOTTOM_COLOR)
        g2.draw(cardShape)
    }

    /** 绘制呼吸光晕、双轨旋转电弧、重复点击扩散波纹以及中心 Onyx 图标。 */
    private fun paintAmbientGlowAndOrbitalLogo(g2: Graphics2D) {
        val centerX = width / 2f
        val centerY = CARD_MARGIN + ORBIT_CENTER_OFFSET_Y
        val breathe = (0.85f + 0.15f * sin(phaseRadians * 1.4).toFloat()).coerceIn(0.7f, 1.0f)
        val glowRadius = GLOW_BASE_RADIUS * breathe

        g2.paint = RadialGradientPaint(
            Point2D.Float(centerX, centerY),
            glowRadius,
            floatArrayOf(0f, 0.55f, 1f),
            arrayOf(GLOW_CORE_COLOR, GLOW_MID_COLOR, GLOW_OUTER_COLOR),
        )
        g2.fillOval(
            (centerX - glowRadius).toInt(),
            (centerY - glowRadius).toInt(),
            (glowRadius * 2).toInt(),
            (glowRadius * 2).toInt(),
        )

        if (pulseProgress >= 0f) {
            val pulseRadius = ORBIT_RADIUS + pulseProgress * PULSE_EXPAND_SPAN
            val pulseAlpha = ((1f - pulseProgress) * 190f).toInt().coerceIn(0, 255)
            g2.color = Color(0x4C, 0x9A, 0xFF, pulseAlpha)
            g2.stroke = BasicStroke(2.2f * (1f - pulseProgress * 0.5f))
            g2.drawOval(
                (centerX - pulseRadius).toInt(),
                (centerY - pulseRadius).toInt(),
                (pulseRadius * 2).toInt(),
                (pulseRadius * 2).toInt(),
            )
        }

        val orbitBoxX = (centerX - ORBIT_RADIUS).toDouble()
        val orbitBoxY = (centerY - ORBIT_RADIUS).toDouble()
        val orbitDiameter = (ORBIT_RADIUS * 2f).toDouble()

        g2.color = ORBIT_TRACK_COLOR
        g2.stroke = BasicStroke(2.2f)
        g2.drawOval(orbitBoxX.toInt(), orbitBoxY.toInt(), orbitDiameter.toInt(), orbitDiameter.toInt())

        val primaryStartAngle = -(Math.toDegrees(phaseRadians * 2.2) % FULL_CIRCLE_DEGREES)
        g2.color = PRIMARY_ACCENT_COLOR
        g2.stroke = BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.draw(
            Arc2D.Double(
                orbitBoxX,
                orbitBoxY,
                orbitDiameter,
                orbitDiameter,
                primaryStartAngle,
                PRIMARY_ARC_SWEEP,
                Arc2D.OPEN,
            ),
        )

        val secondaryStartAngle = (Math.toDegrees(phaseRadians * 1.5) % FULL_CIRCLE_DEGREES) + HALF_CIRCLE_DEGREES
        g2.color = SECONDARY_ACCENT_COLOR
        g2.stroke = BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.draw(
            Arc2D.Double(
                orbitBoxX,
                orbitBoxY,
                orbitDiameter,
                orbitDiameter,
                secondaryStartAngle,
                SECONDARY_ARC_SWEEP,
                Arc2D.OPEN,
            ),
        )

        val currentLogo = logoImage
        if (currentLogo != null) {
            val logoX = (centerX - LOGO_SIZE / 2f).toInt()
            val logoY = (centerY - LOGO_SIZE / 2f).toInt()
            g2.drawImage(currentLogo, logoX, logoY, LOGO_SIZE, LOGO_SIZE, null)
        } else {
            paintFallbackOnyxGem(g2, centerX, centerY)
        }
    }

    /** 当图标尚未解码完成时绘制几何黑曜石晶体回退图标。 */
    private fun paintFallbackOnyxGem(g2: Graphics2D, centerX: Float, centerY: Float) {
        val radius = LOGO_SIZE * 0.42f
        val polygon = Path2D.Float()
        for (index in 0 until HEXAGON_VERTICES) {
            val angle = Math.toRadians((index * 60.0) - 90.0)
            val vx = centerX + (cos(angle) * radius).toFloat()
            val vy = centerY + (sin(angle) * radius).toFloat()
            if (index == 0) polygon.moveTo(vx, vy) else polygon.lineTo(vx, vy)
        }
        polygon.closePath()
        g2.paint = GradientPaint(
            centerX - radius,
            centerY - radius,
            PRIMARY_ACCENT_COLOR,
            centerX + radius,
            centerY + radius,
            SECONDARY_ACCENT_COLOR,
        )
        g2.fill(polygon)
    }

    /** 绘制应用标题、版本号徽章与当前启动阶段说明文案。 */
    private fun paintTitleAndStatusText(g2: Graphics2D) {
        val centerX = width / 2f
        val titleY = CARD_MARGIN + TITLE_BASELINE_OFFSET_Y

        val titleFont = Font(Font.SANS_SERIF, Font.BOLD, TITLE_FONT_SIZE)
        val versionFont = Font(Font.SANS_SERIF, Font.PLAIN, VERSION_FONT_SIZE)
        val titleText = "Onyx"
        val versionText = "v${BuildConfig.VERSION}"

        g2.font = titleFont
        val titleMetrics = g2.fontMetrics
        val titleWidth = titleMetrics.stringWidth(titleText)

        g2.font = versionFont
        val versionMetrics = g2.fontMetrics
        val versionWidth = versionMetrics.stringWidth(versionText)

        val totalHeaderWidth = titleWidth + HEADER_GAP + versionWidth + VERSION_PILL_PAD_X * 2
        val startX = centerX - totalHeaderWidth / 2f

        g2.font = titleFont
        g2.color = TITLE_TEXT_COLOR
        g2.drawString(titleText, startX, titleY)

        val pillX = startX + titleWidth + HEADER_GAP
        val pillH = versionMetrics.height + 2f
        val pillY = titleY - versionMetrics.ascent - 1f
        val pillW = versionWidth + VERSION_PILL_PAD_X * 2f
        g2.color = VERSION_PILL_BG_COLOR
        g2.fill(RoundRectangle2D.Float(pillX, pillY, pillW, pillH, 10f, 10f))

        g2.font = versionFont
        g2.color = VERSION_TEXT_COLOR
        g2.drawString(versionText, pillX + VERSION_PILL_PAD_X, titleY - 1f)

        val statusY = CARD_MARGIN + STATUS_BASELINE_OFFSET_Y
        g2.font = Font(Font.SANS_SERIF, Font.PLAIN, STATUS_FONT_SIZE)
        val statusText = if (repeatedBannerTicks > 0) {
            REPEATED_CLICK_STATUS_TEXT
        } else {
            currentStage.statusText
        }
        g2.color = if (repeatedBannerTicks > 0) REPEATED_STATUS_TEXT_COLOR else STATUS_TEXT_COLOR
        val statusWidth = g2.fontMetrics.stringWidth(statusText)
        g2.drawString(statusText, centerX - statusWidth / 2f, statusY)
    }

    /** 绘制底部圆角进度轨道、渐变填充与流光高光条。 */
    private fun paintShimmerProgressBar(g2: Graphics2D) {
        val barX = (width - PROGRESS_BAR_WIDTH) / 2f
        val barY = CARD_MARGIN + PROGRESS_BAR_OFFSET_Y
        val trackShape = RoundRectangle2D.Float(
            barX,
            barY,
            PROGRESS_BAR_WIDTH,
            PROGRESS_BAR_HEIGHT,
            PROGRESS_BAR_ARC,
            PROGRESS_BAR_ARC,
        )

        g2.color = ORBIT_TRACK_COLOR
        g2.fill(trackShape)

        val fillWidth = (PROGRESS_BAR_WIDTH * displayedProgress.coerceIn(0.08f, 1.0f))
        val fillShape = RoundRectangle2D.Float(
            barX,
            barY,
            fillWidth,
            PROGRESS_BAR_HEIGHT,
            PROGRESS_BAR_ARC,
            PROGRESS_BAR_ARC,
        )
        g2.paint = GradientPaint(
            barX,
            barY,
            PRIMARY_ACCENT_COLOR,
            barX + PROGRESS_BAR_WIDTH,
            barY,
            SECONDARY_ACCENT_COLOR,
        )
        g2.fill(fillShape)

        val oldClip = g2.clip
        g2.clip(fillShape)
        val shimmerNormalized = ((phaseRadians * 0.45) % 1.0).toFloat()
        val shimmerSpan = PROGRESS_BAR_WIDTH + SHIMMER_WIDTH * 2
        val shimmerCenter = barX + shimmerSpan * shimmerNormalized - SHIMMER_WIDTH
        g2.paint = GradientPaint(
            shimmerCenter - SHIMMER_WIDTH / 2f,
            barY,
            SHIMMER_EDGE_COLOR,
            shimmerCenter,
            barY,
            SHIMMER_CENTER_COLOR,
            true,
        )
        g2.fillRect(
            (shimmerCenter - SHIMMER_WIDTH).toInt(),
            barY.toInt(),
            (SHIMMER_WIDTH * 2).toInt(),
            PROGRESS_BAR_HEIGHT.toInt(),
        )
        g2.clip = oldClip
    }

    private companion object {
        const val WINDOW_WIDTH = 400
        const val WINDOW_HEIGHT = 240
        const val CARD_MARGIN = 16
        const val CARD_ARC = 24f
        const val SHADOW_LAYERS = 5
        const val SHADOW_BASE_ALPHA = 38

        const val ORBIT_CENTER_OFFSET_Y = 66f
        const val ORBIT_RADIUS = 36f
        const val GLOW_BASE_RADIUS = 56f
        const val LOGO_SIZE = 42
        const val HEXAGON_VERTICES = 6

        const val FULL_CIRCLE_DEGREES = 360.0
        const val HALF_CIRCLE_DEGREES = 180.0
        const val PRIMARY_ARC_SWEEP = 108.0
        const val SECONDARY_ARC_SWEEP = 68.0

        const val TITLE_BASELINE_OFFSET_Y = 134f
        const val STATUS_BASELINE_OFFSET_Y = 160f
        const val PROGRESS_BAR_OFFSET_Y = 178f
        const val PROGRESS_BAR_WIDTH = 260f
        const val PROGRESS_BAR_HEIGHT = 4.5f
        const val PROGRESS_BAR_ARC = 4.5f
        const val SHIMMER_WIDTH = 54f

        const val TITLE_FONT_SIZE = 17
        const val VERSION_FONT_SIZE = 11
        const val STATUS_FONT_SIZE = 12
        const val HEADER_GAP = 8f
        const val VERSION_PILL_PAD_X = 7f

        const val PHASE_STEP_RADIANS = 0.075
        const val CREEP_STEP = 0.0025f
        const val MAX_CREEP_PROGRESS = 0.94f
        const val PROGRESS_SMOOTHING = 0.16f
        const val PULSE_STEP = 0.055f
        const val PULSE_EXPAND_SPAN = 28f
        const val REPEATED_BANNER_DURATION_TICKS = 90
        const val REPEATED_CLICK_STATUS_TEXT = "已收到打开请求，正在全力加载主窗口..."

        val CARD_TOP_COLOR = Color(0x13, 0x17, 0x22, 248)
        val CARD_BOTTOM_COLOR = Color(0x0D, 0x10, 0x17, 252)
        val CARD_BORDER_TOP_COLOR = Color(0x33, 0x3D, 0x56, 210)
        val CARD_BORDER_BOTTOM_COLOR = Color(0x1F, 0x25, 0x36, 180)

        val GLOW_CORE_COLOR = Color(0x4C, 0x9A, 0xFF, 64)
        val GLOW_MID_COLOR = Color(0x7C, 0x5C, 0xFF, 24)
        val GLOW_OUTER_COLOR = Color(0x13, 0x17, 0x22, 0)

        val ORBIT_TRACK_COLOR = Color(0x22, 0x29, 0x3A)
        val PRIMARY_ACCENT_COLOR = Color(0x4C, 0x9A, 0xFF)
        val SECONDARY_ACCENT_COLOR = Color(0x8B, 0x6B, 0xFF)

        val TITLE_TEXT_COLOR = Color(0xF2, 0xF6, 0xFC)
        val VERSION_PILL_BG_COLOR = Color(0x1E, 0x26, 0x38)
        val VERSION_TEXT_COLOR = Color(0x7D, 0xA2, 0xFF)
        val STATUS_TEXT_COLOR = Color(0x94, 0xA3, 0xB8)
        val REPEATED_STATUS_TEXT_COLOR = Color(0xBF, 0xDB, 0xFE)

        val SHIMMER_EDGE_COLOR = Color(0xFF, 0xFF, 0xFF, 0)
        val SHIMMER_CENTER_COLOR = Color(0xFF, 0xFF, 0xFF, 110)
    }
}

/** 将启动动画窗口居中放置在当前鼠标所在显示器（回退为主显示器）。 */
private fun centerOnActiveScreen(window: JWindow) {
    val defaultScreenBounds =
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
    val screenBounds = runCatching {
        MouseInfo.getPointerInfo()?.device?.defaultConfiguration?.bounds
    }.getOrNull() ?: defaultScreenBounds
    val x = screenBounds.x + (screenBounds.width - window.width) / 2
    val y = screenBounds.y + (screenBounds.height - window.height) / 2
    window.setLocation(x, y)
}
