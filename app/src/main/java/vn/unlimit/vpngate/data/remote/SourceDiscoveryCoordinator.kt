package vn.unlimit.vpngate.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** User-visible stages of one source-discovery run. */
enum class SourceDiscoveryStage {
    CHECKING_OFFICIAL_SOURCES,
    DOWNLOADING_HTML,
    DOWNLOADING_CSV,
    DISCOVERING_MIRRORS,
    TESTING_MIRRORS,
    MERGING,
    COMPLETED,
    FAILED,
}

data class SourceDiscoverySummary(
    val officialHtmlAvailable: Boolean = false,
    val officialCsvAvailable: Boolean = false,
    val mirrorsDiscovered: Int = 0,
    val mirrorsTested: Int = 0,
    val mirrorsValidated: Int = 0,
    val htmlSourceUsed: String? = null,
    val csvSourceUsed: String? = null,
    val failedSources: List<String> = emptyList(),
    val recordsReceived: Int = 0,
)

data class SourceDiscoveryResult(
    val html: FetchedSource?,
    val csv: FetchedSource?,
    val summary: SourceDiscoverySummary,
    val registry: List<MirrorRegistryEntry>,
)

/**
 * Owns the health checks, mirror discovery and per-mirror validation for one
 * refresh. It never writes the server database: it only returns the bodies the
 * repository is allowed to parse.
 *
 * HTML and CSV are separate resources. A mirror that serves a valid hosts
 * table is not assumed to serve the canonical CSV schema, and vice versa.
 */
