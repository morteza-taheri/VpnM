package vn.unlimit.vpngate.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A bookmarked server, stored as a full snapshot of its [VPNGateItem] fields at the moment it
 * was bookmarked. This is a deliberately SEPARATE table from [VPNGateItem]: the server-list sync
 * ([vn.unlimit.vpngate.utils.ServerListRepository]) deletes and re-inserts the whole VPNGateItem
 * table on every refresh, but never touches this one - so a bookmark survives even if the server
 * later disappears from every source (it's then shown as an offline/unavailable entry instead of
 * being silently dropped).
 */
@Entity
data class BookmarkedServer(
    @PrimaryKey val hostName: String,
    @ColumnInfo val ip: String?,
    @ColumnInfo val score: Int = 0,
    @ColumnInfo val ping: Int = 0,
    @ColumnInfo val speed: Int = 0,
    @ColumnInfo val countryLong: String?,
    @ColumnInfo val countryShort: String?,
    @ColumnInfo val numVpnSession: Int = 0,
    @ColumnInfo val uptime: Int = 0,
    @ColumnInfo val totalUser: Int = 0,
    @ColumnInfo val totalTraffic: Long = 0,
    @ColumnInfo val logType: String?,
    @ColumnInfo val operator: String?,
    @ColumnInfo val message: String?,
    @ColumnInfo val openVpnConfigData: String?,
    @ColumnInfo val tcpPort: Int = 0,
    @ColumnInfo val udpPort: Int = 0,
    @ColumnInfo val isL2TPSupport: Boolean = false,
    @ColumnInfo val isSSTPSupport: Boolean = false,
    @ColumnInfo val seTcpPort: Int = 0,
    @ColumnInfo val seUdpPort: Int = 0,
    @ColumnInfo val bookmarkedAt: Long = System.currentTimeMillis()
) {
    fun toVPNGateItem(): VPNGateItem = VPNGateItem(
        hostName = hostName, ip = ip, score = score, ping = ping, speed = speed,
        countryLong = countryLong, countryShort = countryShort, numVpnSession = numVpnSession,
        uptime = uptime, totalUser = totalUser, totalTraffic = totalTraffic, logType = logType,
        operator = operator, message = message, openVpnConfigData = openVpnConfigData,
        tcpPort = tcpPort, udpPort = udpPort, isL2TPSupport = isL2TPSupport,
        isSSTPSupport = isSSTPSupport, seTcpPort = seTcpPort, seUdpPort = seUdpPort
    )

    companion object {
        fun fromVPNGateItem(item: VPNGateItem): BookmarkedServer = BookmarkedServer(
            hostName = item.hostName, ip = item.ip, score = item.score, ping = item.ping,
            speed = item.speed, countryLong = item.countryLong, countryShort = item.countryShort,
            numVpnSession = item.numVpnSession, uptime = item.uptime, totalUser = item.totalUser,
            totalTraffic = item.totalTraffic, logType = item.logType, operator = item.operator,
            message = item.message, openVpnConfigData = item.openVpnConfigData,
            tcpPort = item.tcpPort, udpPort = item.udpPort, isL2TPSupport = item.isL2TPSupport,
            isSSTPSupport = item.isSSTPSupport, seTcpPort = item.seTcpPort, seUdpPort = item.seUdpPort
        )
    }
}
