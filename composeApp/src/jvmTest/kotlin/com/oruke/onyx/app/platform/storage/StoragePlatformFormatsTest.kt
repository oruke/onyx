package com.oruke.onyx.app.platform.storage

import com.oruke.onyx.core.model.StorageDeviceAction
import com.oruke.onyx.core.model.StorageDeviceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 跨宿主核对系统结构化数据协议，不用格式解析测试替代原生设备运行验收。 */
class StoragePlatformFormatsTest {
    /** 真实 mountinfo 字段顺序与八进制转义支持可移动盘、网络挂载和 Btrfs 任意子卷。 */
    @Test
    fun mountInfoKeepsEscapesOptionalFieldsAndBtrfsSubvolumes() {
        val ordinary =
            assertNotNull(
                parseLinuxMountInfo(
                    "97 31 8:1 / /run/media/Work\\040Disk rw,nosuid shared:5 - ext4 /dev/sdb1 rw"
                )
            )
        assertEquals("8:1", ordinary.deviceNumber)
        assertEquals("/run/media/Work Disk", ordinary.location)
        assertTrue(isVisibleStorageMount(ordinary))
        val subvolume =
            assertNotNull(
                parseLinuxMountInfo(
                    "98 31 8:2 /@archive /mnt/archive rw - btrfs /dev/sdc1 rw,subvol=/@archive"
                )
            )
        assertTrue(isVisibleStorageMount(subvolume))
        val kernel = assertNotNull(parseLinuxMountInfo("99 31 0:4 / /proc rw - proc proc rw"))
        assertFalse(isVisibleStorageMount(kernel))
        val network =
            assertNotNull(
                parseLinuxMountInfo("100 31 0:30 / /mnt/share ro - cifs //example.test/share ro")
            )
        assertTrue(network.readOnly)
        assertTrue(isVisibleStorageMount(network))
    }

    /** plist 结构中容器不伪装成目录，卷身份不随卷标改变，系统根卷不能卸载或弹出。 */
    @Test
    fun propertyListDistinguishesContainersSystemRootAndUnmountedVolumes() {
        val text =
            """
            <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                        <plist version="1.0"><dict><key>AllDisksAndPartitions</key><array>
                          <dict><key>DeviceIdentifier</key><string>disk0</string>
                            <key>Content</key><string>GUID_partition_scheme</string>
                            <key>APFSVolumes</key><array>
                              <dict><key>DeviceIdentifier</key><string>disk1s1</string>
                                <key>VolumeName</key><string>System</string><key>VolumeUUID</key><string>ROOT-SAMPLE</string>
                                <key>MountPoint</key><string>/</string></dict>
                              <dict><key>DeviceIdentifier</key><string>disk1s2</string>
                                <key>VolumeName</key><string>メディア</string><key>VolumeUUID</key><string>DATA-SAMPLE</string></dict>
                            </array>
                          </dict>
                        </array></dict></plist>
            """
                .trimIndent()
        val volumes = parseMacStorageVolumes(readStoragePropertyList(text))
        assertEquals(2, volumes.size)
        val root = volumes.single { it.device.locations == listOf("/") }.device
        assertFalse(StorageDeviceAction.UNMOUNT in root.actions)
        assertFalse(StorageDeviceAction.EJECT in root.actions)
        val unmounted = volumes.single { it.identifier == "disk1s2" }.device
        assertEquals("mac-volume:data-sample", unmounted.id)
        assertEquals(StorageDeviceState.UNMOUNTED, unmounted.state)
        assertEquals(setOf(StorageDeviceAction.MOUNT), unmounted.actions)
        val renamed =
            parseMacStorageVolumes(readStoragePropertyList(text.replace("メディア", "Archive")))
        assertEquals(unmounted.id, renamed.single { it.identifier == "disk1s2" }.device.id)
    }
}
