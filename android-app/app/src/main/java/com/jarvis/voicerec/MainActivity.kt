package com.jarvis.voicerec

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.jarvis.voicerec.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Settings + status screen:
 *  - server URL field with Save
 *  - Test Connection -> GET /health
 *  - manual Record / Stop button
 *  - live list of upload work states
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val requestPerms = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* result surfaced when the user records */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.serverUrl.setText(Prefs.serverUrl(this))

        b.saveUrl.setOnClickListener {
            Prefs.setServerUrl(this, b.serverUrl.text.toString())
            toast("Saved")
        }

        b.testConnection.setOnClickListener { testConnection() }

        b.recordButton.setOnClickListener {
            ensurePermissions()
            val svc = Intent(this, RecordService::class.java)
                .setAction(RecordService.ACTION_TOGGLE)
            startForegroundService(svc)
        }

        // --- voice assistant ---
        b.assistantButton.setOnClickListener {
            ensurePermissions()
            startActivity(
                Intent(this, AssistantActivity::class.java)
                    .setAction("com.jarvis.voicerec.ASSISTANT")
            )
        }

        b.picovoiceKey.setText(Prefs.picovoiceKey(this))
        b.wakeToggle.isChecked = Prefs.wakeEnabled(this)

        b.saveWake.setOnClickListener {
            Prefs.setPicovoiceKey(this, b.picovoiceKey.text.toString())
            toast("Key saved")
        }

        b.wakeToggle.setOnCheckedChangeListener { _, on ->
            Prefs.setWakeEnabled(this, on)
            ensurePermissions()
            val intent = Intent(this, WakeService::class.java).setAction(
                if (on) WakeService.ACTION_START else WakeService.ACTION_STOP
            )
            if (on) startForegroundService(intent) else startService(intent)
            toast(if (on) "Wake word on" else "Wake word off")
        }

        observeUploads()
    }

    private fun ensurePermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPerms.launch(missing.toTypedArray())
    }

    private fun testConnection() {
        val url = b.serverUrl.text.toString().trim().trimEnd('/')
        b.status.text = "Testing $url/health ..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(8, TimeUnit.SECONDS)
                        .readTimeout(8, TimeUnit.SECONDS)
                        .build()
                    client.newCall(Request.Builder().url("$url/health").build())
                        .execute().use { r -> "HTTP ${r.code}: ${r.body?.string()?.take(80)}" }
                } catch (e: Exception) {
                    "FAILED: ${e.message}"
                }
            }
            b.status.text = result
        }
    }

    private fun observeUploads() {
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData("jarvis-upload")
            .observe(this) { infos ->
                b.uploads.text = formatUploads(infos)
            }
    }

    private fun formatUploads(infos: List<WorkInfo>): String {
        if (infos.isEmpty()) return "No uploads yet."
        return infos
            .sortedByDescending { it.state.ordinal }
            .take(20)
            .joinToString("\n") { "• ${it.state}" }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
