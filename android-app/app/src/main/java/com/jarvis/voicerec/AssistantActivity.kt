package com.jarvis.voicerec

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.voicerec.databinding.ActivityAssistantBinding
import org.json.JSONObject

/**
 * Full-screen assistant UI. Shows the current state (Listening / Thinking /
 * Speaking), a rolling transcript, and pops up images (charts, visual answers)
 * the assistant sends. It starts AssistantService and listens to its local
 * broadcasts. Works over the lock screen.
 */
class AssistantActivity : AppCompatActivity() {

    private lateinit var b: ActivityAssistantBinding
    private val transcript = StringBuilder()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val json = intent?.getStringExtra(AssistantService.EXTRA_JSON) ?: return
            handle(json)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        b = ActivityAssistantBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.endButton.setOnClickListener { endConversation() }
        b.imageOverlayContainer.setOnClickListener {
            b.imageOverlayContainer.visibility = android.view.View.GONE
        }

        b.stateText.text = "Starting..."
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(AssistantService.BROADCAST)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        // Start the assistant service unless it is already running: a second
        // ACTION_START would open another WebSocket and so a second Gemini
        // session, which resets the conversation mid-turn.
        if (!AssistantService.isRunning) {
            startForegroundService(
                Intent(this, AssistantService::class.java)
                    .setAction(AssistantService.ACTION_START)
            )
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    private fun handle(json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        when (o.optString("type")) {
            "state" -> b.stateText.text = stateLabel(o.optString("state"))
            "transcript" -> {
                val who = o.optString("who")
                val text = o.optString("text")
                if (text.isNotBlank()) {
                    transcript.append(if (who == "you") "\nYou: " else "\nJarvis: ").append(text)
                    b.transcriptText.text = transcript.toString().trim().takeLast(2000)
                }
            }
            "show_image" -> showImage(o.optString("png_b64"), o.optString("caption"))
            "error" -> b.stateText.text = "Error: ${o.optString("message")}"
        }
    }

    private fun showImage(b64: String, caption: String) {
        if (b64.isBlank()) return
        try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            b.imageOverlay.setImageBitmap(bmp)
            b.imageOverlay.visibility = android.view.View.VISIBLE
            b.imageOverlayContainer.visibility = android.view.View.VISIBLE
            b.imageCaption.text = caption
            b.imageCaption.visibility =
                if (caption.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        } catch (_: Exception) {
        }
    }

    private fun stateLabel(s: String): String = when (s) {
        "connecting" -> "Connecting..."
        "listening" -> "Listening"
        "thinking" -> "Thinking..."
        "speaking" -> "Speaking"
        "error" -> "Error"
        "closed" -> "Ended"
        else -> s
    }

    private fun endConversation() {
        startForegroundService(
            Intent(this, AssistantService::class.java)
                .setAction(AssistantService.ACTION_STOP)
        )
        finish()
    }
}
