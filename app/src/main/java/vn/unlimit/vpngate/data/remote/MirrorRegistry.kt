package vn.unlimit.vpngate.data.remote

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import vn.unlimit.vpngate.parser.VpnGateHtmlParser

/** One resource a mirror was validated for. */
enum class MirrorResource {
    HTML, CSV
}

/**
 * Persistent last-known-good mirror metadata.
 *
 * The old cache was a bare `List<String>`: a URL had no memory of when it was
 * seen, what it served, or whether it had failed 20 times in a row. This entry
 * keeps that evidence without changing the Room schema — the registry lives in
 * SharedPreferences and is therefore migration-free.
 *
 * Health is tracked per resource. Multiple failed path candidates inside ONE
 * refresh are a single failed refresh cycle for that resource, not five
 * separate failures.
 */
data class MirrorRegistryEntry(
    /** The URL exactly as published by the official directory (or cached). */
    val publishedUrl: String,
    /** Scheme + authority without the per-resource path. */
    val baseUrl: String,
    /** Normalized path from the published URL, e.g. `/en/`. */
    val publishedPath: String = "/",
    val discoveredAt: Long = 0L,
    /** Historical overall success — never cleared by a temporary outage. */
    val lastSuccessAt: Long = 0L,
    val lastFailureAt: Long = 0L,
    /** Resources that passed structural validation at least once (historical). */
    val supportedResources: Set<String> = emptySet(),
    val lastStatus: Int = 0,
    val lastResponseTimeMs: Long = 0L,
    /** Legacy display counter; derived from the per-resource counters. */
    val failureCount: Int = 0,
    val lastResult: String = "",
    val discoveryOrigin: String? = null,
    val htmlConsecutiveFailures: Int = 0,
    val csvConsecutiveFailures: Int = 0,
    val lastHtmlFailureAt: Long = 0L,
    val lastCsvFailureAt: Long = 0L,
    val lastHtmlSuccessAt: Long = 0L,
    val lastCsvSuccessAt: Long = 0L,
) {
    fun isKnownFor(resource: MirrorResource): Boolean =
        resource.name in supportedResources

    fun consecutiveFailures(resource: MirrorResource): Int = when (resource) {
        MirrorResource.HTML -> htmlConsecutiveFailures
        MirrorResource.CSV -> csvConsecutiveFailures
    }
}

/** Pure persistence and merge rules for the mirror registry. */
object MirrorRegistryStore {
    internal const val MAX_ENTRIES = 15
    /** Consecutive FAILED REFRESH CYCLES, not failed URL attempts. */
    internal const val REPEATED_FAILURE_LIMIT = 3
    /** A deactivated resource is retried after this cooldown. */
    internal const val RETRY_COOLDOWN_MS = 30 * 60_000L

    private val gson = Gson()
    private val listType = object : TypeToken<List<MirrorRegistryEntry>>() {}.type

