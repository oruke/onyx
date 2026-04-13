package com.oruke.onyx.app.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.oruke.onyx.app.filesystem.JvmLocalFileProvider
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
        DefaultRootComponent(
            scope = scope,
            localFileProvider = JvmLocalFileProvider(),
        )
    }

    DisposableEffect(component) {
        onDispose {
            scope.cancel()
        }
    }

    return component
}
