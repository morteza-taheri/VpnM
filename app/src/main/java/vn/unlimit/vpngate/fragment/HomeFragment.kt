package vn.unlimit.vpngate.fragment

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout.OnRefreshListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.unlimit.vpngate.App.Companion.instance
import vn.unlimit.vpngate.compat.LocalAnalytics as FirebaseAnalytics
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.activities.DetailActivity
import vn.unlimit.vpngate.activities.MainActivity
import vn.unlimit.vpngate.adapter.OnItemClickListener
import vn.unlimit.vpngate.adapter.OnItemLongClickListener
import vn.unlimit.vpngate.adapter.OnScrollListener
import vn.unlimit.vpngate.adapter.VPNGateListAdapter
import vn.unlimit.vpngate.databinding.FragmentHomeBinding
import vn.unlimit.vpngate.dialog.CopyBottomSheetDialog
import vn.unlimit.vpngate.dialog.CopyBottomSheetDialog.Companion.newInstance
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.models.VPNGateConnectionList
import vn.unlimit.vpngate.provider.BaseProvider
import vn.unlimit.vpngate.utils.DataUtil
import vn.unlimit.vpngate.utils.BookmarkManager
import vn.unlimit.vpngate.utils.JalaliDateUtil
import vn.unlimit.vpngate.viewmodels.ConnectionListViewModel

/**
 * Created by hoangnd on 1/30/2018.
 */
