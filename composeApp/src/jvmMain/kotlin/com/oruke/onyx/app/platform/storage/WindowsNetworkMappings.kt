package com.oruke.onyx.app.platform.storage

import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/** 当前 JNA 平台接口未覆盖的 Windows 网络盘连接查询。 */
internal interface WindowsNetworkMappings : StdCallLibrary {
    /**
     * 读取盘符对应的系统网络连接名称，不访问共享目录内容。
     *
     * @param localName 不带结尾分隔符的盘符名称。
     * @param remoteName 接收 UNC 名称的字符缓冲区。
     * @param length 输入缓冲区长度，空间不足时返回所需长度。
     * @return Win32 状态码。
     */
    fun WNetGetConnection(localName: String, remoteName: CharArray, length: IntByReference): Int

    companion object {
        /** 只在 Windows 设备实际查询时加载网络提供程序库。 */
        val instance: WindowsNetworkMappings by lazy {
            Native.load("Mpr", WindowsNetworkMappings::class.java, W32APIOptions.UNICODE_OPTIONS)
        }
    }
}
