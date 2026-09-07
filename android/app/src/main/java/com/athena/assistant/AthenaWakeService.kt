package com.athena.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class AthenaWakeService : Service() {
    private var foregroundReady = false
    private var recognizer: SpeechRecognizer? = null
    private var paused = false
    private var restarting = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION_ID, notification())
            foregroundReady = true
        } catch (_: Throwable) {
            foregroundReady = false
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP -> { paused = true; stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE -> { paused = true; recognizer?.cancel(); return START_STICKY }
            ACTION_RESUME -> { paused = false; startWakeListening(); return START_STICKY }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return START_NOT_STICKY }
        paused = false
        startWakeListening()
        return START_STICKY
    }

    private fun startWakeListening() {
        if (paused || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        if (recognizer == null) {
            try { recognizer = SpeechRecognizer.createSpeechRecognizer(this) } catch (_: Throwable) { return }
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { restartSoon() }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onResults(results: Bundle?) {
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                    val lower = spoken.lowercase(Locale.UK)
                    val wakeDetected = lower == "athena" || lower.contains("athena ") || lower.contains(" athena") || lower.startsWith("athena,") || lower.startsWith("athena.") || lower == "a then a" || lower.startsWith("a then a ")
                    if (wakeDetected) {
                        val command = stripWakeWord(spoken)
                        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 35).startTone(ToneGenerator.TONE_PROP_BEEP, 45)
                        paused = true
                        try { recognizer?.cancel() } catch (_: Exception) {}
                        val open = Intent(this@AthenaWakeService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            putExtra(MainActivity.EXTRA_WAKE_COMMAND, command)
                            putExtra(MainActivity.EXTRA_WAKE_ONLY, command.isBlank())
                        }
                        try { startActivity(open) } catch (_: Throwable) {
                            // Android may block background activity launches. The notification remains available.
                        }
                    } else restartSoon()
                }
            })
        }
        val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.UK.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try { recognizer?.startListening(speechIntent) } catch (_: Throwable) { restartSoon() }
    }

    private fun restartSoon() {
        if (restarting || paused) return
        restarting = true
        handler.postDelayed({ restarting = false; startWakeListening() }, 900)
    }

    private fun stripWakeWord(spoken: String): String {
        if (spoken.equals("athena", true)) return ""
        var i = 6
        while (i < spoken.length && spoken[i].isWhitespace()) i++
        if (i < spoken.length && spoken[i] in charArrayOf(',', '.', ':', ';', '-', '!', '?')) i++
        while (i < spoken.length && spoken[i].isWhitespace()) i++
        return spoken.substring(i).trim()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Athena background listening", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification {
        val pi = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.athena_icon)
            .setContentTitle("Athena is listening")
            .setContentText("Say “Athena” whenever you need her")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() { try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}; recognizer = null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.athena.assistant.STOP_WAKE"
        const val ACTION_PAUSE = "com.athena.assistant.PAUSE_WAKE"
        const val ACTION_RESUME = "com.athena.assistant.RESUME_WAKE"
        const val CHANNEL_ID = "athena_wake"
        const val NOTIFICATION_ID = 49321
    }
}
