package vn.unlimit.vpngate.fragment.paidserver

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import de.blinkt.openvpn.core.OpenVPNService
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.activities.paid.LoginActivity
import vn.unlimit.vpngate.activities.paid.PaidServerActivity
import vn.unlimit.vpngate.databinding.FragmentBuyDataBinding
import vn.unlimit.vpngate.viewmodels.PurchaseViewModel
import vn.unlimit.vpngate.viewmodels.UserViewModel

/**
 * Google Play Billing has been removed from this build - no Google service dependencies.
 * This screen now only shows the user's current data balance; buying new data packages is
 * not available (that always required the Google Play Store).
 */
class BuyDataFragment : Fragment(), View.OnClickListener {
    private lateinit var binding: FragmentBuyDataBinding
    private var paidServerUtil = App.instance!!.paidServerUtil!!
    private var userViewModel: UserViewModel? = null
    private var isAttached = false
    private var paidServerActivity: PaidServerActivity? = null
    private var purchaseViewModel: PurchaseViewModel? = null

    companion object {
        const val TAG = "BuyDataFragment"
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        isAttached = true
    }

    override fun onDetach() {
        super.onDetach()
        isAttached = false
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentBuyDataBinding.inflate(layoutInflater)
        binding.txtDataSize.text = OpenVPNService.humanReadableByteCount(
            paidServerUtil.getUserInfo()!!.dataSize!!, false, resources
        )
        binding.btnBack.setOnClickListener(this)
        binding.incLoading.lnLoading.visibility = View.GONE
        binding.rcvSkuDetails.visibility = View.GONE
        binding.txtPurchaseUnavailable.visibility = View.VISIBLE
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViewModel()
    }

    private fun bindViewModel() {
        paidServerActivity = activity as PaidServerActivity
        userViewModel = paidServerActivity!!.userViewModel
        userViewModel?.userInfo?.observe(viewLifecycleOwner) { userInfo ->
            if (isAttached) {
                binding.txtDataSize.text = OpenVPNService.humanReadableByteCount(
                    userInfo!!.dataSize!!,
                    false,
                    resources
                )
            }
        }
        purchaseViewModel = ViewModelProvider(this)[PurchaseViewModel::class.java]
        purchaseViewModel?.isLoggedIn?.observe(viewLifecycleOwner) { isLoggedIn ->
            if (!isLoggedIn) {
                // Go to login screen if user login status is changed
                val intentLogin = Intent(paidServerActivity, LoginActivity::class.java)
                startActivity(intentLogin)
                paidServerActivity!!.finish()
            }
        }
    }

    override fun onClick(v: View?) {
        when (v) {
            binding.btnBack -> findNavController().popBackStack()
        }
    }
}
