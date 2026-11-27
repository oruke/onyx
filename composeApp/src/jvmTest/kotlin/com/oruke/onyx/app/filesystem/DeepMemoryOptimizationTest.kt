package com.oruke.onyx.app.filesystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.ui.SettingsCategory
import com.oruke.onyx.ui.SettingsDialogLayout
import com.oruke.onyx.ui.resolveGalleryThumbnailDimension
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.OnyxWindowTheme
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import com.oruke.onyx.vfs.archive.ArchiveService
import com.oruke.onyx.vfs.archive.ArchiveVfsProvider
import com.oruke.onyx.vfs.local.JvmLocalFileProvider
import com.oruke.onyx.vfs.local.LocalVfsProvider
import com.oruke.onyx.vfs.s3.S3VfsProvider
import com.oruke.onyx.vfs.smb.SmbVfsProvider
import com.oruke.onyx.vfs.webdav.WebDavVfsProvider
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo

/**
 * 验证 Phase 2 深度内存优化闭环：Skia 位图引用计数回收、缩略图磁盘 LRU 缓存、
 * 画廊分辨率动态分档、VFS 协议懒路由与设置页磁盘缓存管理交互。
 */
class DeepMemoryOptimizationTest {

    /**
     * 校验 [ManagedBitmapRegistry] 在 UI 仍持有位图时不提前释放原生内存，
     * 仅在缓存与 UI 全部释放后关闭底层 Skia 位图，且 `flushPendingCloses()` 可立即刷清宽限队列。
     */
    @Test
    fun managedBitmapRegistryDefersNativeCloseUntilCacheAndUiRelease() {
        val immediateBitmap = createTestImageBitmap(width = 32, height = 32)
        ManagedBitmapRegistry.register(immediateBitmap)
        assertTrue(ManagedBitmapRegistry.retain(immediateBitmap))

        // 模拟内存缓存淘汰：UI 仍持有 1 份引用，原生像素必须存活。
        ManagedBitmapRegistry.release(immediateBitmap, immediate = true)
        assertFalse(immediateBitmap.asSkiaBitmap().isClosed, "UI 仍持有引用时不得关闭 Skia Bitmap")

        // 模拟 UI DisposableEffect 释放最后 1 份引用：原生像素立即关闭。
        ManagedBitmapRegistry.release(immediateBitmap, immediate = true)
        assertTrue(immediateBitmap.asSkiaBitmap().isClosed, "全部引用释放后应关闭 Skia Bitmap")

        // 校验带宽限期释放后，调用 flushPendingCloses 可在托盘隐藏时立即释放。
        val deferredBitmap = createTestImageBitmap(width = 32, height = 32)
        ManagedBitmapRegistry.register(deferredBitmap)
        ManagedBitmapRegistry.release(deferredBitmap, immediate = false)
        assertFalse(deferredBitmap.asSkiaBitmap().isClosed, "宽限期内尚未到期应暂缓关闭")
        ManagedBitmapRegistry.flushPendingCloses()
        assertTrue(deferredBitmap.asSkiaBitmap().isClosed, "flushPendingCloses 应立即关闭已归零位图")
    }