    fun load(json: String?): List<MirrorRegistryEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            gson.fromJson<List<MirrorRegistryEntry>>(json, listType).orEmpty()
        }.getOrDefault(emptyList())
    }

    fun toJson(entries: List<MirrorRegistryEntry>): String =
        gson.toJson(entries.take(MAX_ENTRIES))

    /** Identity used for de-duplication: scheme + authority + normalized path. */
    fun keyOf(entry: MirrorRegistryEntry): String =
        entry.publishedUrl.trimEnd('/').lowercase()

    /**
     * Merge newly published mirrors into the saved registry.
     *
     * A failed directory fetch must never erase the saved list, so this only
     * adds/updates entries. Old entries stay until the repeated-failure policy
     * explicitly deactivates them, and even then they are kept as retry candidates.
     */
    fun merge(
        existing: List<MirrorRegistryEntry>,
        discovered: List<MirrorRegistryEntry>,
        now: Long,
    ): List<MirrorRegistryEntry> {
        // Newly discovered authorities are tried before the older bootstrap
        // set: the directory is the current source of truth, and provenance
        // tags mirror_1..N must describe fresh discoveries first.
        val orderedKeys = LinkedHashSet<String>()
        for (entry in discovered) {
            val key = keyOf(entry)
            if (key.isNotBlank()) orderedKeys.add(key)
        }
        for (entry in existing) {
            val key = keyOf(entry)
            if (key.isNotBlank()) orderedKeys.add(key)
        }
        val byKey = LinkedHashMap<String, MirrorRegistryEntry>()
        for (key in orderedKeys) {
            val fresh = discovered.firstOrNull { keyOf(it) == key }
            val old = existing.firstOrNull { keyOf(it) == key }
            byKey[key] = when {
                old != null && fresh != null -> old.copy(
                    // A mirror still published by the directory is freshly seen;
                    // its historical successes/failures stay intact.
                    discoveredAt = now,
                    publishedUrl = fresh.publishedUrl,
                    baseUrl = fresh.baseUrl,
                    publishedPath = fresh.publishedPath,
                    discoveryOrigin = fresh.discoveryOrigin ?: old.discoveryOrigin,
                )
                fresh != null -> fresh.copy(
                    discoveredAt = fresh.discoveredAt.takeIf { it > 0L } ?: now,
                )
                else -> old!!
            }
        }
        // Known-good first, then most recently seen. Nothing is discarded here;
        // the cap is applied only when choosing candidates for one run.
        return byKey.values.sortedWith(
            compareByDescending<MirrorRegistryEntry> { it.lastSuccessAt }
                .thenByDescending { it.discoveredAt },
        ).take(MAX_ENTRIES)
    }

    /**
     * Record ONE resource result for ONE refresh cycle.
     *
     * Call this after all candidate paths for the resource have been tried:
     * path-level attempts must not inflate the consecutive failure counter.
     */
    fun recordResult(
        entry: MirrorRegistryEntry,
        resource: MirrorResource,
        accepted: Boolean,
        status: Int,
        responseTimeMs: Long,
        now: Long,
        detail: String,
    ): MirrorRegistryEntry {
        val resources = if (accepted) entry.supportedResources + resource.name else entry.supportedResources
        val htmlFailures: Int
        val csvFailures: Int
        val htmlFailureAt: Long
        val csvFailureAt: Long
        val htmlSuccessAt: Long
        val csvSuccessAt: Long
        when (resource) {
            MirrorResource.HTML -> {
                htmlFailures = if (accepted) 0 else entry.htmlConsecutiveFailures + 1
                csvFailures = entry.csvConsecutiveFailures
                htmlFailureAt = if (accepted) 0L else now
                csvFailureAt = entry.lastCsvFailureAt
                htmlSuccessAt = if (accepted) now else entry.lastHtmlSuccessAt
                csvSuccessAt = entry.lastCsvSuccessAt
            }
            MirrorResource.CSV -> {
                htmlFailures = entry.htmlConsecutiveFailures
                csvFailures = if (accepted) 0 else entry.csvConsecutiveFailures + 1
                htmlFailureAt = entry.lastHtmlFailureAt
                csvFailureAt = if (accepted) 0L else now
                htmlSuccessAt = entry.lastHtmlSuccessAt
                csvSuccessAt = if (accepted) now else entry.lastCsvSuccessAt
            }
        }
        return entry.copy(
            supportedResources = resources,
            lastSuccessAt = if (accepted) now else entry.lastSuccessAt,
            lastFailureAt = if (accepted) entry.lastFailureAt else now,
            lastStatus = status,
            lastResponseTimeMs = responseTimeMs,
            failureCount = maxOf(htmlFailures, csvFailures),
            lastResult = detail.take(200),
            htmlConsecutiveFailures = htmlFailures,
            csvConsecutiveFailures = csvFailures,
            lastHtmlFailureAt = htmlFailureAt,
            lastCsvFailureAt = csvFailureAt,
            lastHtmlSuccessAt = htmlSuccessAt,
            lastCsvSuccessAt = csvSuccessAt,
        )
    }

    /** True when a resource has reached the repeated-failure threshold. */
    fun isDeactivatedFor(entry: MirrorRegistryEntry, resource: MirrorResource): Boolean =
        entry.consecutiveFailures(resource) >= REPEATED_FAILURE_LIMIT

    /**
     * A deactivated resource is skipped for a bounded cooldown and then retried.
     * Nothing is ever deleted from the registry for being temporarily down.
     */
    fun shouldSkipForResource(
        entry: MirrorRegistryEntry,
        resource: MirrorResource,
        now: Long,
    ): Boolean {
        if (!isDeactivatedFor(entry, resource)) return false
        val failedAt = when (resource) {
            MirrorResource.HTML -> entry.lastHtmlFailureAt
            MirrorResource.CSV -> entry.lastCsvFailureAt
        }
        return now - failedAt < RETRY_COOLDOWN_MS
    }
}

