package com.ghostthief.game.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.ghostthief.game.BuildConfig
import com.ghostthief.game.R
import com.ghostthief.game.net.BtClient
import com.ghostthief.game.net.BtHost
import com.ghostthief.game.net.HostServer
import com.ghostthief.game.net.NetClient
import com.ghostthief.game.net.NetLink
import com.ghostthief.game.net.NetProtocol
import com.ghostthief.game.net.NetSession
import com.ghostthief.game.net.NetUtil
import com.ghostthief.game.net.QrPayload
import com.ghostthief.game.net.Room
import com.ghostthief.game.net.RoomBroadcaster
import com.ghostthief.game.net.RoomFinder
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * غرفة اللعب بين هاتفين بثلاث طرق: الواي فاي (بحث تلقائي أو عنوان يدوي)، البلوتوث (الأجهزة المقترنة)،
 * أو رمز QR (المضيف يعرضه والضيف يمسحه فيدخل مباشرة). المضيف يختار دوره (شرطي/حرامي) والضيف يأخذ الآخر.
 * كلها بلا إنترنت ولا خادم ولا حسابات: الاتصال مباشر بين الهاتفين.
 */
class MultiplayerActivity : BaseActivity() {
    private enum class Mode { WIFI, BT, QR }

    private lateinit var status: TextView
    private lateinit var roleGroup: RadioGroup
    private lateinit var panelWifi: LinearLayout
    private lateinit var panelBt: LinearLayout
    private lateinit var panelQr: LinearLayout
    private lateinit var wifiBlock: HostBlock
    private lateinit var btBlock: HostBlock
    private lateinit var qrBlock: HostBlock
    private lateinit var roomsBox: LinearLayout
    private lateinit var devicesBox: LinearLayout
    private var policeId = 0
    private var d = 1f

    private var server: HostServer? = null
    private var broadcaster: RoomBroadcaster? = null
    private var finder: RoomFinder? = null
    private var btServer: BtHost? = null
    private val seen = HashSet<String>()
    private var handedOff = false
    /** رقم البناء بدون خانة النكهة: هاتفان بنسختين (بإعلانات وبدونها) من نفس البناء يتوافقان. */
    private val build get() = BuildConfig.VERSION_CODE / 10

