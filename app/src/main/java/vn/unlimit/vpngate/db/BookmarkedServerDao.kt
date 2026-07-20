package vn.unlimit.vpngate.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import vn.unlimit.vpngate.models.BookmarkedServer

@Dao
interface BookmarkedServerDao {
    @Query("SELECT * FROM BookmarkedServer ORDER BY bookmarkedAt DESC")
    suspend fun getAll(): List<BookmarkedServer>

    @Query("SELECT hostName FROM BookmarkedServer")
    suspend fun getAllHostNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: BookmarkedServer)

    @Delete
    suspend fun delete(item: BookmarkedServer)

    @Query("DELETE FROM BookmarkedServer WHERE hostName = :hostName")
    suspend fun deleteByHostName(hostName: String)

    @Query("SELECT EXISTS(SELECT 1 FROM BookmarkedServer WHERE hostName = :hostName)")
    suspend fun isBookmarked(hostName: String): Boolean
}
