package vn.unlimit.vpngate.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.collector.CollectorTestFixtures

/**
 * Deterministic coverage of the source-discovery/failover matrix. Every
 * response is a local fixture or a mocked body; no test touches live VPN Gate.
 */
class SourceDiscoveryCoordinatorTest {
    private val table = CollectorTestFixtures.load(CollectorTestFixtures.TABLE_SAMPLE)
    private val api = CollectorTestFixtures.load(CollectorTestFixtures.API_SAMPLE)
    private val badHtml = "<html><body>no table here</body></html>"
    private val badCsv = "this is not a VPN Gate CSV"

    private class FakeFetcher(
        private val pages: Map<String, Pair<Int, String>>,
        private val delays: Map<String, Long> = emptyMap(),
    ) : HttpFetcher {
        val urls = mutableListOf<String>()
        var cancelOn: String? = null

        override suspend fun get(url: String, redirectPolicy: RedirectPolicy?): HttpResponse? {
            urls.add(url)
            delays[url]?.let { delay(it) }
            if (url == cancelOn) throw CancellationException("cancelled by test")
            val body = pages[url] ?: return null
            val type = if ("/api/iphone/" in url) "text/csv" else "text/html; charset=utf-8"
            return HttpResponse(
                requestedUrl = url,
                finalUrl = url,
                status = body.first,
                contentType = type,
                body = body.second,
            )
        }
    }

    private fun ok(body: String) = 200 to body

    private fun directory(vararg mirrors: String): String = buildString {
        append("<html><body><h2>VPNGate.net Mirror Sites</h2>")
        mirrors.forEach { append("<a href=\"").append(it).append("\">mirror</a>") }
        append("</body></html>")
    }

    private fun entry(url: String) = MirrorRegistryEntry(
        publishedUrl = url,
        baseUrl = url.substringBeforeLast('/'),
        publishedPath = "/" + url.substringAfterLast('/'),
        discoveryOrigin = MirrorOrigin.CANONICAL_DIRECTORY,
    )

    private fun coordinator(
        fetcher: FakeFetcher,
        initial: List<MirrorRegistryEntry> = emptyList(),
        saved: MutableList<List<MirrorRegistryEntry>> = mutableListOf(),
    ) = SourceDiscoveryCoordinator(
        fetcher = fetcher,
        loadRegistry = { initial },
        saveRegistry = { saved.add(it) },
        timeoutMs = 2_000L,
        directoryTimeoutMs = 2_000L,
    )

    @Test
    fun `both official sources available`() = runBlocking {
        val f = FakeFetcher(mapOf(VpnGateUrls.MAIN_URL to ok(table), VpnGateUrls.API_URL to ok(api)))
        val r = coordinator(f).discover(includeCsv = true)
        assertNotNull(r.html)
        assertNotNull(r.csv)
        assertTrue(r.summary.officialHtmlAvailable)
        assertTrue(r.summary.officialCsvAvailable)
        assertEquals(0, r.summary.mirrorsDiscovered)
        assertFalse(f.urls.contains(VpnGateUrls.MIRRORS_URL))
    }

    @Test
    fun `html blocked but csv available`() = runBlocking {
        val f = FakeFetcher(mapOf(VpnGateUrls.MAIN_URL to ok(badHtml), VpnGateUrls.API_URL to ok(api)))
        val r = coordinator(f).discover(includeCsv = false)
        assertNull(r.html)
        assertNotNull(r.csv)
        assertTrue(r.summary.officialCsvAvailable)
    }

    @Test
    fun `csv blocked but html available`() = runBlocking {
        val f = FakeFetcher(mapOf(VpnGateUrls.MAIN_URL to ok(table), VpnGateUrls.API_URL to ok(badCsv)))
        val r = coordinator(f).discover(includeCsv = true)
        assertNotNull(r.html)
        assertNull(r.csv)
        assertFalse(r.summary.officialCsvAvailable)
    }

    @Test
    fun `both official sources blocked`() = runBlocking {
        val r = coordinator(FakeFetcher(emptyMap())).discover(includeCsv = true)
        assertNull(r.html)
        assertNull(r.csv)
        assertTrue(r.summary.failedSources.isNotEmpty())
    }

    @Test
    fun `directory with multiple mirrors is parsed`() = runBlocking {
        val m1 = "http://140.199.128.1:1770/en/"
        val m2 = "http://140.199.128.2:1771/en/"
        val f = FakeFetcher(mapOf(VpnGateUrls.MIRRORS_URL to ok(directory(m1, m2))))
        val r = coordinator(f).discover(includeCsv = false)
        assertEquals(2, r.summary.mirrorsDiscovered)
        assertTrue(r.registry.any { it.publishedUrl == m1 })
        assertTrue(r.registry.any { it.publishedUrl == m2 })
    }