/**
 * Derive candidate resource URLs from a mirror's *published* URL.
 *
 * Nothing here is trusted until a fetch has passed [VpnGateSourceValidation].
 * The published path is always tried first, so a mirror with a non-root path
 * is not silently rewritten to `/en/` or `/api/iphone/`.
 */
object MirrorResourcePaths {
    data class Plan(
        val baseUrl: String,
        val htmlPaths: List<String>,
        val csvPaths: List<String>,
        val directoryPaths: List<String>,
    )

    fun planFor(publishedUrl: String): Plan? {
        val identity = SourceUrl.forMirror(
            publishedUrl,
            SourceKind.DISCOVERED_MIRROR,
            MirrorOrigin.CANONICAL_DIRECTORY,
        ) ?: return null
        val published = identity.path
        val html = linkedSetOf<String>()
        if (published != "/" && !published.endsWith("sites.aspx", ignoreCase = true)) {
            html.add(published)
        }
        html.add("/en/")
        html.add("/")

        val csv = linkedSetOf<String>()
        val replaced = when {
            published.contains("/en/", ignoreCase = true) ->
                published.replace("/en/", "/api/iphone/", ignoreCase = true)
            published == "/" -> "/api/iphone/"
            else -> null
        }
        if (replaced != null) csv.add(replaced)
        csv.add("/api/iphone/")

        val directory = linkedSetOf<String>()
        if (published == "/en/") directory.add("/en/sites.aspx")
        if (published.endsWith("/")) {
            directory.add(published + "sites.aspx")
        } else {
            val parent = published.substringBeforeLast('/', "")
            directory.add(if (parent.isBlank()) "/sites.aspx" else "$parent/sites.aspx")
        }
        directory.add("/en/sites.aspx")
        directory.add("/sites.aspx")

        return Plan(
            baseUrl = identity.baseUrl,
            htmlPaths = html.toList(),
            csvPaths = csv.toList(),
            directoryPaths = directory.toList(),
        )
    }
}

/** Extract official mirrors from the real `sites.aspx` page. */
object MirrorDirectoryParser {
    fun extract(html: String): List<MirrorRegistryEntry> {
        val now = System.currentTimeMillis()
        val doc = VpnGateHtmlParser.makeSoup(html)
        val seen = HashSet<String>()
        val result = mutableListOf<MirrorRegistryEntry>()
        for (anchor in doc.getElementsByTag("a")) {
            val href = anchor.attr("href").trim()
            if (!VpnGateSourceValidation.isAcceptableMirrorHref(href)) continue
            val identity = SourceUrl.forMirror(
                href,
                SourceKind.DISCOVERED_MIRROR,
                MirrorOrigin.CANONICAL_DIRECTORY,
            ) ?: continue
            val published = identity.url
            val key = published.trimEnd('/').lowercase()
            if (!seen.add(key)) continue
            result.add(
                MirrorRegistryEntry(
                    publishedUrl = published,
                    baseUrl = identity.baseUrl,
                    publishedPath = identity.path,
                    discoveredAt = now,
                    discoveryOrigin = MirrorOrigin.CANONICAL_DIRECTORY,
                ),
            )
        }
        return result
    }
}