    /**
     * 校验缩略图磁盘缓存支持编码落盘、清空内存缓存后直接从磁盘命中、超容量 LRU 淘汰及一键清空。
     */
    @Test
    fun diskThumbnailCachePersistsHitsAfterMemoryClearEvictsLruAndClears() = runBlocking {
        val tempDir = Files.createTempDirectory("onyx-disk-thumb-test-")
        val cacheDir = tempDir.resolve("cache")
        try {
            val sourceImage = tempDir.resolve("sample.png")
            writeNoisyPng(sourceImage, width = 256, height = 256, seed = 42L)
            val mtime = Files.getLastModifiedTime(sourceImage).toMillis()
            val size = Files.size(sourceImage)

            val diskCache = DiskThumbnailCache(cacheDirectory = cacheDir, maxBytes = 512L * 1024L)
            val localProvider = JvmLocalFileProvider()
            val service = JvmThumbnailService(
                contentServices = listOf(localProvider),
                archiveService = ArchiveService(),
                diskCache = diskCache,
            )

            // 1. 首次加载：解码原图并持久化至磁盘缩略图缓存。
            val firstLoaded = service.loadThumbnail(
                location = sourceImage.toString(),
                maxDimension = 128,
                modifiedAtEpochMillis = mtime,
                sizeBytes = size,
            )
            assertNotNull(firstLoaded)
            assertTrue(service.readDiskCacheUsageBytes() > 0L, "首次加载后磁盘缓存体积应大于 0")

            // 2. 清空内存缓存并删除原图，验证二次加载直接从磁盘缓存命中返回位图。
            service.clearCache()
            Files.deleteIfExists(sourceImage)
            val fromDisk = service.loadThumbnail(
                location = sourceImage.toString(),
                maxDimension = 128,
                modifiedAtEpochMillis = mtime,
                sizeBytes = size,
            )
            assertNotNull(fromDisk, "清空内存并删除原图后应从磁盘缩略图缓存命中")

            // 3. 验证一键清空磁盘缓存。
            service.clearDiskCache().getOrThrow()
            assertEquals(0L, service.readDiskCacheUsageBytes())

            // 4. 验证超出小容量上限时按最旧修改时间执行 LRU 裁剪（单张 96x96 噪点图约 5-9 KB，上限设为单张 2.2 倍）。
            val lruDir = tempDir.resolve("lru")
            val probeDir = tempDir.resolve("probe")
            val probeCache = DiskThumbnailCache(cacheDirectory = probeDir, maxBytes = 1024L * 1024L)
            val imgBytes1 = createNoisyPngBytes(width = 96, height = 96, seed = 1L)
            val imgBytes2 = createNoisyPngBytes(width = 96, height = 96, seed = 2L)
            val imgBytes3 = createNoisyPngBytes(width = 96, height = 96, seed = 3L)
            SkiaImage.makeFromEncoded(imgBytes1).use { probeCache.putImage("probe", it) }
            val singleEncodedBytes = probeCache.readUsageBytes()
            assertTrue(singleEncodedBytes > 0L)

            val lruBudgetBytes = (singleEncodedBytes * 2.2).toLong()
            val smallCache = DiskThumbnailCache(cacheDirectory = lruDir, maxBytes = lruBudgetBytes)
            SkiaImage.makeFromEncoded(imgBytes1).use { smallCache.putImage("key-oldest", it) }
            assertTrue(smallCache.readUsageBytes() > 0L)
            // 将最旧缓存文件时间戳向前拨 10 分钟，确保 LRU 淘汰顺序确定。
            Files.list(lruDir).use { stream ->
                stream.forEach { file ->
                    Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 600_000L))
                }
            }
            SkiaImage.makeFromEncoded(imgBytes2).use { smallCache.putImage("key-middle", it) }
            SkiaImage.makeFromEncoded(imgBytes3).use { smallCache.putImage("key-newest", it) }

