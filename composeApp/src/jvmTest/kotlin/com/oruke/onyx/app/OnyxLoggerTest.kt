package com.oruke.onyx.app

import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 验证 Onyx 持久化日志的文件命名与保留边界。 */
class OnyxLoggerTest {
    /** 验证按日日志文件名包含完整 ISO 日期。 */
    @Test
    fun createsDailyLogFileName() {
        assertEquals("onyx-2026-07-13.log", OnyxLogger.logFileName(LocalDate.of(2026, 7, 13)))
    }

    /** 验证仅清理早于保留边界的 Onyx 日志，不误删其他文件。 */
    @Test
    fun identifiesExpiredOnyxLogFilesOnly() {
        val oldestRetainedDate = LocalDate.of(2026, 6, 30)

        assertTrue(OnyxLogger.isExpiredLogFile(Path.of("onyx-2026-06-29.log"), oldestRetainedDate))
        assertFalse(OnyxLogger.isExpiredLogFile(Path.of("onyx-2026-06-30.log"), oldestRetainedDate))
        assertFalse(OnyxLogger.isExpiredLogFile(Path.of("application.log"), oldestRetainedDate))
    }
}
