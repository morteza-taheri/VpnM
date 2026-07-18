package vn.unlimit.vpngate.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import vn.unlimit.vpngate.api.PurchaseApiService
import vn.unlimit.vpngate.models.PurchaseHistory

/**
 * Google Play Billing has been removed from this build - no Google service dependencies.
 * Buying new data packages via createPurchase() is no longer available; listing purchase
 * *history* still works normally since it just reads from the paid-server API.
 */
class PurchaseViewModel(application: Application) : BaseViewModel(application) {
    private val purchaseApiService = retrofit.create(PurchaseApiService::class.java)
    var purchaseList: MutableLiveData<List<PurchaseHistory>> = MutableLiveData(emptyList())
    var isOutOfData: Boolean = false

    companion object {
        const val TAG = "PurchaseViewModel"
        const val ITEM_PER_PAGE = 20
    }

    fun listPurchase(loadFromStart: Boolean = false) {
        if (isLoading.value == true) {
            return
        }
        if (!isOutOfData || loadFromStart) {
            isLoading.value = true
            var skip = purchaseList.value?.size
            if (loadFromStart) {
                skip = 0
                isOutOfData = false
            }
            viewModelScope.launch {
                try {
                    val response = purchaseApiService.listPurchase(ITEM_PER_PAGE, skip)
                    if (loadFromStart) {
                        purchaseList.postValue(response.listPurchase)
                    } else {
                        val merged = ArrayList(purchaseList.value ?: emptyList())
                        merged.addAll(response.listPurchase)
                        purchaseList.postValue(merged)
                    }
                    isOutOfData = response.listPurchase.size < ITEM_PER_PAGE
                } catch (e: Throwable) {
                    Log.e(TAG, "Got exception when list purchase history", e)
                } finally {
                    isLoading.postValue(false)
                }
            }
        }
    }
}
