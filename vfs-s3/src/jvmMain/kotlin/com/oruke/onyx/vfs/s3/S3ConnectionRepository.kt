package com.oruke.onyx.vfs.s3

import com.oruke.onyx.core.model.S3ConnectionConfig

/** S3 VFS 位置对应的非密钥连接配置来源。 */
interface S3ConnectionRepository {
    /**
     * 查询指定 VFS 位置应使用的 S3 连接配置。
     *
     * @param location S3 VFS 位置。
     * @return 最具体的已注册配置；未注册时返回 Amazon S3 默认配置。
     */
    fun configuration(location: String): S3ConnectionConfig

    /** 不包含已保存配置的默认仓库。 */
    data object None : S3ConnectionRepository {
        override fun configuration(location: String): S3ConnectionConfig = S3ConnectionConfig(region = "")
    }
}

/** 一个已保存 S3 根位置及其连接配置。 */
data class S3ConnectionRegistration(
    /** 配置生效的 S3 根位置。 */
    val rootLocation: String,
    /** 该位置使用的连接配置。 */
    val config: S3ConnectionConfig,
)

/**
 * 运行期可原子替换的 S3 连接配置仓库。
 *
 * 文件操作可能与设置更新并发发生，因此读取只访问不可变快照，不暴露可变集合。
 */
class MutableS3ConnectionRepository : S3ConnectionRepository {
    /** 当前按根路径长度降序排列的不可变配置快照。 */
    @Volatile
    private var registrations: List<S3ConnectionRegistration> = emptyList()

    /**
     * 原子替换全部已保存 S3 配置。
     *
     * @param nextRegistrations 最新配置集合。
     */
    fun replaceAll(nextRegistrations: List<S3ConnectionRegistration>) {
        registrations = nextRegistrations
            .distinctBy { registration -> registration.rootLocation }
            .sortedByDescending { registration -> registration.rootLocation.length }
    }

    override fun configuration(location: String): S3ConnectionConfig {
        return registrations
            .firstOrNull { registration -> location.belongsTo(registration.rootLocation) }
            ?.config
            ?: S3ConnectionConfig(region = "")
    }
}

/**
 * 判断位置是否属于已注册 S3 根位置。
 *
 * @param rootLocation 已保存连接根位置。
 * @return 相同位置或其后代返回 true。
 */
private fun String.belongsTo(rootLocation: String): Boolean {
    if (this == rootLocation) return true
    val rootPrefix = if (rootLocation.endsWith('/')) rootLocation else "$rootLocation/"
    return startsWith(rootPrefix)
}