    @Test
    fun `directory unavailable uses cached registry`() = runBlocking {
        val mirror = "http://140.199.128.3:1772/en/"
        val f = FakeFetcher(mapOf(mirror to ok(table)))
        val r = coordinator(f, listOf(entry(mirror))).discover(includeCsv = false)
        assertNotNull(r.html)
        assertEquals(VpnGateSourceCandidates.MIRROR_TAG_PREFIX + "1", r.html!!.identity.sourceTag)
    }

    @Test
    fun `changed directory list is merged without erasing old mirror`() = runBlocking {
        val old = "http://140.199.128.4:1773/en/"
        val fresh = "http://140.199.128.5:1774/en/"
        val f = FakeFetcher(mapOf(VpnGateUrls.MIRRORS_URL to ok(directory(fresh))))
        val r = coordinator(f, listOf(entry(old))).discover(includeCsv = false)
        assertTrue(r.registry.any { it.publishedUrl == old })
        assertTrue(r.registry.any { it.publishedUrl == fresh })
    }

    @Test
    fun `non standard port is preserved`() = runBlocking {
        val mirror = "http://140.199.128.6:45678/en/"
        val f = FakeFetcher(
            mapOf(VpnGateUrls.MIRRORS_URL to ok(directory(mirror)), mirror to ok(table)),
        )
        val r = coordinator(f).discover(includeCsv = false)
        assertNotNull(r.html)
        assertEquals(45678, r.html!!.identity.port)
        assertTrue(r.html!!.identity.baseUrl.endsWith(":45678"))
    }

    @Test
    fun `non root mirror path is tried first`() = runBlocking {
        val mirror = "http://140.199.128.7:8080/custom/vpn/"
        val f = FakeFetcher(
            mapOf(VpnGateUrls.MIRRORS_URL to ok(directory(mirror)), mirror to ok(table)),
        )
        val r = coordinator(f).discover(includeCsv = false)
        assertNotNull(r.html)
        assertEquals(mirror, r.html!!.identity.url)
    }

    @Test
    fun `mirror may serve html but not csv`() = runBlocking {
        val mirror = "http://140.199.128.8:1775/en/"
        val csvPath = "http://140.199.128.8:1775/api/iphone/"
        val f = FakeFetcher(
            mapOf(
                VpnGateUrls.MIRRORS_URL to ok(directory(mirror)),
                mirror to ok(table),
                csvPath to ok(badCsv),
            ),
        )
        val r = coordinator(f).discover(includeCsv = true)
        assertNotNull(r.html)
        assertNull(r.csv)
        val saved = r.registry.first { it.publishedUrl == mirror }
        assertTrue(saved.isKnownFor(MirrorResource.HTML))
        assertFalse(saved.isKnownFor(MirrorResource.CSV))
    }

    @Test
    fun `mirror may serve csv but not html`() = runBlocking {
        val mirror = "http://140.199.128.9:1776/en/"
        val csvPath = "http://140.199.128.9:1776/api/iphone/"
        val f = FakeFetcher(
            mapOf(
                VpnGateUrls.MIRRORS_URL to ok(directory(mirror)),
                mirror to ok(badHtml),
                csvPath to ok(api),
            ),
        )
        val r = coordinator(f).discover(includeCsv = true)
        assertNull(r.html)
        assertNotNull(r.csv)
        val saved = r.registry.first { it.publishedUrl == mirror }
        assertFalse(saved.isKnownFor(MirrorResource.HTML))
        assertTrue(saved.isKnownFor(MirrorResource.CSV))
    }

    @Test
    fun `successful http with invalid html is rejected`() = runBlocking {
        val mirror = "http://140.199.128.10:1777/en/"
        val f = FakeFetcher(mapOf(VpnGateUrls.MIRRORS_URL to ok(directory(mirror)), mirror to ok(badHtml)))
        val r = coordinator(f).discover(includeCsv = false)
        assertNull(r.html)
        assertTrue(r.registry.first { it.publishedUrl == mirror }.failureCount > 0)
    }

    @Test
    fun `malformed csv is rejected`() = runBlocking {
        val mirror = "http://140.199.128.11:1778/en/"
        val csvPath = "http://140.199.128.11:1778/api/iphone/"
        val f = FakeFetcher(
            mapOf(VpnGateUrls.MIRRORS_URL to ok(directory(mirror)), csvPath to ok(badCsv)),
        )
        val r = coordinator(f).discover(includeCsv = true)
        assertNull(r.csv)
        assertFalse(r.registry.first { it.publishedUrl == mirror }.isKnownFor(MirrorResource.CSV))
    }

