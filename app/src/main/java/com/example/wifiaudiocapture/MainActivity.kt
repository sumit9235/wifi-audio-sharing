package com.example.wifiaudiocapture

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {
    private val requestProjection = 100
    private val requestAudio = 101
    private lateinit var status: TextView
    private lateinit var fileInfo: TextView
    private var player: MediaPlayer? = null

    private val results = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CaptureService.RESULT_ACTION) {
                status.text = intent.getStringExtra("message") ?: "Capture completed"
                fileInfo.text = "Saved: ${intent.getStringExtra("path") ?: "none"}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 64, 36, 28)
            gravity = Gravity.TOP
        }
        fun label(text: String, size: Float): TextView = TextView(this).apply {
            this.text = text; textSize = size; setPadding(0, 0, 0, 28)
        }
        root.addView(label("Internal Audio Capture Test", 24f))
        root.addView(label("1. Start music in ReVanced.\n2. Return here and tap Record.\n3. Approve Android audio and screen-capture prompts.\n4. Play ReVanced music during the 10-second test.\n5. Return and tap Listen to test.", 16f))
        status = label("Ready. No microphone audio is recorded.", 16f)
        root.addView(status)
        fileInfo = label("No recording yet", 13f)
        root.addView(fileInfo)
        root.addView(Button(this).apply { text = "Record 10 seconds"; setOnClickListener { initiate() } })
        root.addView(Button(this).apply { text = "Listen to saved sample"; setOnClickListener { playLast() } })
        root.addView(Button(this).apply { text = "Stop recording"; setOnClickListener { startService(Intent(this@MainActivity, CaptureService::class.java).apply { action = CaptureService.STOP_ACTION }) } })
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(CaptureService.RESULT_ACTION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(results, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(results, filter)
    }
    override fun onStop() { unregisterReceiver(results); super.onStop() }

    private fun initiate() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), requestAudio)
            return
        }
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        status.text = "Approve Android's screen/audio capture dialog to continue."
        @Suppress("DEPRECATION") startActivityForResult(mgr.createScreenCaptureIntent(), requestProjection)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestAudio && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) initiate()
        else status.text = "Audio capture permission denied."
    }
    @Deprecated("Legacy activity result for compact standalone example")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestProjection) return
        if (resultCode != RESULT_OK || data == null) { status.text = "Capture permission not granted."; return }
        status.text = "Recording in background for 10 seconds. Return to ReVanced."
        val service = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.START_ACTION
            putExtra("resultCode", resultCode)
            putExtra("resultData", data)
        }
        startForegroundService(service)
    }
    private fun playLast() {
        val file = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "revanced-capture.wav")
        if (!file.exists() || file.length() <= 44) { status.text = "No audio sample yet."; return }
        try {
            player?.release()
            player = MediaPlayer().apply { setDataSource(file.absolutePath); prepare(); start() }
            status.text = "Playing ${file.length() / 1024} KB recording."
        } catch (e: Exception) { status.text = "Playback failed: ${e.message}" }
    }
    override fun onDestroy() { player?.release(); super.onDestroy() }
}
