package com.bushers.tvsdt.wireless

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.ListFragment
import com.microsoft.appcenter.analytics.Analytics
import com.microsoft.appcenter.crashes.WrapperSdkExceptionManager
import com.microsoft.appcenter.crashes.ingestion.models.ErrorAttachmentLog
import java.io.ByteArrayOutputStream
import java.util.*


class DevicesFragment : ListFragment() {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val listItems = ArrayList<BluetoothDevice>()
    private var listAdapter: ArrayAdapter<BluetoothDevice>? = null
    private val nullParent: ViewGroup? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        if (activity!!.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) bluetoothAdapter =
            BluetoothAdapter.getDefaultAdapter()
        listAdapter = object : ArrayAdapter<BluetoothDevice>(activity!!, 0, listItems) {
            override fun getView(position: Int, view: View?, parent: ViewGroup): View {
                var viewVar = view
                val device = listItems[position]
                if (viewVar == null) viewVar = activity!!.layoutInflater.inflate(
                    R.layout.device_list_item,
                    parent,
                    false
                )
                val text1 = viewVar!!.findViewById<TextView>(R.id.text1)
                val text2 = viewVar.findViewById<TextView>(R.id.text2)
                text1.text = device.name
                text2.text = device.address
                return viewVar
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        setListAdapter(null)
        val header = activity!!.layoutInflater.inflate(
            R.layout.device_list_header,
            nullParent,
            false
        )
        listView.addHeaderView(header, null, false)
        setEmptyText("initializing...")
        (listView.emptyView as TextView).textSize = 18f
        setListAdapter(listAdapter)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_devices, menu)
        if (bluetoothAdapter == null) menu.findItem(R.id.bt_settings).isEnabled = false
    }

    override fun onResume() {
        super.onResume()
        if (bluetoothAdapter == null) setEmptyText("<bluetooth not supported>") else if (!bluetoothAdapter!!.isEnabled) setEmptyText(
            "<bluetooth is disabled>"
        ) else setEmptyText("<no bluetooth devices found>")
        refresh()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        return if (id == R.id.bt_settings) {
            val intent = Intent()
            intent.action = Settings.ACTION_BLUETOOTH_SETTINGS
            startActivity(intent)
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    private fun refresh() {
        listItems.clear()
        if (bluetoothAdapter != null) {
            for (device in bluetoothAdapter!!.bondedDevices) if (device.type != BluetoothDevice.DEVICE_TYPE_LE) listItems.add(
                device
            )
        }
        listItems.sortWith { a: BluetoothDevice, b: BluetoothDevice -> compareTo(a, b) }
        listAdapter!!.notifyDataSetChanged()
    }

    override fun onListItemClick(l: ListView, v: View, position: Int, id: Long) {
        Analytics.trackEvent("Click Tool")
        val device = listItems[position - 1]
        val args = Bundle()
        args.putString("device", device.address)
        val properties: MutableMap<String, String> = HashMap()
        properties["Device Address"] = device.address
        Analytics.trackEvent("Tools", properties)
        val fragment: Fragment = TerminalFragment()
        fragment.arguments = args
        fragmentManager!!.beginTransaction().replace(R.id.fragment, fragment, "terminal")
            .addToBackStack(
                null
            ).commit()
    }

    companion object {
        /**
         * sort by name, then address. sort named devices first
         */
        fun compareTo(a: BluetoothDevice, b: BluetoothDevice): Int {
            val aValid = a.name != null && a.name.isNotEmpty()
            val bValid = b.name != null && b.name.isNotEmpty()
            if (aValid && bValid) {
                val ret = a.name.compareTo(b.name)
                return if (ret != 0) ret else a.address.compareTo(b.address)
            }
            if (aValid) return -1
            return if (bValid) +1 else a.address.compareTo(b.address)
        }
    }
}