    @Test
    fun `failure on first mirror does not stop second`() = runBlocking {
        val first = "http://140.199.128.12:1779/en/"
        val second = "http://140.199.128.13:1780/en/"
        val f = FakeFetcher(
            mapOf(
                VpnGateUrls.MIRRORS_URL to ok(directory(first, second)),
                first to ok(badHtml),
                second to ok(table),
            ),
        )
        val r = coordinator(f).discover(includeCsv = false)
        assertNotNull(r.html)
        assertEquals(second, r.html!!.identity.url)
        assertTrue(r.summary.failedSources.any { first in it })
    }

    @Test
    fun `all mirrors fail reports failure`() = runBlocking {
        val mirror = "http://140.199.128.14:1781/en/"
        val f = FakeFetcher(mapOf(VpnGateUrls.MIRRORS_URL to ok(directory(mirror)), mirror to ok(badHtml)))
        val r = coordinator(f).discover(includeCsv = false)
        assertNull(r.html)
        assertNull(r.csv)
        assertTrue(r.summary.failedSources.isNotEmpty())
    }

    @Test
    fun `duplicate mirror urls are removed`() = runBlocking {
        val mirror = "http://140.199.128.15:1782/en/"
        val f = FakeFetcher(mapOf(VpnGateUrls.MIRRORS_URL to ok(directory(mirror, mirror))))
        val r = coordinator(f).discover(includeCsv = false)
        assertEquals(1, r.summary.mirrorsDiscovered)
    }

    @Test
    fun `failed directory request does not erase cached mirrors`() = runBlocking {
        val mirror = "http://140.199.128.16:1783/en/"
        val saved = mutableListOf<List<MirrorRegistryEntry>>()
        val r = coordinator(FakeFetcher(emptyMap()), listOf(entry(mirror)), saved).discover(false)
        assertNull(r.html)
        assertTrue(saved.last().any { it.publishedUrl == mirror })
    }

    @Test
    fun `cancellation keeps earlier successful validation`() = runBlocking {
        val first = "http://140.199.128.17:1784/en/"
        val second = "http://140.199.128.18:1785/en/"
        val f = FakeFetcher(
            mapOf(
                VpnGateUrls.MIRRORS_URL to ok(directory(first, second)),
                first to ok(table),
                second to ok(table),
            ),
        )
        f.cancelOn = second
        val saved = mutableListOf<List<MirrorRegistryEntry>>()
        runCatching { coordinator(f, emptyList(), saved).discover(false) }
        assertTrue(
            saved.any { list ->
                list.any { it.publishedUrl == first && it.isKnownFor(MirrorResource.HTML) }
            },
        )
    }

    @Test
    fun `all requests use the injected fetcher only`() = runBlocking {
        val mirror = "http://140.199.128.19:1786/en/"
        val f = FakeFetcher(
            mapOf(VpnGateUrls.MIRRORS_URL to ok(directory(mirror)), mirror to ok(table)),
        )
        coordinator(f).discover(false)
        assertTrue(f.urls.isNotEmpty())
        assertTrue(f.urls.all { it.startsWith("http") })
    }

    @Test
    fun `summary counts only mirrors tested and validated in this run`() = runBlocking {
        val first = "http://140.199.128.20:1787/en/"
        val second = "http://140.199.128.21:1788/en/"
        val third = "http://140.199.128.22:1789/en/"
        val f = FakeFetcher(
            mapOf(
                VpnGateUrls.MAIN_URL to ok(badHtml),
                VpnGateUrls.API_URL to ok(api),
                VpnGateUrls.MIRRORS_URL to ok(directory(first, second, third)),
                first to ok(badHtml),
                second to ok(table),
                third to ok(table),
            ),
        )
        val r = coordinator(f).discover(true)
        assertEquals(3, r.summary.mirrorsDiscovered)
        assertEquals(2, r.summary.mirrorsTested)
        assertEquals(1, r.summary.mirrorsValidated)
    }

    @Test
    fun `historical capability is not counted as current validation`() = runBlocking {
        val mirror = "http://140.199.128.23:1790/en/"
        val cached = entry(mirror).copy(supportedResources = setOf("HTML"), lastSuccessAt = 123L)
        val f = FakeFetcher(mapOf(mirror to ok(badHtml)))
        val r = coordinator(f, listOf(cached)).discover(false)
        assertEquals(0, r.summary.mirrorsValidated)
        assertTrue(r.registry.first().isKnownFor(MirrorResource.HTML))
    }

    @Test
    fun `canonical directory uses its own timeout budget`() = runBlocking {
        val f = FakeFetcher(
            pages = mapOf(VpnGateUrls.MIRRORS_URL to ok(directory("http://140.199.128.24:1791/en/"))),
            delays = mapOf(VpnGateUrls.MIRRORS_URL to 800L),
        )
        val c = SourceDiscoveryCoordinator(
            fetcher = f,
            timeoutMs = 3_000L,
            directoryTimeoutMs = 100L,
        )
        val r = c.discover(false)
        assertNull(r.html)
        assertEquals(0, r.summary.mirrorsDiscovered)
    }
}
