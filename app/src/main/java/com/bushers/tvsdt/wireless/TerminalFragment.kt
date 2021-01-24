package com.bushers.tvsdt.wireless

import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.*
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.bushers.tvsdt.wireless.SerialService.SerialBinder
import com.bushers.tvsdt.wireless.TextUtil.HexWatcher
import com.bushers.tvsdt.wireless.TextUtil.toCaretString
import com.bushers.tvsdt.wireless.TextUtil.toHexString
import com.google.android.material.tabs.TabLayout
import com.microsoft.appcenter.analytics.Analytics
import com.microsoft.appcenter.crashes.Crashes
import java.io.IOException


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
    private var esc = "\u001B"

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
        val tabLayout = view.findViewById(R.id.tabs) as TabLayout
        val mstartButtons = view.findViewById(R.id.mstart_buttons) as HorizontalScrollView
        val realtekButtons = view.findViewById(R.id.realtek_buttons) as HorizontalScrollView
        val nuggetButtons = view.findViewById(R.id.nugget_buttons) as HorizontalScrollView
        val panasonicButtons = view.findViewById(R.id.panasonic_buttons) as HorizontalScrollView
        val hisenseButtons = view.findViewById(R.id.hisense_buttons) as HorizontalScrollView
        mstartButtons.visibility = View.VISIBLE
        realtekButtons.visibility = View.GONE
        nuggetButtons.visibility = View.GONE
        panasonicButtons.visibility = View.GONE
        hisenseButtons.visibility = View.GONE
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val tabText = tab.text
                Analytics.trackEvent("OnClick Tab $tab")
                when (tabText) {
                    "MStar" -> mstartButtons.visibility = View.VISIBLE
                    "Realtek" -> realtekButtons.visibility = View.VISIBLE
                    "Nugget" -> nuggetButtons.visibility = View.VISIBLE
                    "Panasonic" -> panasonicButtons.visibility = View.VISIBLE
                    "Hisense-MT5882" -> hisenseButtons.visibility = View.VISIBLE
                }
            }

            override fun onTabUnselected(p0: TabLayout.Tab?) {
                if (p0 != null) {
                    when (p0.text) {
                        "MStar" -> mstartButtons.visibility = View.GONE
                        "Realtek" -> realtekButtons.visibility = View.GONE
                        "Nugget" -> nuggetButtons.visibility = View.GONE
                        "Panasonic" -> panasonicButtons.visibility = View.GONE
                        "Hisense-MT5882" -> hisenseButtons.visibility = View.GONE
                    }
                }
            }

            override fun onTabReselected(p0: TabLayout.Tab?) {
            }
        })



        receiveText =
            view.findViewById(R.id.receive_text) // TextView performance decreases with number of spans
        receiveText?.setTextColor(
            ContextCompat.getColor(
                view.context,
                R.color.colorRecieveText
            )
        ) // set as default color to reduce number of spans
        receiveText?.movementMethod = ScrollingMovementMethod.getInstance()
        //sendText = view.findViewById(R.id.send_text)
        //hexWatcher = HexWatcher(sendText!!)
        //hexWatcher!!.enable(hexEnabled)
        //sendText?.addTextChangedListener(hexWatcher)
        //sendText?.hint = if (hexEnabled) "HEX mode" else ""
        // val sendBtn = view.findViewById<View>(R.id.send_btn)
        // sendBtn.setOnClick CMD Listener { send(sendText?.text.toString()) }


        val mstarKeyAccess = view.findViewById<View>(R.id.mstar_key_access)
        val mstarForceBacklight = view.findViewById<View>(R.id.mstar_force_backlight)
        val mstarBootlogo = view.findViewById<View>(R.id.mstar_bootlogo)
        val mstarStopLogo = view.findViewById<View>(R.id.mstar_stop_logo)
        val mstarDeleteLogo = view.findViewById<View>(R.id.mstar_delete_logo)
        val mstarBackupToUsb = view.findViewById<View>(R.id.mstar_backup_to_usb)
        val mstarRestoreBackup = view.findViewById<View>(R.id.mstar_restore_backup_)
        val mstarDiagnoseSound = view.findViewById<View>(R.id.mstar_diagnose_sound)
        val mstarFirmwareUpgrade = view.findViewById<View>(R.id.mstar_firmware_upgrade)
        val mstarMemoryInformation = view.findViewById<View>(R.id.mstar_memory_information)
        val mstarPanelInverter1 = view.findViewById<View>(R.id.mstar_panel_inverter_1)
        val mstarPanelInverter2 = view.findViewById<View>(R.id.mstar_panel_inverter_2)
        val mstarReset = view.findViewById<View>(R.id.mstar_reset)


        val realtekEcho = view.findViewById<View>(R.id.realtek_echo)
        val realtekKeyAccessRealtek = view.findViewById<View>(R.id.realtek_key_access_realtek)
        val realtekEnableUartart = view.findViewById<View>(R.id.realtek_enable_uartart)
        val realtekAssignPanelanelAndMirror =
            view.findViewById<View>(R.id.realtek_assign_panelanel_and_mirror)
        val realtekEmmcInfoo = view.findViewById<View>(R.id.realtek_emmc_infoo)
        val realtekEmmcStatustus = view.findViewById<View>(R.id.realtek_emmc_statustus)
        val realtekReportOffEmmc = view.findViewById<View>(R.id.realtek_report_off_emmc)
        val realtekTestAllDevices = view.findViewById<View>(R.id.realtek_test_all_devices)
        val realtekFirmwareUpgradeUpgrade =
            view.findViewById<View>(R.id.realtek_firmware_upgrade_upgrade)
        val realtekTestUsbPorts = view.findViewById<View>(R.id.realtek_test_usb_ports)
        val realtekDisableWachdogwachdog =
            view.findViewById<View>(R.id.realtek_disable_wachdogwachdog)
        val realtekAssignRemoteemote = view.findViewById<View>(R.id.realtek_assign_remoteemote)
        val realtekResetMainin = view.findViewById<View>(R.id.realtek_reset_mainin)


        val hisenseKeyAccess1 = view.findViewById<View>(R.id.hisense_key_access_1)
        val hisenseUsbTest = view.findViewById<View>(R.id.hisense_usb_test)
        val hisenseFirmwareUpgrade = view.findViewById<View>(R.id.hisense_firmware_upgrade)
        val hisenseResetMainBoard = view.findViewById<View>(R.id.hisense_reset_main_board)
        val hisenseTestDeMainBoard = view.findViewById<View>(R.id.hisense_test_de_main_board)
        val hisenseInformationEmmc = view.findViewById<View>(R.id.hisense_information_emmc)
        val hisenseUpgradeLinux = view.findViewById<View>(R.id.hisense_upgrade_linux_)
        val hisenseVersionMain = view.findViewById<View>(R.id.hisense_version_main)
        val hisenseMemoryAsiggn = view.findViewById<View>(R.id.hisense_memory_asiggn_)
        val hisensePartitionInfo = view.findViewById<View>(R.id.hisense_partition_info)
        val hisenseKeyAccess2 = view.findViewById<View>(R.id.hisense_key_access_2)
        val hisenseFirmwareUpgrade2 = view.findViewById<View>(R.id.hisense_firmware_upgrade_2)
        val hisenseReinicio = view.findViewById<View>(R.id.hisense_reinicio)
        val hisenseShowLogo = view.findViewById<View>(R.id.hisense_show_logo)
        val hisenseIdentificationChipset =
            view.findViewById<View>(R.id.hisense_identification_chipset)
        val hisenseTestMemory = view.findViewById<View>(R.id.hisense_test_memory)
        val hisenseEmmcReference = view.findViewById<View>(R.id.hisense_emmc_reference)
        val hisenseInfoEmmc = view.findViewById<View>(R.id.hisense_info_emmc)
        val hisenseDeviceInfo = view.findViewById<View>(R.id.hisense_device_info)


        val nuggetKeyAccess = view.findViewById<View>(R.id.nugget_key_access)
        val nuggetForceBacklight = view.findViewById<View>(R.id.nugget_force_backlight)
        val nuggetShowlogo = view.findViewById<View>(R.id.nugget_showlogo)
        val nuggetStopLogo = view.findViewById<View>(R.id.nugget_stop_logo)
        val nuggetBackupTo = view.findViewById<View>(R.id.nugget_backup_to)
        val nuggetRestoreBackup = view.findViewById<View>(R.id.nugget_restore_backup)
        val nuggetFirmwareUpgrade1 = view.findViewById<View>(R.id.nugget_firmware_upgrade_1)
        val nuggetFirmwareUpgrade2 = view.findViewById<View>(R.id.nugget_firmware_upgrade_2)
        val nuggetResetMain = view.findViewById<View>(R.id.nugget_reset_main)
        val nuggetReset2Mstar = view.findViewById<View>(R.id.nugget_reset_2_mstar)
        val nuggetReset3Securebootcmd = view.findViewById<View>(R.id.nugget_reset_3_securebootcmd)
        val nuggetTestOf = view.findViewById<View>(R.id.nugget_test_of)
        val nuggetViewAll = view.findViewById<View>(R.id.nugget_view_all)
        val nuggetCopySpi = view.findViewById<View>(R.id.nugget_copy_spi)
        val nuggetTestUsb = view.findViewById<View>(R.id.nugget_test_usb)


        val panasonicKeyAccess = view.findViewById<View>(R.id.panasonic_key_access)
        val panasonicForceBacklight = view.findViewById<View>(R.id.panasonic_force_backlight)
        val panasonicDiagnoseSound = view.findViewById<View>(R.id.panasonic_diagnose_sound)
        val panasonicBootlogo = view.findViewById<View>(R.id.panasonic_bootlogo)
        val panasonicDeleteLogo = view.findViewById<View>(R.id.panasonic_delete_logo)
        val panasonicBackupToUsb = view.findViewById<View>(R.id.panasonic_backup_to_usb)
        val panasonicRestoreBackup = view.findViewById<View>(R.id.panasonic_restore_backup_)
        val panasonicCopySpi = view.findViewById<View>(R.id.panasonic_copy_spi)
        val panasonicRestoreSpi = view.findViewById<View>(R.id.panasonic_restore_spi)
        val panasonicMemoryEmmcInformation =
            view.findViewById<View>(R.id.panasonic_memory_emmc_information)
        val panasonicMemoryTest = view.findViewById<View>(R.id.panasonic_memory_test)
        val panasonicVerifyStatusAndFirmware =
            view.findViewById<View>(R.id.panasonic_verify_status_and_firmware)
        val panasonicRecordMac = view.findViewById<View>(R.id.panasonic_record_mac)
        val panasonicTestPanelAndSound =
            view.findViewById<View>(R.id.panasonic_test_panel_and_sound)
        val panasonicTestVideo = view.findViewById<View>(R.id.panasonic_test_video)
        val panasonicAssignPanel4K2K = view.findViewById<View>(R.id.panasonic_assign_panel_4k_2k)
        val panasonicAssignPanelFullHd1 =
            view.findViewById<View>(R.id.panasonic_assign_panel_full_hd_1)
        val panasonicAssignPanelFullHd2 =
            view.findViewById<View>(R.id.panasonic_assign_panel_full_hd_2)
        val panasonicFirmwareUpgrade = view.findViewById<View>(R.id.panasonic_firmware_upgrade)
        val panasonicTestUsbPorts = view.findViewById<View>(R.id.panasonic_test_usb_ports)
        val panasonicMaininfo = view.findViewById<View>(R.id.panasonic_maininfo)
        val panasonicReset1 = view.findViewById<View>(R.id.panasonic_reset_1)
        val panasonicReset2 = view.findViewById<View>(R.id.panasonic_reset_2)

        mstarKeyAccess.setOnClickListener {
            try {
                for (i in 5000 downTo 0 step 100) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        sendCommand(newline!!)
                    }, i.toLong())
                }
            } catch (e: Exception) {
                Crashes.trackError(e)
            }
            Analytics.trackEvent("OnClick CMD KEY ACCESS")
        }
        mstarForceBacklight.setOnClickListener {
            sendCommand("panel_init")
            Analytics.trackEvent("OnClick CMD FORCE BACKLIGHT")
        }
        mstarBootlogo.setOnClickListener {
            sendCommand("bootlogo")
            Analytics.trackEvent("OnClick CMD BOOTLOGO")
        }
        mstarStopLogo.setOnClickListener {
            sendCommand("destroy_logo ")
            Analytics.trackEvent("OnClick CMD STOP LOGO")
        }
        mstarDeleteLogo.setOnClickListener {
            sendCommand("destroy_logo")
            Analytics.trackEvent("OnClick CMD DELETE LOGO")
        }
        mstarBackupToUsb.setOnClickListener {
            sendCommand("usbstart  0")
            Analytics.trackEvent("OnClick CMD BACKUP TO USB")
        }
        mstarRestoreBackup.setOnClickListener {
            sendCommand("emmcbootbin 0 boot1_2")
            Analytics.trackEvent("OnClick CMD RESTORE BACKUP ")
        }
        mstarDiagnoseSound.setOnClickListener {
            sendCommand("audio_preinit")
            sendCommand("bootmusic")
            Analytics.trackEvent("OnClick CMD DIAGNOSE SOUND")
        }
        mstarFirmwareUpgrade.setOnClickListener {
            sendCommand("custar")
            Analytics.trackEvent("OnClick CMD FIRMWARE UPGRADE")
        }
        mstarMemoryInformation.setOnClickListener {
            sendCommand("mmcinfo")
            Analytics.trackEvent("OnClick CMD MEMORY INFORMATION")
        }
        mstarPanelInverter1.setOnClickListener {
            sendCommand("env edit MIRROR_ON")
            sendCommand("0")
            sendCommand("save")
            sendCommand("reset")
            Analytics.trackEvent("OnClick CMD PANEL INVERTER 1")
        }
        mstarPanelInverter2.setOnClickListener {
            sendCommand("env edit MIRROR_ON")
            sendCommand("1")
            sendCommand("save")
            sendCommand("reset")
            Analytics.trackEvent("OnClick CMD PANEL INVERTER 2")
        }
        mstarReset.setOnClickListener {
            sendCommand("reset")
            Analytics.trackEvent("OnClick CMD RESET")
        }



        realtekEcho.setOnClickListener {
            try {
                for (i in 5000 downTo 0 step 100) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        sendCommand("BUSHERS-TESTING ")
                    }, i.toLong())
                }
            } catch (e: Exception) {
                Crashes.trackError(e)
            }
            Analytics.trackEvent("OnClick CMD ECHO")
        }
        realtekKeyAccessRealtek.setOnClickListener {
            try {
                for (i in 5000 downTo 0 step 100) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        sendCommand(esc)
                    }, i.toLong())
                }
            } catch (e: Exception) {
                Crashes.trackError(e)
            }
            Analytics.trackEvent("OnClick CMD KEY ACCESS REALTEK")
        }
        realtekEnableUartart.setOnClickListener {
            sendCommand("setuart on")
            Analytics.trackEvent("OnClick CMD ENABLE UARTART")
        }
        realtekAssignPanelanelAndMirror.setOnClickListener {
            sendCommand("panel a")
            Analytics.trackEvent("OnClick CMD ASSIGN PANELANEL AND MIRROR")
        }
        realtekEmmcInfoo.setOnClickListener {
            sendCommand("mmc info ")
            Analytics.trackEvent("OnClick CMD EMMC INFOO")
        }
        realtekEmmcStatustus.setOnClickListener {
            sendCommand("mmc speed")
            Analytics.trackEvent("OnClick CMD EMMC STATUSTUS")
        }
        realtekReportOffEmmc.setOnClickListener {
            sendCommand("mmc report")
            Analytics.trackEvent("OnClick CMD REPORT OFF EMMC")
        }
        realtekTestAllDevices.setOnClickListener {
            sendCommand("test_i2c")
            Analytics.trackEvent("OnClick CMD TEST ALL DEVICES")
        }
        realtekFirmwareUpgradeUpgrade.setOnClickListener {
            sendCommand("cusboot")
            Analytics.trackEvent("OnClick CMD FIRMWARE UPGRADE UPGRADE")
        }
        realtekTestUsbPorts.setOnClickListener {
            sendCommand("usb start")
            Analytics.trackEvent("OnClick CMD TEST USB PORTS")
        }
        realtekDisableWachdogwachdog.setOnClickListener {
            sendCommand("wdt 0")
            Analytics.trackEvent("OnClick CMD DISABLE WACHDOGWACHDOG")
        }
        realtekAssignRemoteemote.setOnClickListener {
            sendCommand("irda")
            Analytics.trackEvent("OnClick CMD ASSIGN REMOTEEMOTE")
        }
        realtekResetMainin.setOnClickListener {
            sendCommand("reset")
            Analytics.trackEvent("OnClick CMD RESET MAININ")
        }




        hisenseKeyAccess1.setOnClickListener {
            try {
                for (i in 5000 downTo 0 step 100) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        sendCommand(newline!!)
                    }, i.toLong())
                }
            } catch (e: Exception) {
                Crashes.trackError(e)
            }
            Analytics.trackEvent("OnClick CMD KEY ACCESS 1")
        }
        hisenseUsbTest.setOnClickListener {
            sendCommand("usb start")
            Analytics.trackEvent("OnClick CMD USB TEST")
        }
        hisenseFirmwareUpgrade.setOnClickListener {
            sendCommand("usbboot")
            Analytics.trackEvent("OnClick CMD FIRMWARE UPGRADE")
        }
        hisenseResetMainBoard.setOnClickListener {
            sendCommand("BOARD= reset")
            Analytics.trackEvent("OnClick CMD RESET MAIN BOARD")
        }
        hisenseTestDeMainBoard.setOnClickListener {
            sendCommand("MAIN BOARD= mtest")
            Analytics.trackEvent("OnClick CMD TEST DE MAIN BOARD")
        }
        hisenseInformationEmmc.setOnClickListener {
            sendCommand("mmcinfo")
            Analytics.trackEvent("OnClick CMD INFORMATION eMMC")
        }
        hisenseUpgradeLinux.setOnClickListener {
            sendCommand("upgrade")
            Analytics.trackEvent("OnClick CMD UPGRADE LINUX ")
        }
        hisenseVersionMain.setOnClickListener {
            sendCommand("ver")
            Analytics.trackEvent("OnClick CMD VERSION MAIN")
        }
        hisenseMemoryAsiggn.setOnClickListener {
            sendCommand("bdinfo")
            Analytics.trackEvent("OnClick CMD MEMORY ASIGGN ")
        }
        hisensePartitionInfo.setOnClickListener {
            sendCommand("mtdparts")
            Analytics.trackEvent("OnClick CMD PARTITION INFO")
        }
        hisenseKeyAccess2.setOnClickListener {
            try {
                for (i in 5000 downTo 0 step 100) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        sendCommand(esc)
                    }, i.toLong())
                }
            } catch (e: Exception) {
                Crashes.trackError(e)
            }
            Analytics.trackEvent("OnClick CMD KEY ACCESS 2")
        }
        hisenseFirmwareUpgrade2.setOnClickListener {
            sendCommand("u")
            Analytics.trackEvent("OnClick CMD FIRMWARE UPGRADE 2")
        }
        hisenseReinicio.setOnClickListener {
            sendCommand("bt")
            Analytics.trackEvent("OnClick CMD REINICIO")
        }
        hisenseShowLogo.setOnClickListener {
            sendCommand("logo")
            Analytics.trackEvent("OnClick CMD SHOW LOGO")
        }
        hisenseIdentificationChipset.setOnClickListener {
            sendCommand("chipid")
            Analytics.trackEvent("OnClick CMD IDENTIFICATION CHIPSET")
        }
        hisenseTestMemory.setOnClickListener {
            sendCommand("memtest")
            Analytics.trackEvent("OnClick CMD TEST MEMORY")
        }
        hisenseEmmcReference.setOnClickListener {
            sendCommand("msdc.identify 1")
            Analytics.trackEvent("OnClick CMD EMMC REFERENCE")
        }
        hisenseInfoEmmc.setOnClickListener {
            sendCommand("msdc.configinfo")
            Analytics.trackEvent("OnClick CMD INFO EMMC")
        }
        hisenseDeviceInfo.setOnClickListener {
            sendCommand("msdc.getdevinfo")
            Analytics.trackEvent("OnClick CMD DEVICE INFO")
        }





        nuggetKeyAccess.setOnClickListener {
            try {
                for (i in 5000 downTo 0 step 100) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        sendCommand(newline!!)
                    }, i.toLong())
                }
            } catch (e: Exception) {
                Crashes.trackError(e)
            }
            Analytics.trackEvent("OnClick CMD KEY ACCESS")
        }
        nuggetForceBacklight.setOnClickListener {
            sendCommand("panel_init")
            Analytics.trackEvent("OnClick CMD FORCE BACKLIGHT")
        }
        nuggetShowlogo.setOnClickListener {
            sendCommand("bootlogo")
            Analytics.trackEvent("OnClick CMD SHOWLOGO")
        }
        nuggetStopLogo.setOnClickListener {
            sendCommand("destroy_logo")
            Analytics.trackEvent("OnClick CMD STOP LOGO")
        }
        nuggetBackupTo.setOnClickListener {
            sendCommand("nandbinall")
            Analytics.trackEvent("OnClick CMD BACKUP TO")
        }
        nuggetRestoreBackup.setOnClickListener {
            sendCommand("bin2nand")
            Analytics.trackEvent("OnClick CMD RESTORE BACKUP")
        }
        nuggetFirmwareUpgrade1.setOnClickListener {
            sendCommand("custar")
            Analytics.trackEvent("OnClick CMD FIRMWARE UPGRADE 1")
        }
        nuggetFirmwareUpgrade2.setOnClickListener {
            sendCommand("ustar")
            Analytics.trackEvent("OnClick CMD FIRMWARE UPGRADE 2")
        }
        nuggetResetMain.setOnClickListener {
            sendCommand("reset")
            Analytics.trackEvent("OnClick CMD RESET MAIN")
        }
        nuggetReset2Mstar.setOnClickListener {
            sendCommand("mstar")
            Analytics.trackEvent("OnClick CMD RESET 2 MSTAR")
        }
        nuggetReset3Securebootcmd.setOnClickListener {
            sendCommand("SecureBootCmd")
            Analytics.trackEvent("OnClick CMD RESET 3 SECUREBOOTCMD")
        }
        nuggetTestOf.setOnClickListener {
            sendCommand("memtest")
            Analytics.trackEvent("OnClick CMD TEST OF")
        }
        nuggetViewAll.setOnClickListener {
            sendCommand("showtb 0 ")
            Analytics.trackEvent("OnClick CMD VIEW ALL")
        }
        nuggetCopySpi.setOnClickListener {
            sendCommand("spi2usb")
            Analytics.trackEvent("OnClick CMD COPY SPI")
        }
        nuggetTestUsb.setOnClickListener {
            sendCommand("usb start")
            sendCommand("usb storage")
            Analytics.trackEvent("OnClick CMD TEST USB")
        }


        panasonicKeyAccess.setOnClickListener {
            try {
                for (i in 5000 downTo 0 step 100) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        sendCommand(newline!!)
                    }, i.toLong())
                }
            } catch (e: Exception) {
                Crashes.trackError(e)
            }
            Analytics.trackEvent("OnClick CMD KEY ACCESS")
        }
        panasonicForceBacklight.setOnClickListener {
            sendCommand("panel_init")
            Analytics.trackEvent("OnClick CMD FORCE BACKLIGHT")
        }
        panasonicDiagnoseSound.setOnClickListener {
            sendCommand("audio_preinit")
            sendCommand("bootmusic")
            Analytics.trackEvent("OnClick CMD DIAGNOSE SOUND")
        }
        panasonicBootlogo.setOnClickListener {
            sendCommand("bootlogo")
            Analytics.trackEvent("OnClick CMD BOOTLOGO")
        }
        panasonicDeleteLogo.setOnClickListener {
            sendCommand("destroy_logo")
            Analytics.trackEvent("OnClick CMD DELETE LOGO")
        }
        panasonicBackupToUsb.setOnClickListener {
            sendCommand("usbstart 0")
            Analytics.trackEvent("OnClick CMD BACKUP TO USB")
        }
        panasonicRestoreBackup.setOnClickListener {
            sendCommand("emmcbootbin 0")
            Analytics.trackEvent("OnClick CMD RESTORE BACKUP ")
        }
        panasonicCopySpi.setOnClickListener {
            sendCommand("usbstart 0")
            sendCommand("spi2usb")
            Analytics.trackEvent("OnClick CMD COPY SPI")
        }
        panasonicRestoreSpi.setOnClickListener {
            sendCommand("usbstart 0")
            sendCommand("usb2spi")
            Analytics.trackEvent("OnClick CMD RESTORE SPI")
        }
        panasonicMemoryEmmcInformation.setOnClickListener {
            sendCommand("mmcinfo")
            Analytics.trackEvent("OnClick CMD MEMORY eMMC INFORMATION")
        }
        panasonicMemoryTest.setOnClickListener {
            sendCommand("memtest")
            Analytics.trackEvent("OnClick CMD MEMORY TEST")
        }
        panasonicVerifyStatusAndFirmware.setOnClickListener {
            sendCommand("version")
            Analytics.trackEvent("OnClick CMD VERIFY STATUS AND FIRMWARE")
        }
        panasonicRecordMac.setOnClickListener {
            sendCommand("burn_mac")
            Analytics.trackEvent("OnClick CMD RECORD MAC")
        }
        panasonicTestPanelAndSound.setOnClickListener {
            sendCommand("dbtable_init")
            Analytics.trackEvent("OnClick CMD TEST PANEL AND SOUND")
        }
        panasonicTestVideo.setOnClickListener {
            sendCommand("ebist")
            Analytics.trackEvent("OnClick CMD TEST VIDEO")
        }
        panasonicAssignPanel4K2K.setOnClickListener {
            sendCommand("inx_panel_set_4k2k- panel_set_4")
            Analytics.trackEvent("OnClick CMD ASSIGN PANEL 4K_2K")
        }
        panasonicAssignPanelFullHd1.setOnClickListener {
            sendCommand("inix_panel_set_fhd- panel_set_fhd")
            Analytics.trackEvent("OnClick CMD ASSIGN PANEL FULL HD 1")
        }
        panasonicAssignPanelFullHd2.setOnClickListener {
            sendCommand("inix_panel_set_init")
            Analytics.trackEvent("OnClick CMD ASSIGN PANEL FULL HD 2")
        }
        panasonicFirmwareUpgrade.setOnClickListener {
            sendCommand("custar")
            Analytics.trackEvent("OnClick CMD FIRMWARE UPGRADE")
        }
        panasonicTestUsbPorts.setOnClickListener {
            sendCommand("usb start")
            Analytics.trackEvent("OnClick CMD TEST USB PORTS")
        }
        panasonicMaininfo.setOnClickListener {
            sendCommand("bdinfo")
            Analytics.trackEvent("OnClick CMD MAININFO")
        }
        panasonicReset1.setOnClickListener {
            sendCommand("SecureBootCmd")
            Analytics.trackEvent("OnClick CMD RESET 1")
        }
        panasonicReset2.setOnClickListener {
            sendCommand("reset")
            Analytics.trackEvent("OnClick CMD RESET 2")
        }

        return view
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_terminal, menu)
        // menu.findItem(R.id.hex).isChecked = hexEnabled
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
                // builder.create().show()
                true
            }
            /*
            R.id.hex -> {
                hexEnabled = !hexEnabled
                sendText!!.text = ""
                hexWatcher!!.enable(hexEnabled)
                sendText!!.hint = if (hexEnabled) "HEX mode" else ""
                item.isChecked = hexEnabled
                true
            }
             */
            R.id.save -> {
                try {
                    val sendIntent: Intent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, receiveText!!.text.toString())
                        type = "text/plain"
                    }

                    val shareIntent = Intent.createChooser(sendIntent, null)
                    startActivity(shareIntent)
                } catch (e: IOException) {
                    e.printStackTrace()
                }
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
        } catch (e: IOException) {
            Crashes.trackError(e)
            onSerialConnectError(e)
        }
    }

    private fun disconnect() {
        connected = Connected.False
        service!!.disconnect()
    }

    private fun sendCommand(str: String) {
        if (connected != Connected.True) {
            Toast.makeText(activity, "not connected", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val data = (str + newline).toByteArray()
            service!!.write(data)
        } catch (e: Exception) {
            Crashes.trackError(e)
            onSerialIoError(e)
        }
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

