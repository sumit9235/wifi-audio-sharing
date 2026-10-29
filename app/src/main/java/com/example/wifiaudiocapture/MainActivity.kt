package com.example.wifiaudiocapture

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.text.InputType
import android.view.View
import android.widget.*
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.NetworkInterface

/** Native accessible controls, adaptive system dark theme; services own all streaming. */
class MainActivity : Activity() {
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observe: Job? = null
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var body: LinearLayout
    private lateinit var banner: TextView
    private var page = "home"
    private val navButtons = mutableMapOf<String, Button>()
    private var discovery: SessionDiscovery? = null
    private var sessions = emptyList<SessionDiscovery.Session>()
    private var sessionList: LinearLayout? = null
    private var hostStatus: TextView? = null
    private var permissionStatus: TextView? = null
    private var lastMode: String? = null
    private var listenerStatus: TextView? = null
    private var roster: LinearLayout? = null
    private var startButton: Button? = null
    private var stopButton: Button? = null
    private var lastRoster: String? = null
    private var ipInput: EditText? = null
    private var pendingNotifications: (() -> Unit)? = null
    private var notificationMode: String? = null
    private var notificationHost: String? = null
    private var requestingProjection = false
    private val legacy: Boolean get() = prefs.getBoolean("legacy", false)
    private val displayName: String get() = prefs.getString("name", "Music room") ?: "Music room"
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = getColor(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: when {
            SessionState.state.value.hosting -> "host"
            SessionState.state.value.listening -> "listen"
            else -> "home"
        }
        requestingProjection = savedInstanceState?.getBoolean("projection") ?: false
        notificationMode = savedInstanceState?.getString("notificationMode")
        notificationHost = savedInstanceState?.getString("notificationHost")
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(color(R.color.surface)) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets.consumeSystemWindowInsets()
        }
        root.addView(label("Wi-Fi Music Sharing", 25, true).apply { setPadding(dp(20), dp(18), dp(20), dp(4)) })
        root.addView(label("One room. Shared sound.", 14).apply { setPadding(dp(20), 0, dp(20), dp(12)) })
        val nav = LinearLayout(this)
        listOf("Home" to "home", "Share" to "host", "Listen" to "listen", "Settings" to "settings").forEach { (title, key) ->
            val item = Button(this).apply { text = title; textSize = 12f; isAllCaps = false; minWidth = 0; setPadding(0,0,0,0); elevation = 0f; setOnClickListener { show(key) } }
            navButtons[key] = item
            nav.addView(item, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        root.addView(nav)
        banner = label("Ready • same Wi-Fi required", 14).apply { setPadding(dp(20), dp(10), dp(20), dp(10)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(banner)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(24)) }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(body) }, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root); root.requestApplyInsets(); show(page)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("page")?.let { show(it) }
    }
    private fun label(value: String, size: Int = 16, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color(if (bold) R.color.ink else R.color.muted))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(8))
    }
    private fun card(title: String, description: String = "", content: (LinearLayout) -> Unit = {}) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(16))
            background = GradientDrawable().apply { setColor(color(R.color.card)); cornerRadius = dp(20).toFloat() }
        }
        box.addView(label(title, 21, true))
        if (description.isNotEmpty()) box.addView(label(description, 15))
        content(box)
        body.addView(box, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(16) })
    }
    private fun button(parent: LinearLayout, title: String, action: () -> Unit): Button {
        val b = Button(this).apply {
            text = title; isAllCaps = false; minHeight = dp(48); elevation = 0f
            val primary = title == "Start Sharing" || title.startsWith("Join ") || title.startsWith("Allow ")
            setTextColor(color(if (primary) R.color.surface else R.color.accent))
            val shape = GradientDrawable().apply {
                setColor(color(if (primary) R.color.accent else R.color.card))
                cornerRadius = dp(12).toFloat(); if (!primary) setStroke(dp(1), color(R.color.accent))
            }
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33777777), shape, null)
            setOnClickListener { action() }
        }
        parent.addView(b, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(4); bottomMargin = dp(4) }); return b
    }
    private fun tell(message: String) { banner.text = message }
    private fun show(key: String) {
        discovery?.close(); discovery = null; sessions = emptyList()
        page = key
        navButtons.forEach { (destination, item) ->
            item.background = GradientDrawable().apply { setColor(color(if (destination == key) R.color.accent else R.color.card)); cornerRadius = dp(14).toFloat() }
            item.setTextColor(color(if (destination == key) R.color.surface else R.color.ink))
        }
        body.removeAllViews(); permissionStatus = null; lastMode = null; sessionList = null; hostStatus = null; listenerStatus = null
        roster = null; startButton = null; stopButton = null; lastRoster = null; ipInput = null
        tell(when { SessionState.state.value.hosting -> "● Sharing live • capture is active"; SessionState.state.value.listening -> "● Listener active"; else -> "Same Wi-Fi • local audio only" })
        when (key) { "host" -> hostPage(); "listen" -> listenPage(); "settings" -> settingsPage(); "help" -> helpPage(); "diagnostics" -> diagnosticsPage(); else -> homePage() }
        render(SessionState.state.value)
    }
    private fun homePage() {
        card("Bring your music together", "Play on the host phone and listen on nearby phones over the same Wi-Fi. No accounts or cloud audio.") {
            button(it, "Start Sharing") { show("host"); initiateHost() }
            button(it, "Join a Session") { show("listen") }
        }
        card("Before you start", "Use headphones on listeners to avoid feedback. Normal sessions need host approval. LAN traffic is unencrypted; use a trusted network and share only audio you have permission to distribute.") {
            button(it, "Connection help") { show("help") }
        }
    }
    private fun hostPage() {
        card(displayName, "Host • ${networkSummary()}\nLocal IPv4: ${localIpv4() ?: "not found — connect to Wi-Fi"}") {
            hostStatus = label("",14).also(it::addView)
            val permission = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            permissionStatus = label("Audio permission: ${if (permission) "granted" else "needed"}. Android capture consent is requested each time.", 13).also(it::addView)
            startButton = button(it, "Start Sharing") { initiateHost() }
            stopButton = button(it, "Stop sharing now") { stopService(Intent(this, CaptureService::class.java)) }
            button(it, "Stream diagnostics") { show("diagnostics") }
            if (legacy) it.addView(label("Phase 2 compatibility: manual IP, no approval or encryption.", 14))
        }
        card("Listeners & join requests", "Approve only phones you recognize. Requests expire after 30 seconds. Maximum five listeners.") {
            roster = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also(it::addView)
        }
    }
    private fun listenPage() {
        card("Nearby sessions", "${networkSummary()}\nChoose a host, then ask them to approve your request.") {
            button(it, "Refresh nearby sessions") { beginDiscovery() }
            sessionList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also(it::addView)
        }
        card("Your playback", "Playback continues while the app is minimized. Lock-screen behavior still needs verification on your phone.") {
            listenerStatus = label("",14).also(it::addView)
            it.addView(label("Listener volume", 15))
            it.addView(SeekBar(this).apply {
                max = 100; progress = prefs.getInt("volume", 100); contentDescription = "Listener volume"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                        if (fromUser) {
                            prefs.edit().putInt("volume", value).apply()
                            if (SessionState.state.value.listening) startService(Intent(this@MainActivity, ListenerService::class.java).setAction(ListenerService.VOLUME).putExtra("volume",value/100f))
                        }
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
            })
            button(it, "Disconnect") { stopService(Intent(this, ListenerService::class.java)) }
            button(it, "Stream diagnostics") { show("diagnostics") }
        }
        card("Join by IP", "Fallback when discovery is blocked. Enter the host's private IPv4 address. Both phones must use the same stream mode.") {
            ipInput = EditText(this).apply {
                hint = "Host IPv4, e.g. 192.168.1.12"; inputType = InputType.TYPE_CLASS_PHONE
                setSingleLine(true); contentDescription = "Host IPv4 address"
            }.also(it::addView)
            button(it, "Join this IP") { join(ipInput!!.text.toString().trim()) }
            button(it, "Troubleshoot Wi-Fi") { show("help") }
        }
        if (legacy) {
            sessionList?.addView(label("Phase 2 mode uses Join by IP. Disable compatibility in Settings for discovery and host approval.",14))
        } else beginDiscovery()
    }
    private fun beginDiscovery() {
        if (legacy) { tell("Use Join by IP in Phase 2 compatibility mode"); return }
        discovery?.close()
        discovery = SessionDiscovery(this, { found ->
            sessions = found; renderSessions()
        }, { message -> runOnUiThread { if (page == "listen") tell(message) } })
        try { discovery?.browse(); renderSessions() } catch (e: Exception) { tell("Discovery unavailable: ${e.message}. Try Join by IP.") }
    }
    private fun renderSessions() {
        val list = sessionList ?: return; list.removeAllViews()
        if (sessions.isEmpty()) list.addView(label("Looking for hosts… Start sharing on another phone. If nothing appears, refresh or use Join by IP.",14))
        sessions.forEach { session ->
            button(list, "Join ${session.name}") { join(session.host) }
        }
    }
    private fun settingsPage() {
        card("Make it yours", "A room/display name is shared only with local peers. No account is required.") {
            val name = EditText(this).apply { hint = "Display name"; setText(displayName); setSingleLine(); filters = arrayOf(android.text.InputFilter.LengthFilter(32)); contentDescription = "Room and listener display name" }
            it.addView(name)
            it.addView(label("Listener buffer profile",15))
            val profile = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Low delay • 80 ms", "Balanced • 120 ms", "Resilient • 160 ms"))
                setSelection(prefs.getInt("profile", 0)); contentDescription = "Listener latency profile"
            }; it.addView(profile)
            val compatibility = CheckBox(this).apply { text = "Phase 2 compatibility (manual IP)"; isChecked = legacy }; it.addView(compatibility)
            button(it, "Save settings") {
                val clean = name.text.toString().trim().filterNot { ch -> ch.isISOControl() }.take(32)
                if (clean.isEmpty()) { tell("Enter a display name"); return@button }
                val save = {
                    prefs.edit().putString("name", clean).putInt("profile", profile.selectedItemPosition).putBoolean("legacy", compatibility.isChecked).apply()
                    tell("Saved. Changes apply to the next session.")
                }
                if (compatibility.isChecked && !legacy) AlertDialog.Builder(this)
                    .setTitle("Use Phase 2 compatibility?")
                    .setMessage("This troubleshooting mode accepts listeners without host approval and streams unencrypted PCM. Use it only on a trusted Wi-Fi network. Enable it on both phones.")
                    .setPositiveButton("Enable") { _, _ -> save() }.setNegativeButton("Cancel", null).show()
                else save()
            }
        }
        card("Privacy & battery", "Audio stays on your LAN; it is never saved or uploaded by this app. Normal mode requires host approval, but does not encrypt traffic. A trusted LAN is required. Android and the source app decide what audio may be captured.\n\nOngoing notifications give immediate stop controls. Active sessions hold CPU/Wi-Fi locks; this uses battery. Vendor power-saving rules can still interrupt a session. Bluetooth adds separate output latency.") {
            button(it, "Permissions & troubleshooting") { show("help") }
        }
    }
    private fun helpPage() {
        card("Connection checklist", "${networkSummary()}\nLocal IP: ${localIpv4() ?: "not found"}\n\n1. Install the same APK on every phone.\n2. Use the same Wi-Fi; avoid guest/client isolation.\n3. Start host sharing and approve Android's prompt.\n4. Find a session and ask the host to approve joining.\n5. If discovery fails, use Join by IP.\n\nVPNs, mDNS filtering, guest networks and some hotspots block discovery or audio. Changing networks ends the session; reconnect and start/join again.")
        card("Audio & permissions", "Grant RECORD_AUDIO on the host; Android requires it for playback capture even though this app never records the microphone. Approve the fresh screen/audio capture prompt. On recent Android versions, select the full screen if single-app sharing produces silence.\n\nAllow notifications for visible stop controls. Android may still run a foreground service when notification permission is denied. ReVanced must allow playback capture. Protected/DRM audio cannot be bypassed. Calls/focus loss or unplugging headphones disconnects listener playback; rejoin afterward.\n\nTry Balanced or Resilient in Settings for weak Wi-Fi. A smaller buffer trades stability for delay. Diagnostics estimate pipeline age and cannot measure actual speaker latency.") {
            button(it, "Notification settings") {
                startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
    }
    private fun diagnosticsPage() {
        card("Stream diagnostics", "Pipeline estimates exclude speaker, DAC and Bluetooth latency. Compare modes on the same phones and output route; actual acoustic delay requires a physical recording.")
        card("Host capture") { hostStatus = label("",14).also(it::addView) }
        card("Listener playout") { listenerStatus = label("",14).also(it::addView) }
        button(body,"Back to active session") { show(if (SessionState.state.value.hosting) "host" else "listen") }
    }
    private fun render(state: SessionState.State) {
        val mode = when { state.hosting -> "● Sharing live • capture is active"; state.listening -> "● Listening • ongoing notification"; else -> "Ready • same Wi-Fi required" }
        if (mode != lastMode) { lastMode = mode; tell(mode) }
        permissionStatus?.text = "Audio permission: ${if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "granted" else "needed"}. Fresh Android capture consent is required for every session."
        val elapsed = if (state.startedMs > 0) (SystemClock.elapsedRealtime()-state.startedMs)/1000 else 0
        val hostDetail = if (page == "diagnostics") state.hostMessage else state.hostMessage.lineSequence().firstOrNull().orEmpty()
        val listenerDetail = if (page != "diagnostics" && state.listenerMessage.startsWith("PCM v2"))
            "Playing live audio. Open diagnostics to inspect stream health and estimated delay."
            else if (page == "diagnostics") state.listenerMessage else state.listenerMessage.lineSequence().firstOrNull().orEmpty()
        hostStatus?.text = "${if (state.hosting) "● LIVE • ${elapsed/60}:${(elapsed%60).toString().padStart(2,'0')}" else "● Not sharing"}\n$hostDetail"
        listenerStatus?.text = "${if (state.listening) "● Listener active" else "● Disconnected"}\n$listenerDetail"
        startButton?.isEnabled = !state.hosting && !state.listening && !requestingProjection
        stopButton?.isEnabled = state.hosting
        startButton?.alpha = if (startButton?.isEnabled == true) 1f else 0.4f
        stopButton?.alpha = if (state.hosting) 1f else 0.4f
        val key = state.peers.toString() + state.pending.toString()
        if (lastRoster != key) {
            lastRoster = key; roster?.removeAllViews()
            if (state.peers.isEmpty() && state.pending.isEmpty()) roster?.addView(label("No listeners yet. Ask a friend to find this session.",14))
            state.peers.forEach { roster?.addView(label("● $it — control connection alive",15)) }
            state.pending.forEach { pending ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                row.addView(label("${pending.name}\n${pending.address} requests to join",15,true))
                button(row, "Allow ${pending.name}") { decide(pending.id,true) }
                button(row, "Decline") { decide(pending.id,false) }
                roster?.addView(row)
            }
        }
    }
    private fun decide(id: String, allow: Boolean) {
        if (SessionState.state.value.hosting) startService(Intent(this, CaptureService::class.java).setAction(CaptureService.APPROVE).putExtra("id",id).putExtra("allow",allow))
    }
    private fun withNotifications(mode: String, host: String? = null, action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationMode = mode; notificationHost = host
            pendingNotifications = action; requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
        } else action()
    }
    private fun initiateHost() {
        if (SessionState.state.value.hosting || requestingProjection) return
        if (SessionState.state.value.listening) { tell("Disconnect listening before starting a host"); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),101); return
        }
        withNotifications("host") { requestCapture() }
    }
    private fun requestCapture() {
        if (SessionState.state.value.hosting || SessionState.state.value.listening || requestingProjection) return
        try {
            requestingProjection = true
            @Suppress("DEPRECATION") startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),100)
            tell("Approve Android capture, then return to ReVanced to play audio")
        } catch (e: Exception) { requestingProjection = false; tell("Cannot request capture: ${e.message}") }
    }
    private fun join(host: String) {
        if (SessionState.state.value.hosting) { tell("Stop sharing before joining another phone"); return }
        if (SessionState.state.value.listening) { tell("Disconnect the current session before joining another"); return }
        if (!Regex("\\d{1,3}(\\.\\d{1,3}){3}").matches(host) || host.split('.').any { it.toInt() !in 0..255 }) {
            tell("Enter a valid private Wi-Fi IPv4 address"); return
        }
        withNotifications("join",host) { startListener(host) }
    }
    private fun startListener(host: String) {
        if (SessionState.state.value.hosting || SessionState.state.value.listening) return
        try {
            startForegroundService(Intent(this, ListenerService::class.java).setAction(ListenerService.START)
                .putExtra("host",host).putExtra("legacy",legacy).putExtra("name",displayName)
                .putExtra("volume",prefs.getInt("volume",100)/100f)
                .putExtra("targetMs",listOf(80,120,160)[prefs.getInt("profile",0).coerceIn(0,2)]))
            tell("Connecting… ask the host to approve your join")
        } catch (e: Exception) { tell("Cannot start listener: ${e.message}") }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if (code == 101) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) initiateHost()
            else tell("Audio permission denied. Enable it in Android app settings to share.")
        }
        if (code == 102) {
            val action = pendingNotifications; val mode = notificationMode; val host = notificationHost
            pendingNotifications = null; notificationMode = null; notificationHost = null
            if (action != null) action()
            else if (mode == "host") requestCapture()
            else if (mode == "join" && host != null) startListener(host)
        }
    }
    @Deprecated("Platform consent result retained to preserve working Phase 2 flow")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code,result,data)
        if (code != 100) return
        requestingProjection = false
        if (result != RESULT_OK || data == null) { tell("Capture canceled. Nothing is being shared."); return }
        try {
            startForegroundService(Intent(this,CaptureService::class.java).setAction(CaptureService.START_ACTION)
                .putExtra("resultCode",result).putExtra("resultData",data).putExtra("legacy",legacy).putExtra("name",displayName))
        } catch (e: Exception) { tell("Cannot start sharing: ${e.message}") }
    }
    override fun onStart() {
        super.onStart()
        observe = ui.launch {
            launch { SessionState.state.collect { render(it) } }
            launch { while (isActive) { delay(1000); render(SessionState.state.value) } }
        }
        if (page == "listen" && !legacy && discovery == null) beginDiscovery()
    }
    override fun onStop() {
        observe?.cancel(); discovery?.close(); discovery = null; super.onStop()
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("page",page); out.putBoolean("projection",requestingProjection)
        out.putString("notificationMode",notificationMode); out.putString("notificationHost",notificationHost)
        super.onSaveInstanceState(out)
    }
    override fun onDestroy() { discovery?.close(); ui.cancel(); super.onDestroy() }
    private fun networkSummary(): String {
        val c = getSystemService(ConnectivityManager::class.java)
        val wifi = c.allNetworks.any { c.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        return if (wifi) "Wi-Fi connected" else "Wi-Fi not detected; connect or check hotspot settings"
    }
    private fun localIpv4(): String? = try {
        val c = getSystemService(ConnectivityManager::class.java)
        val wifi = c.allNetworks.firstOrNull { c.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        c.getLinkProperties(wifi)?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
            ?: NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    } catch (_: Exception) { null }
}