class SourceDiscoveryCoordinator(
    private val fetcher: HttpFetcher,
    private val loadRegistry: () -> List<MirrorRegistryEntry> = { emptyList() },
    private val saveRegistry: (List<MirrorRegistryEntry>) -> Unit = {},
    private val progress: (SourceDiscoveryStage, String) -> Unit = { _, _ -> Unit },
    private val timeoutMs: Long = DEFAULT_SOURCE_TIMEOUT_MS,
    private val directoryTimeoutMs: Long = DEFAULT_DIRECTORY_TIMEOUT_MS,
) {
    private val prober = SourceProber(fetcher)

    suspend fun discover(includeCsv: Boolean): SourceDiscoveryResult {
        val registry = loadRegistry().toMutableList()
        if (registry.isEmpty()) registry.addAll(bootstrapEntries())
        // Cached/bootstrap mirrors must be allowed through the real interceptor
        // before the first cleartext request, including directory probes.
        registerMirrors(registry)

        val failed = mutableListOf<String>()
        val testedKeys = mutableSetOf<String>()
        val validatedKeys = mutableSetOf<String>()
        var officialHtml = false
        var officialCsv = false

        progress(SourceDiscoveryStage.CHECKING_OFFICIAL_SOURCES, "official sources")
        var selectedHtml: FetchedSource? = null
        var selectedCsv: FetchedSource? = null

        coroutineScope {
            val htmlDeferred = async {
                progress(SourceDiscoveryStage.DOWNLOADING_HTML, VpnGateUrls.MAIN_URL)
                probeCanonical(SourceResource.HTML, VpnGateUrls.MAIN_URL, SourceKind.CANONICAL_HTML)
            }
            val csvDeferred = if (includeCsv) {
                async {
                    progress(SourceDiscoveryStage.DOWNLOADING_CSV, VpnGateUrls.API_URL)
                    probeCanonical(SourceResource.CSV, VpnGateUrls.API_URL, SourceKind.CANONICAL_API)
                }
            } else null

            val htmlProbe = htmlDeferred.await()
            // The setting controls "CSV alongside HTML". It must not disable the
            // long-standing CSV fallback when HTML produced no usable records.
            val csvProbe = csvDeferred?.await()
                ?: if (htmlProbe.accepted) null else probeCanonical(
                    SourceResource.CSV,
                    VpnGateUrls.API_URL,
                    SourceKind.CANONICAL_API,
                )
            officialHtml = htmlProbe.accepted
            selectedHtml = htmlProbe.fetched
            if (htmlProbe.accepted) {
                failed.removeAll { it.startsWith("HTML:") }
            } else {
                failed.add(htmlProbe.label)
            }
            if (csvProbe != null) {
                officialCsv = csvProbe.accepted
                selectedCsv = csvProbe.fetched
                if (csvProbe.accepted) {
                    failed.removeAll { it.startsWith("CSV:") }
                } else {
                    failed.add(csvProbe.label)
                }
            }
        }

        val needHtml = selectedHtml == null
        val needCsv = selectedCsv == null && (includeCsv || selectedHtml == null)
        var newlyDiscovered = emptyList<MirrorRegistryEntry>()

        if (needHtml || needCsv) {
            val directory = discoverDirectory(registry, failed, testedKeys)
            val discovered = directory.second
            val existingKeys = registry.map { MirrorRegistryStore.keyOf(it) }.toSet()
            newlyDiscovered = discovered.filterNot {
                MirrorRegistryStore.keyOf(it) in existingKeys
            }
            // Validated directory entries are registered BEFORE any request to
            // them, so the real cleartext interceptor cannot refuse them.
            registerMirrors(discovered)
            val merged = MirrorRegistryStore.merge(
                registry,
                discovered,
                System.currentTimeMillis(),
            )
            registry.clear()
            registry.addAll(merged)
            saveRegistry(registry.toList())

            val mirrorResult = testMirrors(
                registry,
                needHtml,
                needCsv,
                failed,
                testedKeys,
                validatedKeys,
            )
            if (selectedHtml == null) selectedHtml = mirrorResult.first
            if (selectedCsv == null) selectedCsv = mirrorResult.second
        }

        saveRegistry(registry.toList())
        val htmlRows = selectedHtml?.let { src ->
            VpnGateSourceValidation.validateHtml(FetchedSource(src.identity, src.response)).rowCount
        } ?: 0
        val csvRows = selectedCsv?.let { src ->
            VpnGateSourceValidation.validateApi(FetchedSource(src.identity, src.response)).rowCount
        } ?: 0
        val summary = SourceDiscoverySummary(
            officialHtmlAvailable = officialHtml,
            officialCsvAvailable = officialCsv,
            mirrorsDiscovered = newlyDiscovered.size,
            mirrorsTested = testedKeys.size,
            mirrorsValidated = validatedKeys.size,
            htmlSourceUsed = selectedHtml?.identity?.sourceTag,
            csvSourceUsed = selectedCsv?.identity?.sourceTag,
            failedSources = failed.distinct(),
            recordsReceived = htmlRows + csvRows,
        )
        if (selectedHtml != null || selectedCsv != null) {
            progress(SourceDiscoveryStage.COMPLETED, summary.toString())
        } else {
            progress(SourceDiscoveryStage.FAILED, "all sources failed")
        }
        return SourceDiscoveryResult(selectedHtml, selectedCsv, summary, registry.toList())
    }

    private suspend fun probeCanonical(
        resource: SourceResource,
        url: String,
        kind: SourceKind,
        budgetMs: Long = timeoutMs,
    ): SourceProbeResult {
        val identity = SourceUrl.parse(url, kind)
            ?: return rejectedIdentity(resource, url)
        return probe(identity, resource, budgetMs) ?: timeoutResult(identity, resource, budgetMs)
    }

    private suspend fun discoverDirectory(
        registry: List<MirrorRegistryEntry>,
        failed: MutableList<String>,
        testedKeys: MutableSet<String>,
    ): Pair<FetchedSource?, List<MirrorRegistryEntry>> {
        progress(SourceDiscoveryStage.DISCOVERING_MIRRORS, VpnGateUrls.MIRRORS_URL)
        // The canonical directory has its OWN budget, independent of HTML/CSV.
        var probe = probeCanonical(
            SourceResource.DIRECTORY,
            VpnGateUrls.MIRRORS_URL,
            SourceKind.CANONICAL_HTML,
            directoryTimeoutMs,
        )
        if (!probe.accepted) {
            failed.add(probe.label)
            // A failed directory request must NOT erase the cached registry.
            for (entry in registry.take(3)) {
                testedKeys.add(MirrorRegistryStore.keyOf(entry))
                val plan = MirrorResourcePaths.planFor(entry.publishedUrl) ?: continue
                var entryAccepted = false
                for (path in plan.directoryPaths) {
                    val identity = identityFor(entry, path) ?: continue
                    probe = probe(identity, SourceResource.DIRECTORY, directoryTimeoutMs)
                        ?: timeoutResult(identity, SourceResource.DIRECTORY, directoryTimeoutMs)
                    if (probe.accepted) {
                        entryAccepted = true
                        break
                    }
                }
                if (entryAccepted) break
                failed.add(probe.label)
            }
        }
        val body = probe.fetched?.body ?: return null to emptyList()
        return probe.fetched to MirrorDirectoryParser.extract(body)
    }

    private suspend fun testMirrors(
        registry: MutableList<MirrorRegistryEntry>,
        needHtml: Boolean,
        needCsv: Boolean,
        failed: MutableList<String>,
        testedKeys: MutableSet<String>,
        validatedKeys: MutableSet<String>,
    ): Pair<FetchedSource?, FetchedSource?> {
        val updated = registry.toMutableList()
        val now = System.currentTimeMillis()
        val candidates = registry.filter { entry ->
            (!needHtml || !MirrorRegistryStore.shouldSkipForResource(entry, MirrorResource.HTML, now)) &&
                (!needCsv || !MirrorRegistryStore.shouldSkipForResource(entry, MirrorResource.CSV, now))
        }.take(VpnGateUrls.MAX_MIRRORS)
        var html: FetchedSource? = null
        var csv: FetchedSource? = null
        progress(SourceDiscoveryStage.TESTING_MIRRORS, "0/${candidates.size}")

        for ((index, entry) in candidates.withIndex()) {
            val key = MirrorRegistryStore.keyOf(entry)
            testedKeys.add(key)
            progress(
                SourceDiscoveryStage.TESTING_MIRRORS,
                "${index + 1}/${candidates.size} ${entry.baseUrl}",
            )
            val plan = MirrorResourcePaths.planFor(entry.publishedUrl) ?: continue

            for (resource in listOf(SourceResource.HTML, SourceResource.CSV)) {
                if (resource == SourceResource.HTML && (!needHtml || html != null)) continue
                if (resource == SourceResource.CSV && (!needCsv || csv != null)) continue
                val mirrorResource = MirrorResource.valueOf(resource.name)
                val paths = if (resource == SourceResource.HTML) plan.htmlPaths else plan.csvPaths
                var accepted = false
                var lastResult: SourceProbeResult? = null

                for (path in paths) {
                    val identity = identityFor(entry, path)?.withMirrorId(
                        VpnGateSourceCandidates.MIRROR_TAG_PREFIX + (index + 1),
                    ) ?: continue
                    val result = probe(identity, resource, timeoutMs)
                        ?: timeoutResult(identity, resource, timeoutMs)
                    lastResult = result
                    if (result.accepted) {
                        if (resource == SourceResource.HTML) html = result.fetched else csv = result.fetched
                        validatedKeys.add(key)
                        accepted = true
                        break
                    }
                    failed.add(result.label)
                }

                // ONE health record per resource per refresh cycle, regardless
                // of how many candidate paths were tried.
                val finalResult = lastResult ?: noCandidateResult(entry, resource)
                val at = updated.indexOfFirst {
                    MirrorRegistryStore.keyOf(it) == key
                }
                if (at >= 0) {
                    updated[at] = MirrorRegistryStore.recordResult(
                        updated[at],
                        mirrorResource,
                        accepted,
                        finalResult.status,
                        finalResult.responseTimeMs,
                        System.currentTimeMillis(),
                        finalResult.detail,
                    )
                    // Persist each resource verdict so cancellation or process
                    // death cannot lose a previously successful validation.
                    saveRegistry(updated.toList())
                }
                if (!accepted) failed.add("${resource.name}:${entry.baseUrl} (no valid path)")
            }
            if ((!needHtml || html != null) && (!needCsv || csv != null)) break
        }
        registry.clear()
        registry.addAll(updated)
        return html to csv
    }

    /**
     * Register only identities that already passed [SourceUrl.forMirror].
     * Nothing else can become a permitted cleartext authority.
     */
    private fun registerMirrors(entries: Collection<MirrorRegistryEntry>) {
        val identities = entries.mapNotNull { identityFor(it) }
        VpnGateSourceCandidates.registerForCleartext(identities)
    }

    private fun identityFor(
        entry: MirrorRegistryEntry,
        path: String = entry.publishedPath,
    ): SourceIdentity? {
        val kind = when (entry.discoveryOrigin) {
            MirrorOrigin.MIRROR_CACHE -> SourceKind.CACHED_MIRROR
            MirrorOrigin.BOOTSTRAP_LIST -> SourceKind.BOOTSTRAP_MIRROR
            else -> SourceKind.DISCOVERED_MIRROR
        }
        return SourceUrl.forMirror(entry.baseUrl, kind, entry.discoveryOrigin)
            ?.withPath(path)
    }

    private suspend fun probe(
        identity: SourceIdentity,
        resource: SourceResource,
        budgetMs: Long,
    ): SourceProbeResult? = try {
        withTimeoutOrNull(budgetMs.coerceAtLeast(1L)) { prober.probe(identity, resource) }
    } catch (e: CancellationException) {
        throw e
    }

    private fun timeoutResult(
        identity: SourceIdentity,
        resource: SourceResource,
        budgetMs: Long,
    ) = SourceProbeResult(
        identity = identity,
        resource = resource,
        accepted = false,
        validation = SourceValidation.reject(SourceRejectReason.NETWORK_FAILURE, "timeout"),
        status = 0,
        responseTimeMs = budgetMs,
        contentType = null,
        finalUrl = null,
        redirectRejected = false,
        fetched = null,
        detail = "timeout",
    )

    private fun noCandidateResult(
        entry: MirrorRegistryEntry,
        resource: SourceResource,
    ) = SourceProbeResult(
        identity = SourceIdentity(
            SourceKind.DISCOVERED_MIRROR,
            "http",
            entry.baseUrl.substringAfter("://").substringBefore(':'),
            path = entry.publishedPath,
        ),
        resource = resource,
        accepted = false,
        validation = SourceValidation.reject(SourceRejectReason.NETWORK_FAILURE, "no candidate path"),
        status = 0,
        responseTimeMs = 0L,
        contentType = null,
        finalUrl = null,
        redirectRejected = false,
        fetched = null,
        detail = "no candidate path",
    )

    private fun rejectedIdentity(resource: SourceResource, url: String) = SourceProbeResult(
        identity = SourceIdentity(SourceKind.DISCOVERED_MIRROR, "http", "invalid", path = "/"),
        resource = resource,
        accepted = false,
        validation = SourceValidation.reject(SourceRejectReason.NETWORK_FAILURE, "invalid URL: $url"),
        status = 0,
        responseTimeMs = 0L,
        contentType = null,
        finalUrl = null,
        redirectRejected = false,
        fetched = null,
        detail = "invalid URL: $url",
    )

    private fun bootstrapEntries(): List<MirrorRegistryEntry> =
        VpnGateUrls.BOOTSTRAP_MIRRORS.mapNotNull { url ->
            SourceUrl.forMirror(url, SourceKind.BOOTSTRAP_MIRROR, MirrorOrigin.BOOTSTRAP_LIST)
                ?.let { identity ->
                    MirrorRegistryEntry(
                        publishedUrl = identity.url,
                        baseUrl = identity.baseUrl,
                        publishedPath = identity.path,
                        discoveredAt = System.currentTimeMillis(),
                        discoveryOrigin = MirrorOrigin.BOOTSTRAP_LIST,
                    )
                }
        }
}
