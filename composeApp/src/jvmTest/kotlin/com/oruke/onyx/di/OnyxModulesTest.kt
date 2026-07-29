package com.oruke.onyx.di

import com.oruke.onyx.vfs.api.RoutableVfsRandomAccessService
import com.oruke.onyx.vfs.archive.ArchiveService
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * 验证桌面文件模块中的关键运行时依赖绑定。
 */
class OnyxModulesTest {
    /**
     * 归档服务应通过接口绑定取得统一随机访问注册表。
     */
    @Test
    fun resolvesArchiveRandomAccessDependencies() {
        val application = koinApplication {
            modules(fileModule)
        }
        try {
            val randomAccessService = application.koin.get<RoutableVfsRandomAccessService>()
            val archiveService = application.koin.get<ArchiveService>()

            assertNotNull(randomAccessService)
            assertSame(archiveService, application.koin.get<ArchiveService>())
        } finally {
            application.close()
        }
    }
}
