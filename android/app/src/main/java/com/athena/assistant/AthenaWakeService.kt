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

/**
 * Background wake listener for Athena.
 *
 * This is a microphone foreground service, so Android shows a persistent
 * notification while the listener is active. Speech recognition is requested
 * in offline-preferred mode and only the wake phrase is acted on here.
 * The full Athena UI/local LLM handles the command after wake-up.
 */
class AthenaWakeService : Service() {
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var restarting = false
    private var paused = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            paused = true
            recognizer?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PAUSE) {
            paused = true
            listening = false
            recognizer?.cancel()
            return START_STICKY
        }
        if (intent?.action == ACTION_RESUME) {
            paused = false
            startWakeListening()
            return START_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        startWakeListening()
        return START_STICKY
    }

    private fun startWakeListening() {
        if (paused) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { listening = true }
                override fun onBeginningOfSpeech() { }
                override fun onRmsChanged(rmsdB: Float) { }
                override fun onBufferReceived(buffer: ByteArray?) { }
                override fun onEndOfSpeech() { listening = false }
                override fun onError(error: Int) { listening = false; restartSoon() }
                override fun onResults(results: Bundle?) {
                    listening = false
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty().trim()
                    val lower = spoken.lowercase(Locale.UK)
                    if (lower == "athena" || lower.startsWith("athena ") || lower.startsWith("athena,") || lower.startsWith("athena.")) {
                        val clean = stripWakeWord(spoken)
                        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 35)
                            .startTone(ToneGenerator.TONE_PROP_BEEP, 45)
                        val open = Intent(this@AthenaWakeService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            putExtra(MainActivity.EXTRA_WAKE_COMMAND, clean)
                        }
                        paused = true
                        recognizer?.cancel()
                        startActivity(open)
                        return
                    }
                    restartSoon()
                }
                override fun onPartialResults(partialResults: Bundle?) { }
                override fun onEvent(eventType: Int, params: Bundle?) { }
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.UK.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            recognizer?.startListening(intent)
            listening = true
        } catch (_: Exception) {
            restartSoon()
        }
    }

    private fun stripWakeWord(spoken:String):String{
        val lower=spoken.lowercase(Locale.UK)
        if(lower=="athena") return ""
        var index=6
        while(index<spoken.length && spoken[index].isWhitespace()) index++
        if(index<spoken.length && spoken[index] in charArrayOf(',', '.', ':', ';', '-', '!', '?')) index++
        while(index<spoken.length && spoken[index].isWhitespace()) index++
        return spoken.substring(index).trim()
    }

    private val handler = android.os.Handler(mainLooper)

    private fun restartSoon() {
        if (restarting || paused) return
        restarting = true
        handler.postDelayed({
            restarting = false
            if (!paused && !listening) startWakeListening()
        }, 900)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Athena background listening",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Athena's background wake listener active."
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.athena_icon)
            .setContentTitle("Athena is listening")
            .setContentText("Say “Athena” whenever you need her")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent)
            .build()
    }

    override fun onDestroy() {
        listening = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.athena.assistant.STOP_WAKE"
        const val ACTION_PAUSE = "com.athena.assistant.PAUSE_WAKE"
        const val ACTION_RESUME = "com.athena.assistant.RESUME_WAKE"
        const val CHANNEL_ID = "athena_wake"
        const val NOTIFICATION_ID = 49321
    }
}
