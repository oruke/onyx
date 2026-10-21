package com.oruke.onyx.app.platform

import com.oruke.onyx.core.model.StandardUserDirectory
import com.oruke.onyx.core.model.SystemQuickAccessLocation
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.mac.CoreFoundation
import com.sun.jna.platform.mac.CoreFoundation.CFArrayRef
import com.sun.jna.platform.mac.CoreFoundation.CFIndex
import com.sun.jna.platform.mac.CoreFoundation.CFStringRef

/** 通过 Foundation 标准目录 API 解析 macOS 用户目录，保留系统重定向语义。 */
internal class MacKnownDirectoriesSource : SystemQuickAccessSource {
    /** @return Foundation 提供的实际标准目录位置，复制路径文本后释放自动引用池。 */
    override fun loadLocations(): Result<List<SystemQuickAccessLocation>> = runCatching {
        val foundation = NativeLibrary.getInstance("Foundation")
        val objectiveC = NativeLibrary.getInstance("objc")
        val message = objectiveC.getFunction("objc_msgSend")
        val register = objectiveC.getFunction("sel_registerName")
        val poolClass =
            objectiveC.getFunction("objc_getClass").invokePointer(arrayOf("NSAutoreleasePool"))
        val allocated =
            message.invokePointer(arrayOf(poolClass, register.invokePointer(arrayOf("alloc"))))
        val pool =
            message.invokePointer(arrayOf(allocated, register.invokePointer(arrayOf("init"))))
        try {
            val directories =
                listOf(
                    StandardUserDirectory.DESKTOP to DESKTOP_DIRECTORY,
                    StandardUserDirectory.DOCUMENTS to DOCUMENT_DIRECTORY,
                    StandardUserDirectory.DOWNLOADS to DOWNLOADS_DIRECTORY,
                    StandardUserDirectory.PICTURES to PICTURES_DIRECTORY,
                    StandardUserDirectory.MUSIC to MUSIC_DIRECTORY,
                    StandardUserDirectory.VIDEOS to MOVIES_DIRECTORY,
                )
            directories.mapNotNull { (kind, identifier) ->
                val result: Pointer =
                    foundation
                        .getFunction("NSSearchPathForDirectoriesInDomains")
                        .invokePointer(arrayOf<Any>(identifier, USER_DOMAIN_MASK, 1.toByte()))
                        ?: return@mapNotNull null
                val paths = CFArrayRef(result)
                if (CoreFoundation.INSTANCE.CFArrayGetCount(paths).toLong() == 0L)
                    return@mapNotNull null
                val path = CoreFoundation.INSTANCE.CFArrayGetValueAtIndex(paths, CFIndex(0))
                SystemQuickAccessLocation(
                    null,
                    CFStringRef(path).stringValue(),
                    standardDirectory = kind,
                )
            }
        } finally {
            message.invokeVoid(arrayOf(pool, register.invokePointer(arrayOf("drain"))))
        }
    }

    private companion object {
        /** Foundation 的用户级搜索域。 */
        const val USER_DOMAIN_MASK = 1L
        /** Foundation 桌面目录枚举值。 */
        const val DESKTOP_DIRECTORY = 12L
        /** Foundation 文档目录枚举值。 */
        const val DOCUMENT_DIRECTORY = 9L
        /** Foundation 下载目录枚举值。 */
        const val DOWNLOADS_DIRECTORY = 15L
        /** Foundation 图片目录枚举值。 */
        const val PICTURES_DIRECTORY = 19L
        /** Foundation 音乐目录枚举值。 */
        const val MUSIC_DIRECTORY = 18L
        /** Foundation 影片目录枚举值。 */
        const val MOVIES_DIRECTORY = 17L
    }
}
