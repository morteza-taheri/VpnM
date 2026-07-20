package vn.unlimit.vpngate.utils

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
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
 *  3. Community mirror sites, scraped live from vpngate.net's own mirror-sites page
 *     (https://www.vpngate.net/en/sites.aspx lists `http://IP:PORT/en/` mirrors; each mirror
 *     serves the same `/api/iphone/` CSV endpoint as the primary site)
 *  4. A CSV snapshot kept in this project's own GitHub repo, as the final last-resort fallback
 *
 * On success the list is saved to the local Room database (replacing the previous snapshot) and
 * [DataUtil.lastServerListUpdateAt] is updated. Used by both [vn.unlimit.vpngate.viewmodels.ConnectionListViewModel]
 * (manual/pull-to-refresh) and [ServerSyncWorker] (periodic background refresh).
 */
object ServerListRepository {
    private const val TAG = "ServerListRepository"

    private const val MIRROR_SITES_LIST_URL = "https://www.vpngate.net/en/sites.aspx"
    // Matches the "http://IP:PORT/en/" mirror entries vpngate.net publishes on that page.
    private val MIRROR_URL_PATTERN =
        Regex("""https?://\d{1,3}(?:\.\d{1,3}){3}:\d{2,5}/en/""")
    private const val MAX_SCRAPED_MIRRORS = 3

    // Last-resort fallback: a CSV snapshot of the server list mirrored in this project's own
    // GitHub repo. Update this file periodically (e.g. via a scheduled GitHub Action) so it
    // doesn't go stale; it's only used when every live source above fails.
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
     * Downloads vpngate.net's own mirror-sites page and extracts up to [MAX_SCRAPED_MIRRORS]
     * `.../api/iphone/` URLs from the `http://IP:PORT/en/` entries it lists. Returns an empty
     * list on any failure (this is itself just one tier of a multi-tier fallback chain).
     */
    private fun fetchMirrorApiUrls(): List<String> {
        return try {
            val request = Request.Builder().url(MIRROR_SITES_LIST_URL).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return emptyList()
                MIRROR_URL_PATTERN.findAll(body)
                    .map { it.value.removeSuffix("/en/") + "/api/iphone/" }
                    .distinct()
                    .take(MAX_SCRAPED_MIRRORS)
                    .toList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch mirror sites list: ${e.message}")
            emptyList()
        }
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

        // The mirror-sites page is only fetched if both direct sources fail below - no need to
        // pay for that extra request on the (overwhelmingly common) happy path.
        var lastError: Throwable? = null
        val directSources = listOf(primaryUrl, mirrorUrl).distinct()
        for ((index, url) in directSources.withIndex()) {
            try {
                val csv = service.getCsvString(url, version)
                val list = parseCsv(csv)
                if (list.size() > 0) {
                    return persist(list, dataUtil, "direct source #$index ($url)")
                }
            } catch (e: Throwable) {
                lastError = e
                Log.w(TAG, "Server list source #$index failed ($url): ${e.message}")
            }
        }

        for (mirrorApiUrl in fetchMirrorApiUrls()) {
            try {
                val csv = service.getCsvString(mirrorApiUrl, version)
                val list = parseCsv(csv)
                if (list.size() > 0) {
                    return persist(list, dataUtil, "scraped mirror ($mirrorApiUrl)")
                }
            } catch (e: Throwable) {
                lastError = e
                Log.w(TAG, "Scraped mirror failed ($mirrorApiUrl): ${e.message}")
            }
        }

        try {
            val csv = service.getCsvString(GITHUB_CSV_FALLBACK_URL, null)
            val list = parseCsv(csv)
            if (list.size() > 0) {
                return persist(list, dataUtil, "GitHub CSV fallback")
            }
        } catch (e: Throwable) {
            lastError = e
            Log.w(TAG, "GitHub CSV fallback failed: ${e.message}")
        }

        throw lastError ?: IllegalStateException("All server list sources returned empty results")
    }

    private fun persist(list: VPNGateConnectionList, dataUtil: DataUtil, sourceDescription: String): Int {
        val items = list.toVPNGateItems()
        App.instance!!.vpnGateItemDao.deleteAll()
        App.instance!!.vpnGateItemDao.insertAll(*items.toTypedArray())
        dataUtil.connectionsCache = list
        dataUtil.lastServerListUpdateAt = Date()
        Log.i(TAG, "Synced ${items.size} servers from $sourceDescription")
        return items.size
    }
}
