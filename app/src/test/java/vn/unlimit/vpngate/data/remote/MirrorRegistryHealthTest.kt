package vn.unlimit.vpngate.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-resource health must count failed refresh cycles, not failed URL attempts. */
class MirrorRegistryHealthTest {

    private val entry = MirrorRegistryEntry(
        publishedUrl = "http://mirror-review.opengw.net:7777/en/",
        baseUrl = "http://mirror-review.opengw.net:7777",
        publishedPath = "/en/",
        discoveryOrigin = MirrorOrigin.CANONICAL_DIRECTORY,
    )

    @Test
    fun `three failed refresh cycles deactivate html only`() {
        var current = entry
        for (cycle in 1..MirrorRegistryStore.REPEATED_FAILURE_LIMIT) {
            current = MirrorRegistryStore.recordResult(
                current,
                MirrorResource.HTML,
                accepted = false,
                status = 503,
                responseTimeMs = 100L,
                now = 1_000L * cycle,
                detail = "HTTP 503",
            )
        }

        assertEquals(MirrorRegistryStore.REPEATED_FAILURE_LIMIT, current.htmlConsecutiveFailures)
        assertEquals(0, current.csvConsecutiveFailures)
        assertTrue(MirrorRegistryStore.isDeactivatedFor(current, MirrorResource.HTML))
        assertFalse(MirrorRegistryStore.isDeactivatedFor(current, MirrorResource.CSV))
        assertEquals(0L, current.lastSuccessAt)
    }

    @Test
    fun `a historical success is preserved but the resource can still be deactivated`() {
        var current = MirrorRegistryStore.recordResult(
            entry,
            MirrorResource.HTML,
            accepted = true,
            status = 200,
            responseTimeMs = 50L,
            now = 500L,
            detail = "ok",
        )
        val historicalSuccess = current.lastSuccessAt

        for (cycle in 1..MirrorRegistryStore.REPEATED_FAILURE_LIMIT) {
            current = MirrorRegistryStore.recordResult(
                current,
                MirrorResource.HTML,
                accepted = false,
                status = 500,
                responseTimeMs = 100L,
                now = 5_000L * cycle,
                detail = "HTTP 500",
            )
        }

        assertEquals(historicalSuccess, current.lastSuccessAt)
        assertTrue(current.isKnownFor(MirrorResource.HTML))
        assertTrue(MirrorRegistryStore.isDeactivatedFor(current, MirrorResource.HTML))
    }

    @Test
    fun `a pass resets only that resource and a cooldown allows retry`() {
        var current = entry
        for (cycle in 1..MirrorRegistryStore.REPEATED_FAILURE_LIMIT) {
            current = MirrorRegistryStore.recordResult(
                current, MirrorResource.HTML, false, 500, 100L, 1_000L * cycle, "fail",
            )
        }
        assertTrue(MirrorRegistryStore.shouldSkipForResource(current, MirrorResource.HTML, 4_000L))
        assertFalse(
            "a deactivated mirror must be retried after the cooldown",
            MirrorRegistryStore.shouldSkipForResource(
                current,
                MirrorResource.HTML,
                4_000L + MirrorRegistryStore.RETRY_COOLDOWN_MS + 1L,
            ),
        )

        current = MirrorRegistryStore.recordResult(
            current, MirrorResource.HTML, true, 200, 20L, 9_000L, "ok",
        )
        assertEquals(0, current.htmlConsecutiveFailures)
        assertTrue(current.lastHtmlSuccessAt > 0L)
        assertEquals(0, current.csvConsecutiveFailures)
    }
}
