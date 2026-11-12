package com.oruke.onyx.app.platform

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * Windows `PROPERTYKEY` 结构体的 JNA 映射（`wtypes.h`，固定 20 字节）。
 *
 * @param fmtid 属性集合 GUID。
 * @param propertyId 属性集合内的数值标识符。
 */
@Structure.FieldOrder("fmtid", "pid")
internal class WindowsPropertyKey(
    fmtid: Guid.GUID = Guid.GUID("{00000000-0000-0000-0000-000000000000}"),
    propertyId: Int = 0,
) : Structure() {
    /** 属性格式集 GUID。 */
    @JvmField
    var fmtid: Guid.GUID = fmtid

    /** 属性标识符。 */
    @JvmField
    var pid: Int = propertyId

    init {
        write()
    }
}

/**
 * Windows `IPropertyStore`（`{886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99}`）的轻量 COM 包装。
 *
 * 严格按照 Win32 `PROPVARIANT` ABI 进行宽字符串读写与 `VT_EMPTY` 擦除，并通过 `PropVariantClear`
 * 释放 COM 堆内存，确保零原生内存泄漏。
 *
 * @param pointer 已获取引用的 `IPropertyStore` 接口指针。
 */
internal class WindowsPropertyStore(
    pointer: Pointer,
) : Unknown(pointer), AutoCloseable {

    /**
     * 读取指定属性键的字符串值（兼容 `VT_LPWSTR` 与 `VT_BSTR`）。
     *
     * @param key 目标属性键。
     * @return 已解析的字符串值；未设置或非字符串类型时返回 `null`。
     */
    fun getStringValue(key: WindowsPropertyKey): String? {
        val propVariant = Memory(PROPVARIANT_CAPACITY_BYTES).apply { clear() }
        return try {
            val hr = invokeGetValue(key.pointer, propVariant)
            if (!isHResultSucceeded(hr)) {
                null
            } else {
                when (propVariant.getShort(0L).toInt()) {
                    VT_LPWSTR, VT_BSTR -> propVariant.getPointer(PROPVARIANT_VALUE_OFFSET)?.getWideString(0L)
                    else -> null
                }
            }
        } finally {
            PropVariantOle32.INSTANCE.PropVariantClear(propVariant)
        }
    }

    /**
     * 将指定属性键设置为 `VT_LPWSTR` 宽字符串值。
     *
     * @param key 目标属性键。
     * @param value 待写入的字符串。
     * @return COM `SetValue` 成功时返回 `true`。
     */
    fun setStringValue(
        key: WindowsPropertyKey,
        value: String,
    ): Boolean {
        val encodedBytes = (value + "\u0000").toByteArray(Charsets.UTF_16LE)
        val nativeString = Ole32.INSTANCE.CoTaskMemAlloc(encodedBytes.size.toLong()) ?: return false
        nativeString.write(0L, encodedBytes, 0, encodedBytes.size)
        val propVariant = Memory(PROPVARIANT_CAPACITY_BYTES).apply {
            clear()
            setShort(0L, VT_LPWSTR.toShort())
            setPointer(PROPVARIANT_VALUE_OFFSET, nativeString)
        }
        return try {
            isHResultSucceeded(invokeSetValue(key.pointer, propVariant))
        } finally {
            PropVariantOle32.INSTANCE.PropVariantClear(propVariant)
        }
    }

    /**
     * 将指定属性键重置为 `VT_EMPTY` 以移除窗口或对象上关联的属性存储。
     *
     * 根据 Microsoft `SHGetPropertyStoreForWindow` 规范，窗口销毁前必须通过 `VT_EMPTY` 清除已设置属性，
     * 否则系统全局窗口属性空间不会自动回收。
     *
     * @param key 待清除的目标属性键。
     * @return COM `SetValue` 成功时返回 `true`。
     */
    fun clearValue(key: WindowsPropertyKey): Boolean {
        val emptyPropVariant = Memory(PROPVARIANT_CAPACITY_BYTES).apply { clear() }
        return isHResultSucceeded(invokeSetValue(key.pointer, emptyPropVariant))
    }

    /**
     * 提交属性存储变更（文件快捷方式属性存储需要显式 `Commit`，窗口属性存储立即生效但调用无副作用）。
     *
     * @return COM `Commit` 成功时返回 `true`。
     */
    fun commit(): Boolean {
        val hr = _invokeNativeObject(
            VTABLE_COMMIT_INDEX,
            arrayOf(pointer),
            WinNT.HRESULT::class.java,
        ) as WinNT.HRESULT
        return isHResultSucceeded(hr)
    }

    override fun close() {
        Release()
    }

    private fun invokeGetValue(
        keyPointer: Pointer,
        propVariantPointer: Pointer,
    ): WinNT.HRESULT = _invokeNativeObject(
        VTABLE_GET_VALUE_INDEX,
        arrayOf(pointer, keyPointer, propVariantPointer),
        WinNT.HRESULT::class.java,
    ) as WinNT.HRESULT

    private fun invokeSetValue(
        keyPointer: Pointer,
        propVariantPointer: Pointer,
    ): WinNT.HRESULT = _invokeNativeObject(
        VTABLE_SET_VALUE_INDEX,
        arrayOf(pointer, keyPointer, propVariantPointer),
        WinNT.HRESULT::class.java,
    ) as WinNT.HRESULT

    companion object {
        /** `IPropertyStore` 接口 IID。 */
        val IID_I_PROPERTY_STORE: Guid.IID = Guid.IID("{886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99}")

        /** `System.AppUserModel` 属性集 GUID。 */
        private val FMTID_APP_USER_MODEL: Guid.GUID = Guid.GUID("{9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3}")

        /** `System.AppUserModel.RelaunchCommand`（pid = 2）。 */
        val PKEY_RELAUNCH_COMMAND = WindowsPropertyKey(FMTID_APP_USER_MODEL, 2)

        /** `System.AppUserModel.RelaunchIconResource`（pid = 3）。 */
        val PKEY_RELAUNCH_ICON_RESOURCE = WindowsPropertyKey(FMTID_APP_USER_MODEL, 3)

        /** `System.AppUserModel.RelaunchDisplayNameResource`（pid = 4）。 */
        val PKEY_RELAUNCH_DISPLAY_NAME_RESOURCE = WindowsPropertyKey(FMTID_APP_USER_MODEL, 4)

        /** `System.AppUserModel.ID`（pid = 5）。 */
        val PKEY_APP_USER_MODEL_ID = WindowsPropertyKey(FMTID_APP_USER_MODEL, 5)

        /** 64 位 Windows 下 `PROPVARIANT` 标准大小（24 字节，向下兼容 32 位 16 字节）。 */
        private const val PROPVARIANT_CAPACITY_BYTES = 24L

        /** `vt` + 3 个保留 `WORD` 合计 8 字节，其后紧接联合体指针字段。 */
        private const val PROPVARIANT_VALUE_OFFSET = 8L

        /** `VARENUM::VT_BSTR`。 */
        private const val VT_BSTR = 8

        /** `VARENUM::VT_LPWSTR`。 */
        private const val VT_LPWSTR = 31

        /** `IPropertyStore::GetValue` 虚表槽位。 */
        private const val VTABLE_GET_VALUE_INDEX = 5

        /** `IPropertyStore::SetValue` 虚表槽位。 */
        private const val VTABLE_SET_VALUE_INDEX = 6

        /** `IPropertyStore::Commit` 虚表槽位。 */
        private const val VTABLE_COMMIT_INDEX = 7
    }
}

