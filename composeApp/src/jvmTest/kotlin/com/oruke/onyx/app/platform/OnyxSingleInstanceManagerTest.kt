package com.oruke.onyx.app.platform

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 单实例本地回环 IPC 管理器测试，验证毫秒级唤醒转发与版本升级旧实例自动退出。
 */
class OnyxSingleInstanceManagerTest {
    /**
     * 验证相同构建指纹下二次启动会将唤醒及路径参数转发给已常驻实例并返回 `true`。
     */
    @Test
    fun forwardsActivateAndOpenLocationToRunningInstanceWithMatchingFingerprint() {
        val endpointFile = createTempDirectory("onyx-single-instance-test-").resolve("instance.endpoint")
        val activatedLocation = AtomicReference<String?>("UNSET")
        val latch = CountDownLatch(1)

        OnyxSingleInstanceManager.startServer(
            endpointFile = endpointFile,
            currentFingerprint = "1.4.10:100",
            onActivate = { location ->
                activatedLocation.set(location)
                latch.countDown()
            },
            onShutdown = {},
        ).use {
            val forwarded = OnyxSingleInstanceManager.tryForwardToRunningInstance(
                args = arrayOf("C:/Users/oruke/Downloads"),
                endpointFile = endpointFile,
                currentFingerprint = "1.4.10:100",
            )
            assertTrue(forwarded)
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals("C:/Users/oruke/Downloads", activatedLocation.get())
        }
    }

    /**
     * 验证构建指纹不一致（升级新版本）时，会通知旧驻留实例退出并返回 `false` 由新进程接管启动。
     */
    @Test
    fun requestsShutdownAndContinuesStartupWhenFingerprintDiffers() {
        val endpointFile = createTempDirectory("onyx-single-instance-upgrade-").resolve("instance.endpoint")
        val shutdownLatch = CountDownLatch(1)

        OnyxSingleInstanceManager.startServer(
            endpointFile = endpointFile,
            currentFingerprint = "1.4.9:100",
            onActivate = {},
            onShutdown = { shutdownLatch.countDown() },
        ).use {
            val forwarded = OnyxSingleInstanceManager.tryForwardToRunningInstance(
                args = emptyArray(),
                endpointFile = endpointFile,
                currentFingerprint = "1.4.10:200",
            )
            assertFalse(forwarded)
            assertTrue(shutdownLatch.await(2, TimeUnit.SECONDS))
        }
    }

    /**
     * 验证冷启动早期的 [OnyxSingleInstanceRelay] 能拦截重复点击触发启动动画反馈，并在窗口管理器就绪后重放待打开目录。
     */
    @Test
    fun earlyRelayNotifiesSplashOnRepeatedClickAndReplaysPendingLocationWhenBound() {
        val endpointFile = createTempDirectory("onyx-single-instance-relay-").resolve("instance.endpoint")
        val earlyFeedbackLatch = CountDownLatch(1)
        val replayLatch = CountDownLatch(1)
        val replayedLocation = AtomicReference<String?>(null)

        OnyxSingleInstanceRelay(
            endpointFile = endpointFile,
            currentFingerprint = "1.4.11:100",
            onEarlyRepeatedActivate = { earlyFeedbackLatch.countDown() },
        ).use { relay ->
            val forwardedDuringColdStart = OnyxSingleInstanceManager.tryForwardToRunningInstance(
                args = arrayOf("D:/Workspace"),
                endpointFile = endpointFile,
                currentFingerprint = "1.4.11:100",
            )
            assertTrue(forwardedDuringColdStart)
            assertTrue(earlyFeedbackLatch.await(2, TimeUnit.SECONDS))

            relay.bindWindowCallbacks(
                onActivate = { location ->
                    replayedLocation.set(location)
                    replayLatch.countDown()
                },
                onShutdown = {},
            )
            assertTrue(replayLatch.await(2, TimeUnit.SECONDS))
            assertEquals("D:/Workspace", replayedLocation.get())
        }
    }

    /**
     * 验证 [OnyxStartupSplash] 阶段单调推进、重复点击计数与关闭状态更新。
     */
    @Test
    fun startupSplashTracksMonotonicStageProgressionAndRepeatedActivations() {
        OnyxStartupSplash.resetForTest()
        OnyxStartupSplash.showIfInteractive(emptyArray())
        assertEquals(StartupSplashStage.LAUNCHING, OnyxStartupSplash.currentSnapshotForTest().stage)

        OnyxStartupSplash.updateStage(StartupSplashStage.INITIALIZING_UI)
        OnyxStartupSplash.updateStage(StartupSplashStage.PRELOADING_STORAGE)
        assertEquals(StartupSplashStage.INITIALIZING_UI, OnyxStartupSplash.currentSnapshotForTest().stage)

        OnyxStartupSplash.notifyRepeatedActivation()
        assertEquals(1, OnyxStartupSplash.currentSnapshotForTest().repeatedActivationCount)

        OnyxStartupSplash.dismiss()
        val finalSnapshot = OnyxStartupSplash.currentSnapshotForTest()
        assertTrue(finalSnapshot.dismissed)
        assertEquals(StartupSplashStage.READY, finalSnapshot.stage)
    }
}

