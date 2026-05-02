package com.oruke.onyx.app.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.oruke.onyx.app.filesystem.JsonSessionRepository
import com.oruke.onyx.app.filesystem.JsonSettingsRepository
import com.oruke.onyx.app.filesystem.ArchiveService
import com.oruke.onyx.app.filesystem.CompositeFileRepository
import com.oruke.onyx.app.filesystem.JvmDesktopExternalOpenService
import com.oruke.onyx.app.filesystem.JvmDesktopTrashService
import com.oruke.onyx.app.filesystem.JvmLocalFileProvider
import com.oruke.onyx.app.filesystem.JvmTextClipboardService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

@Composable
fun rememberRootComponent(): RootComponent {
    val scope = remember {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
    val component = remember {
        val localFileProvider = JvmLocalFileProvider()
        val archiveService = ArchiveService()
        val fileRepository = CompositeFileRepository(localFileProvider, archiveService)
        val externalOpenService = JvmDesktopExternalOpenService()
        val trashService = JvmDesktopTrashService()
        val textClipboardService = JvmTextClipboardService()
        DefaultRootComponent(
            scope = scope,
            fileRepository = fileRepository,
            fileCommandService = localFileProvider,
            textClipboardService = textClipboardService,
            externalOpenService = externalOpenService,
            trashService = trashService,
            settingsRepository = JsonSettingsRepository(),
            sessionRepository = JsonSessionRepository(),
        )
    }

    DisposableEffect(component) {
        onDispose {
            scope.cancel()
        }
    }

    return component
}
