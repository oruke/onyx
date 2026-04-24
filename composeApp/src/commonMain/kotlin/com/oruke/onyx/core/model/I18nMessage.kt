package com.oruke.onyx.core.model

import org.jetbrains.compose.resources.StringResource

/**
 * A structured message model that carries a localized string resource and optional arguments.
 * This allows business components to construct user-facing messages without violating
 * architectural boundaries by depending on actual string resolution logic.
 */
data class I18nMessage(
    val key: StringResource,
    val args: List<Any> = emptyList()
) {
    constructor(key: StringResource, vararg args: Any) : this(key, args.toList())
}
