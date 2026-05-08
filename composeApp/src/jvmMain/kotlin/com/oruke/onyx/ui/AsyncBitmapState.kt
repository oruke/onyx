package com.oruke.onyx.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap

@Composable
internal fun rememberAsyncBitmap(
    location: String,
    maxDimension: Int,
    loader: suspend (String, Int) -> ImageBitmap?,
): Pair<ImageBitmap?, Boolean> {
    var bitmap by remember(location, maxDimension, loader) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(location, maxDimension, loader) { mutableStateOf(true) }
    LaunchedEffect(location, maxDimension, loader) {
        loading = true
        bitmap = loader(location, maxDimension)
        loading = false
    }
    return bitmap to loading
}
