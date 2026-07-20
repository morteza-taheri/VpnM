package vn.unlimit.vpngate.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import vn.unlimit.vpngate.models.BookmarkedServer
import vn.unlimit.vpngate.models.ExcludedApp
import vn.unlimit.vpngate.models.VPNGateItem

@Database(entities = [VPNGateItem::class, ExcludedApp::class, BookmarkedServer::class], version = 4)
abstract class AppDatabase: RoomDatabase() {
    abstract fun vpnGateItemDao() : VPNGateItemDao
    abstract fun excludedAppDao(): ExcludedAppDao
    abstract fun bookmarkedServerDao(): BookmarkedServerDao

    companion object {
        // Migration from version 2 to 3 - adding seTcpPort and seUdpPort fields
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add the new columns with default value 0
                database.execSQL(
                    "ALTER TABLE VPNGateItem ADD COLUMN seTcpPort INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE VPNGateItem ADD COLUMN seUdpPort INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // Migration from version 3 to 4 - adds the BookmarkedServer table. This table is
        // deliberately separate from VPNGateItem (which gets wiped and rebuilt on every server
        // list sync) so bookmarks survive refreshes, cache clears, and the server disappearing
        // from every source.
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `BookmarkedServer` (
                    `hostName` TEXT NOT NULL,
                    `ip` TEXT,
                    `score` INTEGER NOT NULL,
                    `ping` INTEGER NOT NULL,
                    `speed` INTEGER NOT NULL,
                    `countryLong` TEXT,
                    `countryShort` TEXT,
                    `numVpnSession` INTEGER NOT NULL,
                    `uptime` INTEGER NOT NULL,
                    `totalUser` INTEGER NOT NULL,
                    `totalTraffic` INTEGER NOT NULL,
                    `logType` TEXT,
                    `operator` TEXT,
                    `message` TEXT,
                    `openVpnConfigData` TEXT,
                    `tcpPort` INTEGER NOT NULL,
                    `udpPort` INTEGER NOT NULL,
                    `isL2TPSupport` INTEGER NOT NULL,
                    `isSSTPSupport` INTEGER NOT NULL,
                    `seTcpPort` INTEGER NOT NULL,
                    `seUdpPort` INTEGER NOT NULL,
                    `bookmarkedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`hostName`))
                    """.trimIndent()
                )
            }
        }
    }
}
