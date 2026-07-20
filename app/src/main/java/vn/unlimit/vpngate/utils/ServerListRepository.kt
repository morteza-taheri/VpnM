package vn.unlimit.vpngate.utils

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.api.VPNGateApiService
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.models.VPNGateConnectionList
import java.io.BufferedReader
import java.io.StringReader
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Fetches the VPN Gate free-server list, trying multiple sources in order so the app keeps
 * working even if the main domain is blocked in the user's country. Each source can be
 * individually enabled/disabled (and, for the primary API and GitHub fallback, have its URL
 * edited) from Settings -> Server sources - see [DataUtil.SOURCE_PRIMARY_ENABLED] and friends.
 *
 *  1. The primary VPN Gate API (editable URL, default `.../api/iphone/`)
 *  2. Community mirror sites, scraped live from vpngate.net's own mirror-sites page
 *     (https://www.vpngate.net/en/sites.aspx lists `http://IP:PORT/en/` mirrors; each mirror
 *     serves the same `/api/iphone/` CSV endpoint as the primary site) - toggle only, no URL to
 *     edit since it's not a single fixed address
 *  3. A CSV snapshot kept in this project's own GitHub repo (editable URL), as the final
 *     last-resort fallback
 *
 * On success the list is saved to the local Room database (replacing the previous snapshot) and
 * [DataUtil.lastServerListUpdateAt] is updated. Every attempt is logged to [SyncLogBus]. Used by
 * both [vn.unlimit.vpngate.viewmodels.ConnectionListViewModel] (manual/pull-to-refresh) and
 * [ServerSyncWorker] (periodic background refresh).
 */
object ServerListRepository {
    private const val TAG = "ServerListRepository"

    private const val MIRROR_SITES_LIST_URL = "https://www.vpngate.net/en/sites.aspx"
    // Matches the "http://IP:PORT/en/" mirror entries vpngate.net publishes on that page.
    private val MIRROR_URL_PATTERN =
        Regex("""https?://\d{1,3}(?:\.\d{1,3}){3}:\d{2,5}/en/""")
    private const val MAX_SCRAPED_MIRRORS = 3

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
            SyncLogBus.log("Mirror sites page unreachable: ${e.message}")
            emptyList()
        }
    }

    /**
     * Tries each enabled source in turn and persists the first one that returns a non-empty
     * list. Returns the number of servers stored. Throws if every source failed (or all were
     * disabled).
     */
    suspend fun syncNow(@Suppress("UNUSED_PARAMETER") context: Context): Int {
        val dataUtil = App.instance!!.dataUtil!!
        val version = if (!dataUtil.hasAds()) "pro" else null
        var lastError: Throwable? = null

        if (dataUtil.getBooleanSetting(DataUtil.SOURCE_PRIMARY_ENABLED, true)) {
            val primaryUrl = dataUtil.getStringSetting(
                DataUtil.SOURCE_PRIMARY_URL, DataUtil.DEFAULT_PRIMARY_API_URL
            ) ?: DataUtil.DEFAULT_PRIMARY_API_URL
            SyncLogBus.log("Trying primary API: $primaryUrl")
            try {
                val csv = service.getCsvString(primaryUrl, version)
                val list = parseCsv(csv)
                if (list.size() > 0) {
                    return persist(list, dataUtil, "primary API")
                }
                SyncLogBus.log("Primary API returned an empty list")
            } catch (e: Throwable) {
                lastError = e
                SyncLogBus.log("Primary API failed: ${e.message}")
                Log.w(TAG, "Primary API failed: ${e.message}")
            }
        } else {
            SyncLogBus.log("Primary API disabled in settings - skipping")
        }

        if (dataUtil.getBooleanSetting(DataUtil.SOURCE_MIRROR_ENABLED, true)) {
            SyncLogBus.log("Looking up mirror sites from vpngate.net...")
            val mirrors = fetchMirrorApiUrls()
            if (mirrors.isEmpty()) {
                SyncLogBus.log("No usable mirror sites found")
            }
            for (mirrorApiUrl in mirrors) {
                SyncLogBus.log("Trying mirror: $mirrorApiUrl")
                try {
                    val csv = service.getCsvString(mirrorApiUrl, version)
                    val list = parseCsv(csv)
                    if (list.size() > 0) {
                        return persist(list, dataUtil, "scraped mirror ($mirrorApiUrl)")
                    }
                    SyncLogBus.log("Mirror returned an empty list: $mirrorApiUrl")
                } catch (e: Throwable) {
                    lastError = e
                    SyncLogBus.log("Mirror failed ($mirrorApiUrl): ${e.message}")
                    Log.w(TAG, "Scraped mirror failed ($mirrorApiUrl): ${e.message}")
                }
            }
        } else {
            SyncLogBus.log("Mirror web pages disabled in settings - skipping")
        }

        if (dataUtil.getBooleanSetting(DataUtil.SOURCE_GITHUB_ENABLED, true)) {
            val githubUrl = dataUtil.getStringSetting(
                DataUtil.SOURCE_GITHUB_URL, DataUtil.DEFAULT_GITHUB_CSV_URL
            ) ?: DataUtil.DEFAULT_GITHUB_CSV_URL
            SyncLogBus.log("Trying GitHub fallback: $githubUrl")
            try {
                val csv = service.getCsvString(githubUrl, null)
                val list = parseCsv(csv)
                if (list.size() > 0) {
                    return persist(list, dataUtil, "GitHub CSV fallback")
                }
                SyncLogBus.log("GitHub fallback returned an empty list")
            } catch (e: Throwable) {
                lastError = e
                SyncLogBus.log("GitHub fallback failed: ${e.message}")
                Log.w(TAG, "GitHub CSV fallback failed: ${e.message}")
            }
        } else {
            SyncLogBus.log("GitHub fallback disabled in settings - skipping")
        }

        SyncLogBus.log("All enabled sources failed")
        throw lastError ?: IllegalStateException("All server list sources returned empty results")
    }

    private fun persist(list: VPNGateConnectionList, dataUtil: DataUtil, sourceDescription: String): Int {
        val items = list.toVPNGateItems()
        App.instance!!.vpnGateItemDao.deleteAll()
        App.instance!!.vpnGateItemDao.insertAll(*items.toTypedArray())
        dataUtil.connectionsCache = list
        dataUtil.lastServerListUpdateAt = Date()
        SyncLogBus.log("Synced ${items.size} servers from $sourceDescription")
        Log.i(TAG, "Synced ${items.size} servers from $sourceDescription")
        return items.size
    }
}
