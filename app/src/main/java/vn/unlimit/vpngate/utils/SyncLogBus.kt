package vn.unlimit.vpngate.utils

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small rolling log of server-list sync activity - which source was tried, and whether it
 * succeeded or failed - shown in the "Server sources" settings screen so the user can see what
 * the app is actually doing when it refreshes the list (manually or in the background).
 *
 * This is intentionally separate from the OpenVPN connection log console on the status screen
 * (that one is about the VPN tunnel; this one is about fetching the server list).
 */
object SyncLogBus {
    private const val MAX_ENTRIES = 200
    private val buffer = ArrayList<String>()
    private val _entries = MutableLiveData<List<String>>(emptyList())
    val entries: LiveData<List<String>> get() = _entries
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun log(message: String) {
        buffer.add("[${timeFormat.format(Date())}] $message")
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
