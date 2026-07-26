package vn.unlimit.vpngate.utils

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small rolling log of connection activity from all three VPN modules (SoftEther, OpenVPN,
 * MS-SSTP), each line tagged with which module produced it. Shown on the Home screen with a
 * Clear button - separate from [SyncLogBus] (server-list sync activity) and the OpenVPN-only
 * log console on the status/dashboard screen.
 */
object AppLogBus {
    private const val MAX_ENTRIES = 300
    private val buffer = ArrayList<String>()
    private val _entries = MutableLiveData<List<String>>(emptyList())
    val entries: LiveData<List<String>> get() = _entries
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun log(module: String, message: String) {
        if (message.isBlank()) return
        buffer.add("[${timeFormat.format(Date())}] [$module] $message")
        while (buffer.size > MAX_ENTRIES) {
            buffer.removeAt(0)
        }
        _entries.postValue(ArrayList(buffer))
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        _entries.postValue(emptyList())
    }
}
