package com.example.wifiaudiocapture

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.Gravity
import android.widget.*
import java.net.NetworkInterface

class MainActivity : Activity() {
    private val requestProjection = 100
    private val requestAudio = 101
    private lateinit var status: TextView
    private lateinit var ipInput: EditText
    private var listener: AudioListener? = null
    private lateinit var discovery: SessionDiscovery
    private lateinit var sessions: LinearLayout

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CaptureService.RESULT_ACTION) {
                status.text = intent.getStringExtra("message") ?: "Status updated"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 45, 30, 24)
            gravity = Gravity.TOP
        }
        fun text(value: String, size: Float = 16f): TextView = TextView(this).apply {
            this.text = value
            textSize = size
            setPadding(0, 0, 0, 14)
        }
        root.addView(text("Wi-Fi Music Sharing — Phase 3", 23f))
        root.addView(text("Both phones must use the same Wi-Fi. The host plays music in ReVanced. Only share audio you have permission to distribute.", 14f))
        root.addView(text("HOST (music phone)", 18f))
        root.addView(text("Host local IP: ${localIpv4() ?: "Not found — connect to Wi-Fi"}", 15f))
        root.addView(Button(this).apply { text = "Start live sharing"; setOnClickListener { initiateHost() } })
        root.addView(Button(this).apply { text = "Stop sharing"; setOnClickListener { stopService(Intent(this@MainActivity, CaptureService::class.java)); startService(Intent(this@MainActivity, CaptureService::class.java).apply { action = CaptureService.STOP_ACTION }); status.text = "Host stopping" } })
        root.addView(text("LISTENER (friend's phone)", 18f))
        ipInput = EditText(this).apply {
            hint = "Enter host IP, e.g. 192.168.1.12"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
        }
        sessions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(Button(this).apply { text = "Find nearby sessions / refresh"; setOnClickListener { discovery.browse() } })
        root.addView(sessions)
        root.addView(ipInput)
        root.addView(Button(this).apply { text = "Join and listen"; setOnClickListener { joinHost() } })
        root.addView(Button(this).apply { text = "Stop listening"; setOnClickListener { listener?.stop(); listener = null; status.text = "Listener stopped" } })
        status = text("Ready. Use headphones on listeners to avoid feedback.", 15f)
        root.addView(status)
        root.addView(text("Tip: If joining fails, confirm both phones can reach each other on Wi-Fi. Some guest networks block device-to-device traffic.", 13f))
        setContentView(ScrollView(this).apply { addView(root) })
        discovery = SessionDiscovery(this, { found -> runOnUiThread {
            sessions.removeAllViews()
            found.forEach { session -> sessions.addView(Button(this).apply {
                text = "Join ${session.name}"
                setOnClickListener { ipInput.setText(session.host); joinHost() }
            }) }
        } }, { message -> runOnUiThread { status.text = message } })
    }

    @Suppress("DEPRECATION")
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(CaptureService.RESULT_ACTION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
    }
    override fun onStop() { unregisterReceiver(receiver); super.onStop() }

    private fun initiateHost() {
        listener?.stop(); listener = null
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), requestAudio)
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        status.text = "Approve the Android audio-capture prompt"
        @Suppress("DEPRECATION") startActivityForResult(manager.createScreenCaptureIntent(), requestProjection)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestAudio && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) initiateHost()
        else status.text = "Audio permission denied"
    }

    @Deprecated("Activity result API used for lightweight single-activity sample")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestProjection) return
        if (resultCode != RESULT_OK || data == null) { status.text = "Capture permission denied"; return }
        startForegroundService(Intent(this, CaptureService::class.java).apply {
            action = CaptureService.START_ACTION
            putExtra("resultCode", resultCode)
            putExtra("resultData", data)
        })
        status.text = "Starting live sharing. Return to ReVanced to play music."
    }

    private fun joinHost() {
        val host = ipInput.text.toString().trim()
        if (host.isEmpty()) { status.text = "Enter the host phone's Wi-Fi IP"; return }
        listener?.stop()
        listener = AudioListener(host) { message -> runOnUiThread { status.text = message } }.also { it.start() }
        status.text = "Connecting to $host ..."
    }

    private fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) { null }

    override fun onDestroy() { discovery.close(); listener?.stop(); super.onDestroy() }
}
