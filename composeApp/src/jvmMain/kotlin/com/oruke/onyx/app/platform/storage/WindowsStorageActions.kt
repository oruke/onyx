package com.oruke.onyx.app.platform.storage

import com.oruke.onyx.core.model.StorageDeviceAction
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.ShellAPI
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinUser

/** Windows 设备的 Shell 动作；原生调用和 COM 生命周期均留在平台层。 */
internal object WindowsStorageActions {
    /**
     * 使用规范动词打开设备或属性，不把路径拼接为可执行脚本。
     *
     * @param location 操作系统提供的实际设备挂载点。
     * @param action 已经由设备能力校验的动作。
     */
    fun execute(location: String, action: StorageDeviceAction) {
        val ole = Ole32.INSTANCE
        COMUtils.checkRC(ole.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED))
        try {
            val info = ShellAPI.SHELLEXECUTEINFO()
            info.fMask = INVOKE_SHELL_ITEM_MASK
            info.lpVerb =
                when (action) {
                    StorageDeviceAction.PROPERTIES -> "properties"
                    StorageDeviceAction.OPEN_SYSTEM -> "open"
                    StorageDeviceAction.EJECT -> "eject"
                    else -> error("Unsupported storage action: $action")
                }
            info.lpFile = location
            info.nShow = WinUser.SW_SHOWNORMAL
            if (!Shell32.INSTANCE.ShellExecuteEx(info))
                throw Win32Exception(com.sun.jna.Native.getLastError())
        } finally {
            ole.CoUninitialize()
        }
    }

    /** ShellExecuteEx 使用 Shell 项目及其规范动词执行动作。 */
    private const val INVOKE_SHELL_ITEM_MASK = 0x0000000C
}
