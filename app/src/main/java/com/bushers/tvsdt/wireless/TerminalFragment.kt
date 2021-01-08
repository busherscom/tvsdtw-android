package com.bushers.tvsdt.wireless

import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.bushers.tvsdt.wireless.SerialService.SerialBinder
import com.bushers.tvsdt.wireless.TextUtil.HexWatcher
import com.bushers.tvsdt.wireless.TextUtil.toCaretString
import com.bushers.tvsdt.wireless.TextUtil.toHexString
import com.microsoft.appcenter.crashes.Crashes

@Suppress("TooManyFunctions")
class TerminalFragment : Fragment(), ServiceConnection, SerialListener {
    private enum class Connected {
        False, Pending, True
    }

    private var deviceAddress: String? = null
    private var service: SerialService? = null
    private var receiveText: TextView? = null
    private var sendText: TextView? = null
    private var hexWatcher: HexWatcher? = null
    private var connected = Connected.False
    private var initialStart = true
    private var hexEnabled = false
    private var pendingNewline = false
    private var newline: String? = TextUtil.newline_crlf

    /*
     * Lifecycle
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        retainInstance = true
        deviceAddress = arguments!!.getString("device")
    }

    override fun onDestroy() {
        if (connected != Connected.False) disconnect()
        activity!!.stopService(Intent(activity, SerialService::class.java))
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (service != null) service!!.attach(this) else activity!!.startService(
            Intent(
                activity,
                SerialService::class.java
            )
        ) // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    override fun onStop() {
        if (service != null && !activity!!.isChangingConfigurations) service!!.detach()
        super.onStop()
    }


    override fun onAttach(context: Context) {
        super.onAttach(context)
        activity!!.bindService(
            Intent(activity, SerialService::class.java),
            this,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onDetach() {
        try {
            activity!!.unbindService(this)
        } catch (e: IllegalArgumentException) {
            Crashes.trackError(e)
        }
        super.onDetach()
    }

    override fun onResume() {
        super.onResume()
        if (initialStart && service != null) {
            initialStart = false
            activity!!.runOnUiThread { connect() }
        }
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = (binder as SerialBinder).service
        service!!.attach(this)
        if (initialStart && isResumed) {
            initialStart = false
            activity!!.runOnUiThread { connect() }
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
    }

    /*
     * UI
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_terminal, container, false)
        receiveText =
            view.findViewById(R.id.receive_text) // TextView performance decreases with number of spans
        receiveText?.setTextColor(
            ContextCompat.getColor(
                view.context,
                R.color.colorRecieveText
            )
        ) // set as default color to reduce number of spans
        receiveText?.movementMethod = ScrollingMovementMethod.getInstance()
        sendText = view.findViewById(R.id.send_text)
        hexWatcher = HexWatcher(sendText!!)
        hexWatcher!!.enable(hexEnabled)
        sendText?.addTextChangedListener(hexWatcher)
        sendText?.hint = if (hexEnabled) "HEX mode" else ""
        val sendBtn = view.findViewById<View>(R.id.send_btn)
        sendBtn.setOnClickListener { send(sendText?.text.toString()) }
        return view
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_terminal, menu)
        menu.findItem(R.id.hex).isChecked = hexEnabled
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.clear -> {
                receiveText!!.text = ""
                true
            }
            R.id.newline -> {
                val newlineNames = resources.getStringArray(R.array.newline_names)
                val newlineValues = resources.getStringArray(R.array.newline_values)
                val pos = newlineValues.indexOf(newline)
                val builder = AlertDialog.Builder(activity)
                builder.setTitle("Newline")
                builder.setSingleChoiceItems(
                    newlineNames,
                    pos
                ) { dialog: DialogInterface, item1: Int ->
                    newline = newlineValues[item1]
                    dialog.dismiss()
                }
                builder.create().show()
                true
            }
            R.id.hex -> {
                hexEnabled = !hexEnabled
                sendText!!.text = ""
                hexWatcher!!.enable(hexEnabled)
                sendText!!.hint = if (hexEnabled) "HEX mode" else ""
                item.isChecked = hexEnabled
                true
            }
            else -> {
                super.onOptionsItemSelected(item)
            }
        }
    }

    /*
     * Serial + UI
     */
    private fun connect() {
        try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
            status("connecting...")
            connected = Connected.Pending
            val socket = SerialSocket(activity!!.applicationContext, device)
            service!!.connect(socket)
        } catch (e: Exception) {
            Crashes.trackError(e)
            onSerialConnectError(e)
        }
    }

    private fun disconnect() {
        connected = Connected.False
        service!!.disconnect()
    }

    private fun send(str: String) {
        if (connected != Connected.True) {
            Toast.makeText(activity, "not connected", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val msg: String
            val data: ByteArray?
            val breaking = newline!!.toByteArray()
            if (hexEnabled) {
                val sb = StringBuilder()
                toHexString(sb, TextUtil.fromHexString(str))
                toHexString(sb, breaking)
                msg = sb.toString()
                data = TextUtil.fromHexString(msg)
            } else {
                msg = str
                data = str.plus(newline!!).toByteArray()
            }

            val spn = SpannableStringBuilder(
                """
    $msg
    
    """.trimIndent()
            )
            spn.setSpan(
                ForegroundColorSpan(
                    ContextCompat.getColor(
                        activity?.applicationContext!!,
                        R.color.colorSendText
                    )
                ), 0, spn.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            receiveText!!.append(spn)
            service!!.write(data)
        } catch (e: Exception) {
            Crashes.trackError(e)
            onSerialIoError(e)
        }
    }

    private fun receive(data: ByteArray?) = if (hexEnabled)
        receiveText!!.append(data?.let { toHexString(it) } + '\n')
    else {
        var msg = String(data!!)
        if (newline == TextUtil.newline_crlf) {
            if (msg.isNotEmpty()) {
                // don't show CR as ^M if directly before LF
                msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf).also { msg = it }
                // special handling if CR and LF come in separate fragments
                if (pendingNewline)
                    if (msg[0] == '\n') {
                        val edt = receiveText!!.editableText
                        if ((edt == null || edt.length <= 1).not()
                        ) {
                            edt.replace(edt.length - 2, edt.length, "")
                        }
                    }
                pendingNewline = msg[msg.length - 1] == '\r'
            }
        }
        receiveText!!.append(toCaretString(msg, newline!!.isNotEmpty()))
    }

    private fun status(str: String) {
        val spn = SpannableStringBuilder(
            """
    $str
    
    """.trimIndent()
        )
        spn.setSpan(
            ForegroundColorSpan(
                ContextCompat.getColor(
                    activity?.applicationContext!!,
                    R.color.colorStatusText
                )
            ), 0, spn.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        receiveText!!.append(spn)
    }

    /*
     * SerialListener
     */
    override fun onSerialConnect() {
        status("connected")
        connected = Connected.True
    }

    override fun onSerialConnectError(e: Exception) {
        Crashes.trackError(e)
        status("connection failed: " + e.message)
        disconnect()
    }

    override fun onSerialRead(data: ByteArray?) {
        receive(data)
    }

    override fun onSerialIoError(e: Exception) {
        Crashes.trackError(e)
        status("connection lost: " + e.message)
        disconnect()
    }
}