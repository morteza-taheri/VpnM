package vn.unlimit.vpngate.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.models.VPNGateConnectionList
import vn.unlimit.vpngate.utils.DataUtil
import vn.unlimit.vpngate.utils.ServerListRepository

class ConnectionListViewModel(application: Application) : BaseViewModel(application) {
    companion object {
        const val TAG = "VPNGateViewModel"
    }

    var dataUtil: DataUtil = App.instance!!.dataUtil!!

    val vpnGateConnectionList = MutableLiveData<VPNGateConnectionList>()
    init {
        viewModelScope.launch(Dispatchers.IO) {
            val connectionCache = dataUtil.connectionsCache
            connectionCache?.let {
                withContext(Dispatchers.Main) {
                    vpnGateConnectionList.value = it
                }
            }
        }
    }
    var isError: MutableLiveData<Boolean> = MutableLiveData(false)

    /**
     * Refreshes the server list using [ServerListRepository], which already tries the primary
     * VPN Gate API, its mirror, and the GitHub CSV fallback in order - see that class for why.
     */
    fun getAPIData() {
        if (isLoading.value == true) {
            return
        }
        Log.d(TAG, "Start vpnItem from API")
        isLoading.postValue(true)
        isError.postValue(false)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ServerListRepository.syncNow(getApplication())
                }
                val items = withContext(Dispatchers.IO) { App.instance!!.vpnGateItemDao.getAll() }
                vpnGateConnectionList.value = VPNGateConnectionList().fromVPNGateItems(items)
                Log.i(TAG, "Total in database: ${items.size}")
            } catch (e: Throwable) {
                Log.e(TAG, "Got exception when get connection list", e)
                isError.postValue(true)
            } finally {
                isLoading.postValue(false)
            }
        }
    }
}
