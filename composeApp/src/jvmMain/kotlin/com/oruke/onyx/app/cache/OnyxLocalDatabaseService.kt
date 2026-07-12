package com.oruke.onyx.app.cache

import com.oruke.onyx.app.storage.OnyxDataDirectories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as exposedTransaction
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createDirectories

/** Onyx 本地数据数据库入口，负责 SQLite 连接、建表和阻塞事务调度。 */
internal class OnyxLocalDatabaseService(
    private val databasePath: Path = OnyxDataDirectories.stateDirectory().resolve("onyx.db"),
) {
    /** 数据库是否已经完成连接和 schema 初始化。 */
    private val initialized = AtomicBoolean(false)

    /** Exposed 数据库连接对象，只能通过 [database] 访问以保证初始化顺序。 */
    @Volatile
    private var databaseRef: Database? = null

    /**
     * 在 IO 线程中执行一个 SQLite 事务。
     *
     * @param block 需要在 Exposed transaction 内执行的数据库逻辑。
     * @return 事务代码块的返回值。
     */
    suspend fun <T> transaction(block: () -> T): T = withContext(Dispatchers.IO) {
        exposedTransaction(database()) {
            block()
        }
    }

    /**
     * 确保数据库已经可用，适合启动预热流程显式调用。
     *
     * @return 初始化结果。
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            database()
            Unit
        }
    }

    /**
     * 返回已初始化的 Exposed 数据库连接。
     *
     * @return 可用于事务的数据库连接。
     */
    private fun database(): Database {
        if (!initialized.get()) {
            synchronized(this) {
                if (!initialized.get()) initializeDatabase()
            }
        }
        return requireNotNull(databaseRef)
    }

    /** 完成目录创建、schema 迁移和 Exposed 连接初始化。 */
    private fun initializeDatabase() {
        databasePath.parent?.createDirectories()
        val databaseUrl = "jdbc:sqlite:${databasePath.toAbsolutePath()}"
        OnyxDatabaseMigrationService(databaseUrl).migrate()
        databaseRef = Database.connect(
            url = databaseUrl,
            driver = "org.sqlite.JDBC",
        )
        initialized.set(true)
    }
}
