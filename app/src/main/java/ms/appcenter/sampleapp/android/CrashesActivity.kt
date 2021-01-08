package ms.appcenter.sampleapp.android

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment

class CrashesActivity : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        val rootView = inflater.inflate(
                R.layout.crashes_root, container, false) as ViewGroup
        val crashButton = rootView.findViewById<Button>(R.id.crashButton)
        crashButton.setOnClickListener {
            val crashDialog: DialogFragment = CrashDialog()
            crashDialog.show(fragmentManager!!, "crashDialog")
        }
        return rootView
    }

    class CrashDialog : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val builder = AlertDialog.Builder(activity!!)
            builder.setMessage("A crash report will be sent when you reopen the app.")
                    .setPositiveButton("Crash app") { _: DialogInterface?, _: Int -> throw RuntimeException("crashing") }.setNegativeButton("Cancel") { _: DialogInterface?, _: Int -> }
            return builder.create()
        }
    }
}