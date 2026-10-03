package com.oruke.onyx.core.model

/**
 * 携带稳定业务消息的异常，由界面按当前语言解析，并保留底层异常供诊断。
 *
 * @param userMessage 可延迟本地化的业务错误消息。
 * @param cause 导致本次业务失败的底层异常；没有底层异常时为 null。
 */
class LocalizedException(
    /** 供界面解析的业务错误消息，避免将诊断文本作为界面文案。 */
    val userMessage: I18nMessage,
    cause: Throwable? = null,
) : IllegalStateException(userMessage.key.name, cause)
