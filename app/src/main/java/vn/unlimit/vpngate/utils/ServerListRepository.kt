package vn.unlimit.vpngate.utils

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.api.VPNGateApiService
import vn.unlimit.vpngate.compat.LocalRemoteConfig as FirebaseRemoteConfig
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.models.VPNGateConnectionList
import java.io.BufferedReader
import java.io.StringReader
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Fetches the VPN Gate free-server list, trying multiple sources in order so the app keeps
 * working even if the main domain is blocked in the user's country:
 *
 *  1. The official VPN Gate API (`vpn_udp_api` / `.../api/iphone/`)
 *  2. The configured mirror domain (`vpn_alternative_api`)
 *  3. A CSV snapshot kept in this project's own GitHub repo, as a last-resort fallback
 *
 * On success the list is saved to the local Room database (replacing the previous snapshot) and
 * [DataUtil.lastServerListUpdateAt] is updated. Used by both [vn.unlimit.vpngate.viewmodels.ConnectionListViewModel]
 * (manual/pull-to-refresh) and [ServerSyncWorker] (periodic background refresh).
 *
 * NOTE: scraping vpngate.net's own HTML page directly (as opposed to its CSV API) was assessed
 * but not implemented - the HTML table encodes server configs differently from the CSV API and
 * is fragile to changes upstream, so it's a separate, higher-risk piece of work left for later.
 */
object ServerListRepository {
    private const val TAG = "ServerListRepository"

    // Last-resort fallback: a CSV snapshot of the server list mirrored in this project's own
    // GitHub repo. Update this file periodically (e.g. via a scheduled GitHub Action) so it
    // doesn't go stale; it's only used when both live sources above fail.
    const val GITHUB_CSV_FALLBACK_URL =
        "https://raw.githubusercontent.com/morteza-taheri/VpnM/main/servers.csv"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val service: VPNGateApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://www.vpngate.net/") // placeholder; every call below uses an absolute @Url
            .client(client)
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
            .create(VPNGateApiService::class.java)
    }

    private fun parseCsv(csv: String): VPNGateConnectionList {
        val list = VPNGateConnectionList()
        try {
            BufferedReader(StringReader(csv)).use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (l.indexOf("*") != 0 && l.indexOf("#") != 0) {
                        VPNGateConnection.fromCsv(l)?.let { list.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing CSV", e)
        }
        return list
    }

    /**
     * Tries each source in turn and persists the first one that returns a non-empty list.
     * Returns the number of servers stored. Throws if every source failed.
     */
    suspend fun syncNow(@Suppress("UNUSED_PARAMETER") context: Context): Int {
        val dataUtil = App.instance!!.dataUtil!!
        val includeUdp = dataUtil.getBooleanSetting(DataUtil.INCLUDE_UDP_SERVER, true)
        val version = if (!dataUtil.hasAds()) "pro" else null

        val primaryUrl = if (includeUdp) {
            FirebaseRemoteConfig.getInstance().getString("vpn_udp_api")
        } else {
            dataUtil.baseUrl + "/api/iphone/"
        }
        val mirrorUrl = FirebaseRemoteConfig.getInstance().getString("vpn_alternative_api") + "/api/iphone/"
        val sources = listOf(primaryUrl, mirrorUrl, GITHUB_CSV_FALLBACK_URL).distinct()

        var lastError: Throwable? = null
        for ((index, url) in sources.withIndex()) {
            try {
                val isFallback = url == GITHUB_CSV_FALLBACK_URL
                val csv = service.getCsvString(url, if (isFallback) null else version)
                val list = parseCsv(csv)
                if (list.size() > 0) {
                    val items = list.toVPNGateItems()
                    App.instance!!.vpnGateItemDao.deleteAll()
                    App.instance!!.vpnGateItemDao.insertAll(*items.toTypedArray())
                    dataUtil.connectionsCache = list
                    dataUtil.lastServerListUpdateAt = Date()
                    Log.i(TAG, "Synced ${items.size} servers from source #$index ($url)")
                    return items.size
                }
            } catch (e: Throwable) {
                lastError = e
                Log.w(TAG, "Server list source #$index failed ($url): ${e.message}")
            }
        }
        throw lastError ?: IllegalStateException("All server list sources returned empty results")
    }
}
