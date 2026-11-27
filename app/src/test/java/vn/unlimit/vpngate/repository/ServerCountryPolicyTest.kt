package vn.unlimit.vpngate.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.unlimit.vpngate.models.VPNGateItem

class ServerCountryPolicyTest {
    private fun server(host: String, countryLong: String?, countryShort: String?) =
        VPNGateItem(
            hostName = host, ip = null, countryLong = countryLong, countryShort = countryShort,
            score = 0, ping = 0, speed = 0, numVpnSession = 0, uptime = 0,
            totalUser = 0, totalTraffic = 0L, logType = null, operator = null,
            message = null, openVpnConfigData = null,
        )

    @Test fun excludesRussiaByIsoCodeAndCountryName() {
        val input = listOf(
            server("ru-code", "Russian Federation", "RU"),
            server("ru-name", "Russia", null),
            server("other", "Germany", "DE"),
        )
        assertEquals(listOf("other"), ServerCountryPolicy.excludeRussia(input, true).map { it.hostName })
    }

    @Test fun disabledPolicyKeepsEveryCountry() {
        val input = listOf(server("ru", "Russia", "RU"), server("de", "Germany", "DE"))
        assertEquals(input, ServerCountryPolicy.excludeRussia(input, false))
    }
}
