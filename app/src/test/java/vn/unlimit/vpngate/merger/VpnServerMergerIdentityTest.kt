package vn.unlimit.vpngate.merger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.collector.CollectorTestFixtures
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.parser.VpnGateApiParser
import vn.unlimit.vpngate.parser.VpnGateHtmlParser

/** Identity regressions found when CSV is enabled alongside HTML. */
class VpnServerMergerIdentityTest {

    private fun apiRecord(host: String, ip: String) = VpnGateApiParser.parseApi(
        "*vpn_servers\n" + CollectorTestFixtures.makeApiRow(
            mapOf("HostName" to host, "IP" to ip),
        ),
        "api",
    )[0]

    @Test
    fun `two hostnames sharing one IP remain two servers`() {
        val a = apiRecord("public-vpn-a", "84.54.48.175")
        val b = apiRecord("public-vpn-b", "84.54.48.175")

        val merged = VpnServerMerger.mergeRecords(listOf(a, b))

        assertEquals("IP alone is not an identity", 2, merged.size)
    }

    @Test
    fun `api short hostname merges with html fqdn without losing either source`() {
        val api = apiRecord(
            "public-vpn-206",
            "219.100.37.165",
        )
        val html = VpnGateHtmlParser.parseHtml(
            CollectorTestFixtures.load(CollectorTestFixtures.TABLE_SAMPLE),
            "html",
        ).first {
            VpnRecords.str(VpnRecords.identity(it)["hostname"]).contains("public-vpn-206")
        }

        val merged = VpnServerMerger.mergeRecords(listOf(api, html))

        assertEquals("short API host and HTML FQDN are one server", 1, merged.size)
        val record = merged.single()
        val sources = VpnRecords.sources(record)
        assertTrue("api lost: $sources", "api" in sources)
        assertTrue("html lost: $sources", "html" in sources)

        val openVpn = VpnRecords.map(VpnRecords.protocols(record)["openvpn"])
        assertTrue("CSV OpenVPN profile metadata lost", VpnRecords.bool(openVpn["configAvailable"]))
        val softether = VpnRecords.map(VpnRecords.protocols(record)["softether"])
        assertTrue("HTML SoftEther metadata lost", VpnRecords.bool(softether["supported"]))
    }

    @Test
    fun `enabling csv alongside html does not collapse distinct servers`() {
        val api = VpnGateApiParser.parseApi(
            "*vpn_servers\n" + CollectorTestFixtures.load(CollectorTestFixtures.API_SAMPLE)
                .substringAfter("\n"),
            "api",
        )
        val html = VpnGateHtmlParser.parseHtml(
            CollectorTestFixtures.load(CollectorTestFixtures.TABLE_SAMPLE),
            "html",
        )

        val merged = VpnServerMerger.mergeRecords(api + html)

        assertTrue(
            "API rows must not disappear into each other: ${merged.size} < ${api.size}",
            merged.size >= api.size,
        )
        assertTrue(
            "HTML rows must not disappear into each other: ${merged.size} < ${html.size}",
            merged.size >= html.size,
        )
    }
}