/**
 * 判断 COM `HRESULT` 是否成功（`hr >= 0`）。
 *
 * @param result 待检查的 `HRESULT`。
 * @return 非负状态码返回 `true`。
 */
internal fun isHResultSucceeded(result: WinNT.HRESULT): Boolean = result.toInt() >= 0

/**
 * 在当前线程建立单线程单元（STA）COM 上下文执行回调，并按初始化状态精确配对释放。
 *
 * @param block 需要在 COM 已初始化状态下执行的逻辑。
 * @return 回调返回值。
 */
internal inline fun <T> withWindowsComInitialized(block: () -> T): T {
    val initHr = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED)
    val shouldUninitialize = initHr.toInt() == COM_S_OK || initHr.toInt() == COM_S_FALSE
    if (!shouldUninitialize && initHr.toInt() != COM_RPC_E_CHANGED_MODE) {
        check(isHResultSucceeded(initHr)) { "CoInitializeEx failed: 0x${initHr.toInt().toUInt().toString(HEX_RADIX)}" }
    }
    return try {
        block()
    } finally {
        if (shouldUninitialize) {
            Ole32.INSTANCE.CoUninitialize()
        }
    }
}

/** `S_OK`。 */
private const val COM_S_OK = 0

/** `S_FALSE`（当前线程已按相同并发模型初始化）。 */
private const val COM_S_FALSE = 1

/** `RPC_E_CHANGED_MODE`（当前线程已按 MTA 模型初始化，仍可直接访问进程内自由/单元无关接口）。 */
private const val COM_RPC_E_CHANGED_MODE = -2147417850

/** 十六进制格式化基数。 */
private const val HEX_RADIX = 16

/**
 * `ole32.dll` 中用于释放 `PROPVARIANT` 内部缓冲区的原生接口。
 */
private interface PropVariantOle32 : StdCallLibrary {
    /**
     * 释放 `PROPVARIANT` 持有的堆内存或接口引用并将其重置为 `VT_EMPTY`。
     *
     * @param pvar 指向 `PROPVARIANT` 缓冲区的指针。
     * @return COM 状态码。
     */
    fun PropVariantClear(pvar: Pointer): WinNT.HRESULT

    companion object {
        val INSTANCE: PropVariantOle32 by lazy {
            Native.load("ole32", PropVariantOle32::class.java, W32APIOptions.UNICODE_OPTIONS)
        }
    }
}
