package vn.unlimit.vpngate.utils

import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.models.BookmarkedServer
import vn.unlimit.vpngate.models.VPNGateConnection

/**
 * Bookmarks live in their own Room table ([vn.unlimit.vpngate.db.BookmarkedServerDao]) so they
 * are never touched by [ServerListRepository.syncNow] (which deletes and re-inserts the whole
 * VPNGateItem table on every refresh) or by clearing the app's cache. If a bookmarked server
 * disappears from every source, it's still shown - as a read-only "offline" row - by
 * [getOfflineBookmarks].
 */
object BookmarkManager {
    private val dao get() = App.instance!!.bookmarkedServerDao

    suspend fun isBookmarked(hostName: String): Boolean {
        if (hostName.isBlank()) return false
        return dao.isBookmarked(hostName)
    }

    /** Adds or removes the bookmark for [connection], returning the new bookmarked state. */
    suspend fun toggleBookmark(connection: VPNGateConnection): Boolean {
        val hostName = connection.hostName ?: return false
        return if (dao.isBookmarked(hostName)) {
            dao.deleteByHostName(hostName)
            false
        } else {
            dao.insert(BookmarkedServer.fromVPNGateItem(connection.toVPNGateItem()))
            true
        }
    }

    suspend fun getAllHostNames(): Set<String> = dao.getAllHostNames().toSet()

    /** Bookmarked servers not present in [liveHostNames] - rendered as offline placeholder rows. */
    suspend fun getOfflineBookmarks(liveHostNames: Set<String>): List<VPNGateConnection> {
        return dao.getAll()
            .filter { it.hostName !in liveHostNames }
            .map { VPNGateConnection().fromVPNGateItem(it.toVPNGateItem()) }
    }
}