    private var pendingBt: (() -> Unit)? = null
    private val btPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val run = pendingBt; pendingBt = null
        if (granted) run?.invoke() else status.text = getString(R.string.bt_perm_denied)
    }

    /** زر «أنشئ غرفة» + معلومات الانتظار (+ صورة QR) + زر الإلغاء. */
    private inner class HostBlock(parent: LinearLayout, withImage: Boolean, onHost: () -> Unit) {
        val hostBtn = button(R.string.net_host_btn, false, onHost)
        val info = TextView(this@MultiplayerActivity).apply {
            textSize = 20f; setTextColor(0xFF1B5E20.toInt()); visibility = View.GONE
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
        }
        val image: ImageView? = if (withImage) ImageView(this@MultiplayerActivity).apply {
            adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE
            setBackgroundColor(0xFFFFFFFF.toInt())
        } else null
        val stopBtn = button(R.string.net_stop_host, true) { stopAll() }.apply { visibility = View.GONE }

        init {
            parent.addView(hostBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (56 * d).toInt()))
            parent.addView(info)
            image?.let { parent.addView(it, LinearLayout.LayoutParams((280 * d).toInt(), (280 * d).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL }) }
            parent.addView(stopBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * d).toInt()))
        }

        fun waiting(text: String, qr: Bitmap? = null) {
            info.text = text; info.visibility = View.VISIBLE
            image?.let { it.setImageBitmap(qr); it.visibility = if (qr != null) View.VISIBLE else View.GONE }
            hostBtn.visibility = View.GONE; stopBtn.visibility = View.VISIBLE
        }

        fun idle() {
            info.visibility = View.GONE
            image?.let { it.setImageDrawable(null); it.visibility = View.GONE }
            stopBtn.visibility = View.GONE; hostBtn.visibility = View.VISIBLE
        }
    }

    private fun button(res: Int, outlined: Boolean, onClick: () -> Unit): MaterialButton =
        (if (outlined) MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle) else MaterialButton(this)).apply {
            text = getString(res); textSize = 16f; setOnClickListener { onClick() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(col); setBackgroundColor(0xFFE3F2FD.toInt()) })

        fun text(parent: LinearLayout, res: Int, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            text = getString(res); textSize = size; setTextColor(color)
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, (8 * d).toInt(), 0, (4 * d).toInt())
        }.also { parent.addView(it) }
        fun wide(b: View, h: Int = 56) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (h * d).toInt())

        text(col, R.string.net_title, 24f, 0xFF0D47A1.toInt(), true)
        text(col, R.string.net_intro, 14f, 0xFF37474F.toInt())
        text(col, R.string.net_privacy_note, 12.5f, 0xFF607D8B.toInt())

        // ---- الدور (إن كنت أنت من ينشئ الغرفة) ----
        text(col, R.string.net_role_title, 15f, 0xFF0D47A1.toInt(), true)
        roleGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        policeId = View.generateViewId()
        roleGroup.addView(RadioButton(this).apply { id = policeId; text = getString(R.string.net_role_police); textSize = 16f })
        roleGroup.addView(RadioButton(this).apply { id = View.generateViewId(); text = getString(R.string.net_role_thief); textSize = 16f })
        roleGroup.check(policeId)
        col.addView(roleGroup)

        // ---- اختيار طريقة الاتصال ----
        text(col, R.string.net_mode_title, 18f, 0xFF0D47A1.toInt(), true)
        val idWifi = View.generateViewId(); val idBt = View.generateViewId(); val idQr = View.generateViewId()
        val toggle = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        for ((bid, res) in listOf(idWifi to R.string.net_mode_wifi, idBt to R.string.net_mode_bt, idQr to R.string.net_mode_qr)) {
            toggle.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = bid; text = getString(res); textSize = 14f
            }, LinearLayout.LayoutParams(0, (56 * d).toInt(), 1f))
        }
        col.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun panel() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }.also { col.addView(it) }
        panelWifi = panel(); panelBt = panel(); panelQr = panel()

        // ---- واي فاي ----
        text(panelWifi, R.string.net_host_section, 18f, 0xFF0D47A1.toInt(), true)
        wifiBlock = HostBlock(panelWifi, false) { startTcpHosting(wifiBlock, withBroadcast = true, withQr = false) }
        text(panelWifi, R.string.net_join_section, 18f, 0xFF0D47A1.toInt(), true)
        panelWifi.addView(button(R.string.net_search, false) { startSearch() }, wide(panelWifi))
        roomsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panelWifi.addView(roomsBox)
        panelWifi.addView(button(R.string.net_manual, true) { askManual() }, wide(panelWifi, 52))

        // ---- بلوتوث ----
        text(panelBt, R.string.bt_hint, 14f, 0xFF37474F.toInt())
        panelBt.addView(button(R.string.bt_pair_btn, true) {
            try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) { status.text = getString(R.string.bt_unavailable) }
        }, wide(panelBt, 52))
        text(panelBt, R.string.net_host_section, 18f, 0xFF0D47A1.toInt(), true)
        btBlock = HostBlock(panelBt, false) { startBtHosting() }
        text(panelBt, R.string.net_join_section, 18f, 0xFF0D47A1.toInt(), true)
        panelBt.addView(button(R.string.bt_show_devices, false) { showPairedDevices() }, wide(panelBt))
        devicesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panelBt.addView(devicesBox)

        // ---- QR ----
        text(panelQr, R.string.qr_hint, 14f, 0xFF37474F.toInt())
        text(panelQr, R.string.net_host_section, 18f, 0xFF0D47A1.toInt(), true)
        qrBlock = HostBlock(panelQr, true) { startTcpHosting(qrBlock, withBroadcast = false, withQr = true) }
        text(panelQr, R.string.net_join_section, 18f, 0xFF0D47A1.toInt(), true)
        panelQr.addView(button(R.string.qr_scan_btn, false) { scanQr() }, wide(panelQr))

        status = TextView(this).apply { textSize = 15f; setTextColor(0xFFC62828.toInt()); setPadding(0, (12 * d).toInt(), 0, 0) }
        col.addView(status)

        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) setMode(when (checkedId) { idBt -> Mode.BT; idQr -> Mode.QR; else -> Mode.WIFI })
        }
        toggle.check(idWifi)
        setMode(Mode.WIFI)
    }

    private fun setMode(mode: Mode) {
        stopAll()
        status.text = ""
        panelWifi.visibility = if (mode == Mode.WIFI) View.VISIBLE else View.GONE
        panelBt.visibility = if (mode == Mode.BT) View.VISIBLE else View.GONE
        panelQr.visibility = if (mode == Mode.QR) View.VISIBLE else View.GONE
    }

    private fun roleName(role: Int) = getString(if (role == NetProtocol.ROLE_POLICE) R.string.role_name_police else R.string.role_name_thief)

    private fun chosenRole() = if (roleGroup.checkedRadioButtonId == policeId) NetProtocol.ROLE_POLICE else NetProtocol.ROLE_THIEF

    // ---------- مضيف: واي فاي و QR (نفس خادم TCP) ----------
    private fun startTcpHosting(block: HostBlock, withBroadcast: Boolean, withQr: Boolean) {
        val ip = NetUtil.localIpv4()
        if (ip == null) { status.text = getString(R.string.net_no_wifi); return }
        stopAll()
        status.text = ""
        val role = chosenRole()
        val code = NetUtil.randomCode()
        server = HostServer(
            code, role, build, NetProtocol.TCP_PORT,
            onPacket = NetSession::onPacket, onClosed = NetSession::onClosed,
            onGuest = { link -> runOnUiThread { handOff(link, true, role) } },
            onError = { msg -> runOnUiThread { status.text = msg; stopAll() } }
        ).also { it.start() }
        if (withBroadcast) broadcaster = RoomBroadcaster(getString(R.string.net_room_name), role).also { it.start() }
        if (withQr) {
            val bmp = try { QrImage.make(QrPayload(ip, NetProtocol.TCP_PORT, code).encode()) } catch (_: Exception) { null }
            block.waiting(getString(R.string.qr_waiting_fmt, code, ip), bmp)
        } else {
            block.waiting(getString(R.string.net_waiting_fmt, code, ip))
        }
    }

    // ---------- مضيف: بلوتوث ----------
    private fun startBtHosting() = withBluetooth { adapter ->
        stopAll()
        status.text = ""
        val role = chosenRole()
        btServer = BtHost(
            adapter, role, build,
            onPacket = NetSession::onPacket, onClosed = NetSession::onClosed,
            onGuest = { link -> runOnUiThread { handOff(link, true, role) } },
            onError = { msg -> runOnUiThread { status.text = msg; stopAll() } }
        ).also { it.start() }
        btBlock.waiting(getString(R.string.bt_waiting))
    }

    private fun stopAll() {
        server?.stop(); server = null
        broadcaster?.stop(); broadcaster = null
        finder?.stop(); finder = null
        btServer?.stop(); btServer = null
        if (!isFinishing && ::wifiBlock.isInitialized && ::btBlock.isInitialized && ::qrBlock.isInitialized) {
            wifiBlock.idle(); btBlock.idle(); qrBlock.idle()
        }
    }

    // ---------- بلوتوث: الصلاحيات والضيف ----------
    @SuppressLint("MissingPermission")
    private fun withBluetooth(block: (BluetoothAdapter) -> Unit) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { status.text = getString(R.string.bt_unavailable); return }
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingBt = { withBluetooth(block) }
            btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        if (!adapter.isEnabled) { status.text = getString(R.string.bt_turn_on); return }
        block(adapter)
    }

    @SuppressLint("MissingPermission")
    private fun showPairedDevices() = withBluetooth { adapter ->
        devicesBox.removeAllViews()
        status.text = ""
        val devices = adapter.bondedDevices?.toList().orEmpty()
        if (devices.isEmpty()) { status.text = getString(R.string.bt_no_paired); return@withBluetooth }
        for (dev in devices) {
            val name = dev.name?.takeIf { it.isNotBlank() } ?: dev.address
            devicesBox.addView(button(R.string.net_join, true) { joinBluetooth(adapter, dev) }.apply { text = getString(R.string.bt_device_fmt, name) })
        }
    }

    @SuppressLint("MissingPermission")
    private fun joinBluetooth(adapter: BluetoothAdapter, device: android.bluetooth.BluetoothDevice) {
        status.text = getString(R.string.net_connecting)
        Thread {
            val r = BtClient.connect(adapter, device, build, NetSession::onPacket, NetSession::onClosed)
            runOnUiThread { finishJoin(r) }
        }.start()
    }

    // ---------- ضيف: واي فاي ----------
    private fun startSearch() {
        finder?.stop()
        roomsBox.removeAllViews(); seen.clear()
        status.text = getString(R.string.net_searching)
        finder = RoomFinder { room -> runOnUiThread { addRoom(room) } }.also { it.start() }
    }

    private fun addRoom(room: Room) {
        if (!seen.add(room.address + ":" + room.port)) return
        roomsBox.addView(button(R.string.net_join, true) { askCode(room.address, room.port) }.apply {
            text = getString(R.string.net_room_fmt, room.name, roleName(room.hostRole), roleName(NetProtocol.otherRole(room.hostRole)))
        })
    }

    private fun codeField() = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER; hint = getString(R.string.net_code_hint); textSize = 22f
        layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER
        filters = arrayOf(android.text.InputFilter.LengthFilter(4))
    }

    private fun askCode(host: String, port: Int) {
        val code = codeField()
        AlertDialog.Builder(this)
            .setTitle(R.string.net_enter_code).setView(code)
            .setPositiveButton(R.string.net_join) { _, _ -> join(host, port, code.text.toString()) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun askManual() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
        val ip = EditText(this).apply { hint = getString(R.string.net_ip_hint); inputType = InputType.TYPE_CLASS_PHONE; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val code = codeField()
        box.addView(ip); box.addView(code)
        AlertDialog.Builder(this)
            .setTitle(R.string.net_manual).setView(box)
            .setPositiveButton(R.string.net_join) { _, _ -> join(ip.text.toString().trim(), NetProtocol.TCP_PORT, code.text.toString()) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun join(rawHost: String, port: Int, rawCode: String) {
        // الكيبورد العربي قد يكتب أرقاماً هندية (١٩٢.١٦٨…): نحوّلها إلى لاتينية قبل الاتصال
        val host = NetUtil.normalizeDigits(rawHost)
        val code = NetUtil.normalizeDigits(rawCode)
        if (host.isEmpty() || code.length != 4 || !code.all { it in '0'..'9' }) { status.text = getString(R.string.net_code_hint); return }
        status.text = getString(R.string.net_connecting)
        Thread {
            val r = NetClient.connect(host, code, build, port, NetSession::onPacket, NetSession::onClosed)
            runOnUiThread { finishJoin(r) }
        }.start()
    }

    private fun finishJoin(r: com.ghostthief.game.net.ConnectResult) {
        val link = r.link
        if (isDestroyed || handedOff) { link?.close(); return }
        if (link != null) handOff(link, false, NetProtocol.otherRole(r.hostRole)) else status.text = r.error ?: ""
    }

    // ---------- ضيف: QR ----------
    private fun scanQr() {
        status.text = ""
        try {
            val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(this, options).startScan()
                .addOnSuccessListener { barcode ->
                    val p = QrPayload.parse(barcode.rawValue)
                    if (p == null) status.text = getString(R.string.qr_invalid) else join(p.host, p.port, p.code)
                }
                .addOnCanceledListener { }
                .addOnFailureListener { status.text = getString(R.string.qr_scan_failed) }
        } catch (_: Throwable) {
            status.text = getString(R.string.qr_scan_failed)
        }
    }

    /** الاتصال نجح: ننتقل إلى شاشة اللعب بنفس الجلسة. */
    private fun handOff(link: NetLink, host: Boolean, myRole: Int) {
        if (handedOff) return
        handedOff = true
        NetSession.begin(link, host, myRole)
        stopAll()
        startActivity(GameActivity.netIntent(this))
        finish()
    }

    override fun onDestroy() {
        if (!handedOff) stopAll()
        super.onDestroy()
    }
}
