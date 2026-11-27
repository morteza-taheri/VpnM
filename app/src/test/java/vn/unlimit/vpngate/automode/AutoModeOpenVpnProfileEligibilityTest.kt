package vn.unlimit.vpngate.automode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.models.VPNGateConnection

/** Auto Mode must distinguish a locally cached profile from a download descriptor. */
class AutoModeOpenVpnProfileEligibilityTest {

    private fun VPNGateConnection.toAutoCandidate(): AutoModeCandidate =
        with(AutoModeEngine) { toCandidate() }

    @Test
    fun `download identifiers without local profile do not make OpenVPN eligible`() {
        val connection = VPNGateConnection().apply {
            openVpnDownloadSid = "published-sid"
            openVpnDownloadHid = "published-hid"
            openVpnConfigData = null
        }

        assertTrue("the record may offer an on-demand manual download", connection.canDownloadOpenVpnConfig)
        assertFalse(
            "Auto Mode must require profile bytes already stored locally",
            connection.toAutoCandidate().openVpnConfigAvailable,
        )
    }

    @Test
    fun `a locally cached profile makes OpenVPN eligible`() {
        val connection = VPNGateConnection().apply {
            openVpnDownloadSid = "published-sid"
            openVpnDownloadHid = "published-hid"
            openVpnConfigData = "client\nremote 203.0.113.10 1194\n"
        }

        assertTrue(connection.toAutoCandidate().openVpnConfigAvailable)
    }

    @Test
    fun `blank profile is not locally ready even when download identifiers exist`() {
        val connection = VPNGateConnection().apply {
            openVpnDownloadSid = "published-sid"
            openVpnDownloadHid = "published-hid"
            openVpnConfigData = "   \n  "
        }

        assertFalse(connection.toAutoCandidate().openVpnConfigAvailable)
    }
}