            val usageAfterTrim = smallCache.readUsageBytes()
            assertTrue(usageAfterTrim <= lruBudgetBytes, "LRU 裁剪后磁盘占用($usageAfterTrim)不应超过上限($lruBudgetBytes)")
            assertTrue(smallCache.getBytes("key-oldest") == null, "最旧的缩略图应在超限裁剪时被优先淘汰")
            assertTrue(smallCache.getBytes("key-newest") != null, "最新写入的缩略图应保留在缓存中")
            service.clearCache()
            ManagedBitmapRegistry.flushPendingCloses()
        } finally {
            deleteRecursivelyQuietly(tempDir)
        }
    }

    /**
     * 校验画廊缩略图分辨率分档按逻辑尺寸与屏幕缩放倍率向上归档至 128 / 256 / 384 / 512 像素。
     */
    @Test
    fun galleryThumbnailDimensionBucketsCleanlyByDpAndDensity() {
        assertEquals(128, resolveGalleryThumbnailDimension(sizeDp = 96, density = 1f))
        assertEquals(128, resolveGalleryThumbnailDimension(sizeDp = 64, density = 2f))
        assertEquals(256, resolveGalleryThumbnailDimension(sizeDp = 120, density = 1.5f))
        assertEquals(256, resolveGalleryThumbnailDimension(sizeDp = 160, density = 1.5f))
        assertEquals(384, resolveGalleryThumbnailDimension(sizeDp = 180, density = 1.5f))
        assertEquals(512, resolveGalleryThumbnailDimension(sizeDp = 256, density = 2f))
    }

    /**
     * 校验归档与远程 VFS 懒路由在本地浏览与未命中协议匹配期间绝不实例化底层 Provider。
     */
    @Test
    fun lazyVfsProvidersDeferInstantiationUntilMatchedOperationRuns() = runBlocking {
        val archiveCreations = AtomicInteger(0)
        val smbCreations = AtomicInteger(0)
        val webDavCreations = AtomicInteger(0)
        val s3Creations = AtomicInteger(0)

        val lazyArchive = LazyRoutedArchiveProvider {
            archiveCreations.incrementAndGet()
            ArchiveVfsProvider(ArchiveService())
        }
        val lazySmb = LazySmbVfsProvider {
            smbCreations.incrementAndGet()
            SmbVfsProvider()
        }
        val lazyWebDav = LazyWebDavVfsProvider {
            webDavCreations.incrementAndGet()
            WebDavVfsProvider()
        }
        val lazyS3 = LazyS3VfsProvider {
            s3Creations.incrementAndGet()
            S3VfsProvider()
        }
        val localProvider = LocalVfsProvider(JvmLocalFileProvider())
        val registry = VfsProviderRegistry(listOf(lazyArchive, lazySmb, lazyWebDav, lazyS3, localProvider))

        val tempDir = Files.createTempDirectory("onyx-lazy-vfs-test-")
        try {
            // 路由与列举本地目录时，4 个远程/归档 Provider 均应保持未初始化状态。
            assertTrue(registry.list(tempDir.toString()).isSuccess)
            assertTrue(lazySmb.supports("smb://nas.local/share"))
            assertTrue(lazyWebDav.supports("webdavs://dav.example.com/root"))
            assertTrue(lazyS3.supports("s3://bucket/prefix"))
            assertTrue(lazyArchive.supports("archive:///tmp/a.zip!/"))

            assertFalse(lazyArchive.isInitialized)
            assertFalse(lazySmb.isInitialized)
            assertFalse(lazyWebDav.isInitialized)
            assertFalse(lazyS3.isInitialized)
            assertEquals(0, archiveCreations.get())
            assertEquals(0, smbCreations.get())
            assertEquals(0, webDavCreations.get())
            assertEquals(0, s3Creations.get())

            // 关闭未初始化的懒代理不应触发实例化。
            lazySmb.close()
            lazyWebDav.close()
            lazyS3.close()
            assertEquals(0, smbCreations.get())
            assertEquals(0, webDavCreations.get())
            assertEquals(0, s3Creations.get())

            // 真正调用归档列表读取时才按需实例化一次。
            lazyArchive.list("archive:///nonexistent/demo.zip!/")
            assertTrue(lazyArchive.isInitialized)
            assertEquals(1, archiveCreations.get())
        } finally {
            deleteRecursivelyQuietly(tempDir)
        }
    }

    /**
     * 校验默认 `-Xmx` 已收敛至 768 MB，且设置页内存卡片展示磁盘缓存占用并响应一键清理操作。
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun settingsMemoryCardShowsDiskCacheUsageAndDispatchesClearAction() =
        runDesktopComposeUiTest(width = 900, height = 720) {
            assertEquals(768, OnyxSettings.DEFAULT_MAX_HEAP_MEMORY_MB)

            var clearInvocations = 0
            setContent {
                var memoryStatus by remember {
                    mutableStateOf(
                        JvmMemoryStatus(
                            activeMaxHeapMb = 768,
                            usedHeapMb = 142,
                            configuredMaxHeapMb = 768,
                            launcherConfigWritable = true,
                            thumbnailDiskCacheBytes = 12L * 1024L * 1024L,
                        ),
                    )
                }
                val draft = remember { OnyxSettings() }
                OnyxWindowTheme(settings = draft) {
                    val appearance = LocalOnyxAppearance.current
                    SettingsDialogLayout(
                        title = "设置",
                        draft = draft,
                        category = SettingsCategory.SHORTCUTS_ADVANCED,
                        onCategoryChange = {},
                        onDraftChange = {},
                        onCleanupInvalidLocations = {},
                        onConfirm = {},
                        onDismiss = {},
                        bodyFontSize = appearance.listFontSize,
                        labelFontSize = appearance.headerFontSize,
                        memoryStatus = memoryStatus,
                        onClearThumbnailDiskCache = {
                            clearInvocations += 1
                            memoryStatus = memoryStatus.copy(thumbnailDiskCacheBytes = 0L)
                        },
                    )
                }
            }

            waitForIdle()
            onNodeWithTag("settings-thumbnail-disk-cache-usage").performScrollTo().assertIsDisplayed()
            onNodeWithTag("settings-clear-thumbnail-disk-cache-button").performScrollTo().performClick()
            waitForIdle()
            assertEquals(1, clearInvocations)
        }

    private companion object {
        /** 构造真实原生 Skia 位图供引用计数释放测试使用。 */
        fun createTestImageBitmap(width: Int, height: Int) = Bitmap().apply {
            allocPixels(ImageInfo.makeN32Premul(width, height))
        }.asComposeImageBitmap()

        /** 生成高熵噪点 PNG 字节，确保压缩后具有稳定非零体积用于验证 LRU 淘汰。 */
        fun createNoisyPngBytes(width: Int, height: Int, seed: Long): ByteArray {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val random = Random(seed)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    image.setRGB(x, y, Color(random.nextInt(256), random.nextInt(256), random.nextInt(256)).rgb)
                }
            }
            val output = java.io.ByteArrayOutputStream()
            ImageIO.write(image, "png", output)
            return output.toByteArray()
        }

        /** 将高熵噪点 PNG 写入指定临时路径。 */
        fun writeNoisyPng(path: Path, width: Int, height: Int, seed: Long) {
            Files.write(path, createNoisyPngBytes(width, height, seed))
        }

        /** 静默递归删除临时测试目录。 */
        fun deleteRecursivelyQuietly(root: Path) {
            if (!Files.exists(root)) return
            runCatching {
                Files.walk(root).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { path ->
                        runCatching { Files.deleteIfExists(path) }
                    }
                }
            }
        }
    }
}
