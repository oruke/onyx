package com.oruke.onyx.app.cache

import com.oruke.onyx.vfs.api.SystemMenuAction
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 平台菜单缓存服务测试，验证 SQLite 快照写入、命中和平台级失效行为。 */
class PlatformMenuCacheServiceTest {
    /**
     * 验证缓存缺失时会调用 loader，随后同一作用域从 SQLite 快照读取。
     */
    @Test
    fun cachedOrLoadWritesSnapshotAndReusesCachedActions() = runTest {
        val service = createService()
        val scope = PlatformMenuCacheScope(platform = "windows", scopeKey = "windows-registry;txt")
        var loadCount = 0

        val first = service.cachedOrLoad(scope) {
            loadCount += 1
            listOf(sampleAction())
        }
        val second = service.cachedOrLoad(scope) {
            loadCount += 1
            emptyList()
        }

        assertEquals(1, loadCount)
        assertEquals(first, second)
        assertEquals("open", second.single().children.single().id)
    }

    /**
     * 验证空菜单快照也会保存，避免无可用菜单时每次右键都重新扫描。
     */
    @Test
    fun cachedOrLoadStoresEmptySnapshot() = runTest {
        val service = createService()
        val scope = PlatformMenuCacheScope(platform = "linux", scopeKey = "linux-services;unknown")
        var loadCount = 0

        val first = service.cachedOrLoad(scope) {
            loadCount += 1
            emptyList()
        }
        val second = service.cachedOrLoad(scope) {
            loadCount += 1
            listOf(sampleAction())
        }

        assertTrue(first.isEmpty())
        assertTrue(second.isEmpty())
        assertEquals(1, loadCount)
    }

    /**
     * 验证平台来源指纹变化时会清空该平台的菜单缓存。
     */
    @Test
    fun invalidatePlatformIfChangedClearsCachedActions() = runTest {
        val service = createService()
        val scope = PlatformMenuCacheScope(platform = "macos", scopeKey = "macos-services;file")
        service.cachedOrLoad(scope) { listOf(sampleAction()) }

        val changed = service.invalidatePlatformIfChanged(platform = "macos", fingerprint = "new-source")
        var loadCount = 0
        val actions = service.cachedOrLoad(scope) {
            loadCount += 1
            emptyList()
        }

        assertTrue(changed)
        assertTrue(actions.isEmpty())
        assertEquals(1, loadCount)
    }

    /**
     * 创建指向临时 SQLite 文件的缓存服务。
     *
     * @return 可独立读写的缓存服务实例。
     */
    private fun createService(): PlatformMenuCacheService {
        val databasePath = createTempDirectory(prefix = "onyx-local-test-").resolve("onyx.db")
        val databaseService = OnyxLocalDatabaseService(databasePath)
        return PlatformMenuCacheService(databaseService, PlatformMenuCacheRepository())
    }

    /**
     * 构造带子菜单的测试动作。
     *
     * @return 可写入缓存的系统菜单动作。
     */
    private fun sampleAction(): SystemMenuAction {
        return SystemMenuAction(
            id = "group",
            displayName = "Group",
            command = "",
            children = listOf(
                SystemMenuAction(
                    id = "open",
                    displayName = "Open",
                    command = "open %1",
                    iconPath = "open.ico",
                )
            ),
        )
    }
}