class HomeFragment : Fragment(), OnRefreshListener, View.OnClickListener, OnItemClickListener,
    OnItemLongClickListener, OnScrollListener, VPNGateListAdapter.OnBookmarkToggleListener,
    VPNGateListAdapter.OnPingTestListener {
    companion object {
        private const val TAG = "HOME_FREE"
    }

    private var mContext: Context? = null
    private var vpnGateListAdapter: VPNGateListAdapter? = null
    private var connectionListViewModel: ConnectionListViewModel? = null
    private var dataUtil: DataUtil? = null
    private var isSearching = false
    private var mKeyword = ""
    private var handler: Handler? = null
    private var mActivity: MainActivity? = null

    private lateinit var binding: FragmentHomeBinding

    // Interstitial and native ads (AdMob) have been removed from this build - no Google
    // service dependencies.

    private fun startDetailAct(vpnGateConnection: VPNGateConnection?) {
        try {
            val intent = Intent(context, DetailActivity::class.java)
            intent.putExtra(BaseProvider.PASS_DETAIL_VPN_CONNECTION, vpnGateConnection)
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, e.message, e)
        }
    }

    private fun checkAndShowAd(vpnGateConnection: VPNGateConnection?): Boolean {
        // Ads removed from this build - always navigate straight to detail.
        return false
    }

    override fun onAttach(context: Context) {
        mContext = context
        super.onAttach(context)
    }

    override fun onCreate(savedBundle: Bundle?) {
        super.onCreate(savedBundle)
        try {
            dataUtil = instance!!.dataUtil
            vpnGateListAdapter = VPNGateListAdapter(mContext!!)
            handler = Handler(Looper.getMainLooper())
            connectionListViewModel = (this.activity as MainActivity).connectionListViewModel
            connectionListViewModel!!.isLoading.observe(this) { isLoading: Boolean? ->
                if (!isLoading!! && connectionListViewModel!!.vpnGateConnectionList.value != null) {
                    onAPISuccess(connectionListViewModel!!.vpnGateConnectionList.value)
                }
            }
            connectionListViewModel!!.isError.observe(this) { isError: Boolean ->
                if (isError) {
                    onError("")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, e.message, e)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        mActivity = activity as MainActivity?
        binding = FragmentHomeBinding.inflate(layoutInflater)
        binding.lnSwipeRefresh.setColorSchemeResources(R.color.colorAccent)
        binding.lnSwipeRefresh.setOnRefreshListener(this)
        binding.rcvConnection.setAdapter(vpnGateListAdapter)
        binding.rcvConnection.setLayoutManager(LinearLayoutManager(mContext))
        vpnGateListAdapter!!.setOnItemClickListener(this)
        vpnGateListAdapter!!.setOnItemLongClickListener(this)
        vpnGateListAdapter!!.setOnScrollListener(this)
        vpnGateListAdapter!!.setOnBookmarkToggleListener(this)
        vpnGateListAdapter!!.setOnPingTestListener(this)
        binding.btnToTop.setOnClickListener(this)
        updateLastUpdatedLabel()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        lifecycleScope.launch(Dispatchers.IO) {
            if ("" != mActivity!!.sortProperty) {
                mActivity!!.vpnGateConnectionList?.sort(
                    mActivity!!.sortProperty,
                    mActivity!!.sortType
                )
            } else {
                mActivity!!.vpnGateConnectionList?.advancedFilter()
            }
            withContext(Dispatchers.Main) {
                vpnGateListAdapter!!.initialize(mActivity!!.vpnGateConnectionList)
            }
        }
    }

    fun advanceFilter(filter: VPNGateConnectionList.Filter?) {
        lifecycleScope.launch(Dispatchers.IO) {
            var vpnGateConnectionList = mActivity!!.vpnGateConnectionList!!.advancedFilter(filter)
            if (isSearching && "" != mKeyword) {
                vpnGateConnectionList = vpnGateConnectionList.filter(mKeyword)
            }
            withContext(Dispatchers.Main) {
                if (vpnGateConnectionList.size() == 0) {
                    binding.txtEmpty.setText(R.string.empty_filter_result)
                    binding.txtEmpty.visibility = View.VISIBLE
                } else {
                    binding.txtEmpty.visibility = View.GONE
                }
                vpnGateListAdapter!!.initialize(vpnGateConnectionList)
            }
        }
    }

    /**
     * Search by keyword
     *
     * @param keyword search keyword
     */
    fun filter(keyword: String) {
        stopTask()
        if (mActivity!!.vpnGateConnectionList == null) {
            return
        }
        if ("" != keyword) {
            mKeyword = keyword
            isSearching = true
            lifecycleScope.launch(Dispatchers.IO) {
                val filterResult = mActivity!!.vpnGateConnectionList!!.filter(keyword)
                withContext(Dispatchers.Main) {
                    if (filterResult.size() == 0) {
                        binding.txtEmpty.text = getString(R.string.empty_search_result, keyword)
                        binding.txtEmpty.visibility = View.VISIBLE
                        binding.rcvConnection.visibility = View.GONE
                    } else {
                        binding.txtEmpty.visibility = View.GONE
                        binding.rcvConnection.visibility = View.VISIBLE
                    }
                    vpnGateListAdapter!!.initialize(filterResult)
                }
            }
        } else {
            binding.rcvConnection.visibility = View.VISIBLE
            binding.txtEmpty.visibility = View.GONE
            lifecycleScope.launch(Dispatchers.IO) {
                val vpnGateConnectionList = mActivity!!.vpnGateConnectionList!!.advancedFilter()
                withContext(Dispatchers.Main) {
                    vpnGateListAdapter!!.initialize(vpnGateConnectionList)
                }
            }
        }
    }

    fun sort(property: String?, type: Int) {
        try {
            lifecycleScope.launch(Dispatchers.IO) {
                if (mActivity!!.vpnGateConnectionList != null) {
                    mActivity!!.vpnGateConnectionList!!.sort(property, type)
                    if (isSearching) {
                        val filterResult = mActivity!!.vpnGateConnectionList!!.filter(mKeyword)
                        withContext(Dispatchers.Main) {
                            vpnGateListAdapter!!.initialize(filterResult)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            vpnGateListAdapter!!.initialize(mActivity!!.vpnGateConnectionList)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, e.message, e)
        }
    }

    private fun stopTask() {
        binding.lnSwipeRefresh.isEnabled = false
        binding.lnSwipeRefresh.isRefreshing = false
    }

    /**
     * Close search
     */
    fun closeSearch() {
        isSearching = false
        binding.txtEmpty.visibility = View.GONE
        binding.rcvConnection.visibility = View.VISIBLE
        if (mActivity!!.vpnGateConnectionList != null) {
            mActivity!!.vpnGateConnectionList!!.mKeyword = null
            lifecycleScope.launch(Dispatchers.IO) {
                val vpnGateConnectionList = mActivity!!.vpnGateConnectionList!!.advancedFilter()
                withContext(Dispatchers.Main) {
                    vpnGateListAdapter!!.initialize(vpnGateConnectionList)
                }
            }
        } else {
            vpnGateListAdapter!!.initialize(mActivity!!.vpnGateConnectionList)
        }
        handler!!.postDelayed({
            binding.lnSwipeRefresh.isEnabled = true
            binding.lnSwipeRefresh.isRefreshing = false
        }, 300)
    }

    override fun onClick(view: View) {
        if (view == binding.btnToTop) {
            binding.rcvConnection.smoothScrollToPosition(0)
        }
    }

    override fun onItemClick(o: Any?, position: Int) {
        val params = Bundle()
        params.putString("ip", (o as VPNGateConnection?)!!.ip)
        params.putString("hostname", o!!.calculateHostName)
        params.putString("country", o.countryLong)
        FirebaseAnalytics.getInstance(mContext!!).logEvent("Select_Server", params)
        if (!checkAndShowAd(o)) {
            startDetailAct(o)
        }
    }

    override fun onItemLongClick(o: Any?, position: Int) {
        try {
            val params = Bundle()
            params.putString("ip", (o as VPNGateConnection?)!!.ip)
            params.putString("hostname", o!!.calculateHostName)
            params.putString("country", o.countryLong)
            FirebaseAnalytics.getInstance(mContext!!).logEvent("Long_Click_Server", params)
            val dialog = newInstance(o)
            if (!mActivity!!.isFinishing && !mActivity!!.isDestroyed) {
                dialog.show(parentFragmentManager, CopyBottomSheetDialog::class.java.name)
            } else if (!mActivity!!.isFinishing) {
                dialog.show(parentFragmentManager, CopyBottomSheetDialog::class.java.name)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, e.message, e)
        }
    }

    override fun onScrollUp() {
        binding.btnToTop.visibility = View.VISIBLE
    }

    override fun onScrollDown() {
        binding.btnToTop.visibility = View.GONE
    }

    override fun onRefresh() {
        connectionListViewModel?.getAPIData()
    }

    private fun onAPISuccess(o: Any?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val vpnGateConnectionList = o as VPNGateConnectionList?
            if ("" != mActivity!!.sortProperty) {
                vpnGateConnectionList?.sort(
                    mActivity!!.sortProperty,
                    mActivity!!.sortType
                )
            }
            val liveHostNames = HashSet<String>()
            for (i in 0 until (vpnGateConnectionList?.size() ?: 0)) {
                vpnGateConnectionList?.get(i)?.hostName?.let { liveHostNames.add(it) }
            }
            val bookmarkedHostNames = BookmarkManager.getAllHostNames()
            val offlineBookmarks = BookmarkManager.getOfflineBookmarks(liveHostNames)
            // Bookmarked-but-missing servers are shown first, as read-only "offline" rows.
            val merged = VPNGateConnectionList()
            offlineBookmarks.forEach { merged.add(it) }
            vpnGateConnectionList?.let { merged.addAll(it) }
            val offlineHostNames = offlineBookmarks.mapNotNull { it.hostName }.toSet()
            withContext(Dispatchers.Main) {
                mActivity!!.vpnGateConnectionList = merged
                binding.txtEmpty.visibility = View.GONE
                binding.rcvConnection.visibility = View.VISIBLE
                vpnGateListAdapter!!.initialize(merged)
                vpnGateListAdapter!!.setBookmarkState(bookmarkedHostNames, offlineHostNames)
                binding.lnSwipeRefresh.isRefreshing = false
                updateLastUpdatedLabel()
            }
        }
    }

    private fun updateLastUpdatedLabel() {
        try {
            val date = dataUtil?.lastServerListUpdateAt
            binding.txtLastUpdated.text = if (date == null) {
                getString(R.string.server_list_never_updated)
            } else {
                getString(
                    R.string.server_list_updated_at,
                    JalaliDateUtil.format(date, dataUtil!!.isPersianLanguage())
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateLastUpdatedLabel error", e)
        }
    }

    fun onError(error: String?) {
        try {
            binding.lnSwipeRefresh.isRefreshing = false
            val mainActivity = activity as MainActivity?
            mainActivity?.onError(error)
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, e.message, e)
        }
    }

    override fun onBookmarkToggle(item: VPNGateConnection, position: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val nowBookmarked = BookmarkManager.toggleBookmark(item)
                val allBookmarked = BookmarkManager.getAllHostNames()
                withContext(Dispatchers.Main) {
                    vpnGateListAdapter?.setBookmarkState(allBookmarked, emptySet())
                    Toast.makeText(
                        mContext,
                        getString(if (nowBookmarked) R.string.bookmark_added else R.string.bookmark_removed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "onBookmarkToggle error", e)
            }
        }
    }

    override fun onPingTest(item: VPNGateConnection, position: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val hostName = item.hostName ?: return@launch
            val displayText = try {
                val port = when {
                    item.tcpPort != 0 -> item.tcpPort
                    item.udpPort != 0 -> item.udpPort
                    item.seTcpPort != 0 -> item.seTcpPort
                    else -> 443
                }
                val ip = item.ip
                if (ip.isNullOrBlank()) {
                    getString(R.string.ping_test_failed)
                } else {
                    val start = System.currentTimeMillis()
                    java.net.Socket().use { socket ->
                        socket.connect(java.net.InetSocketAddress(ip, port), 4000)
                    }
                    val elapsed = System.currentTimeMillis() - start
                    "${elapsed}ms"
                }
            } catch (e: Exception) {
                getString(R.string.ping_test_failed)
            }
            withContext(Dispatchers.Main) {
                vpnGateListAdapter?.updatePingResult(hostName, displayText)
            }
        }
    }
}
