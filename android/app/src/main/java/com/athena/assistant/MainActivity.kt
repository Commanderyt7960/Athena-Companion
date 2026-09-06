package com.athena.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var prefs: SharedPreferences
    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null
    private var wakeMode = false
    private val io = Executors.newSingleThreadExecutor()
    private var llm: LlmInference? = null
    private var session: LlmInferenceSession? = null
    private lateinit var status: TextView
    private lateinit var chat: TextView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("athena_local", Context.MODE_PRIVATE)
        tts = TextToSpeech(this, this)
        buildUi()
        requestMic()
        loadModelIfPresent()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 24)
            setBackgroundColor(0xFF080A10.toInt())
        }
        val title = TextView(this).apply {
            text = "ATHENA"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(0xFFEDEBFF.toInt())
        }
        val sub = TextView(this).apply {
            text = "Private • Local • Offline capable"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFFA9A5BD.toInt())
            setPadding(0, 0, 0, 18)
        }
        status = TextView(this).apply {
            text = "Starting…"
            setTextColor(0xFF9FE7C4.toInt())
            setPadding(0, 4, 0, 10)
        }
        chat = TextView(this).apply {
            text = "Athena is ready.\n"
            textSize = 17f
            setTextColor(0xFFE8E6EF.toInt())
            setPadding(0, 12, 0, 12)
        }
        val scroll = ScrollView(this).apply { addView(chat) }
        input = EditText(this).apply {
            hint = "Talk to Athena…"
            setSingleLine(false)
            setTextColor(0xFFE8E6EF.toInt())
            setHintTextColor(0xFF777487.toInt())
        }
        val send = Button(this).apply {
            text = "SEND"
            setOnClickListener { val s = input.text.toString().trim(); if (s.isNotEmpty()) { input.setText(""); askAthena(s) } }
        }
        val talk = Button(this).apply {
            text = "🎙 TALK"
            setOnClickListener { startListening(false) }
        }
        val wake = Button(this).apply {
            text = "ATHENA WAKE LISTEN"
            setOnClickListener {
                wakeMode = !wakeMode
                text = if (wakeMode) "STOP WAKE LISTEN" else "ATHENA WAKE LISTEN"
                if (wakeMode) startListening(true) else recognizer?.cancel()
            }
        }
        val model = Button(this).apply {
            text = "IMPORT LOCAL AI MODEL"
            setOnClickListener { chooseModel() }
        }
        val memory = Button(this).apply {
            text = "SHOW MEMORY"
            setOnClickListener { showMemory() }
        }
        root.addView(title); root.addView(sub); root.addView(status)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(input, LinearLayout.LayoutParams(-1, 90))
        root.addView(send); root.addView(talk); root.addView(wake); root.addView(model); root.addView(memory)
        setContentView(root)
    }

    private fun requestMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 10)
    }

    private fun startListening(wake: Boolean) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { status.text = "Speech recognition unavailable"; return }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer!!.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { status.text = "Listening…" }
                override fun onBeginningOfSpeech() { status.text = "Hearing you…" }
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() { status.text = "Processing…" }
                override fun onError(e: Int) { status.text = "Ready"; if (wakeMode) postListen() }
                override fun onResults(r: Bundle?) {
                    val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) handleVoice(text, wake)
                    if (wakeMode) postListen()
                }
                override fun onPartialResults(r: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.UK.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        recognizer!!.startListening(i)
    }

    private fun postListen() { window.decorView.postDelayed({ if (wakeMode) startListening(true) }, 350) }

    private fun handleVoice(raw: String, wake: Boolean) {
        val lower = raw.lowercase(Locale.UK).trim()
        if (wake && !lower.startsWith("athena")) return
        val clean = if (lower.startsWith("athena")) raw.substring(6).trim(' ', ',', '.', ':') else raw
        if (clean.isBlank()) {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 45).startTone(ToneGenerator.TONE_PROP_BEEP, 80)
            status.text = "Listening for command…"
            return
        }
        askAthena(clean)
    }

    private fun askAthena(text: String) {
        append("You: $text")
        saveMemory("user", text)
        status.text = "Thinking locally…"
        val s = session
        if (s == null) {
            val reply = localFallback(text)
            append("Athena: $reply")
            saveMemory("athena", reply)
            speak(reply)
            status.text = "Local AI model not installed"
            return
        }
        io.execute {
            try {
                val result = s.generateResponse("You are Athena, a private helpful Android assistant. Answer concisely. User: $text")
                runOnUiThread {
                    append("Athena: $result")
                    saveMemory("athena", result)
                    speak(result)
                    status.text = "Ready"
                }
            } catch (e: Exception) {
                val reply = localFallback(text)
                runOnUiThread { append("Athena: $reply"); saveMemory("athena", reply); speak(reply); status.text = "AI error — fallback used" }
            }
        }
    }

    private fun localFallback(text: String): String {
        val l = text.lowercase(Locale.UK)
        return when {
            l.contains("hello") || l == "hi" -> "Hello. I'm Athena, and I'm running locally on this device."
            l.contains("remember") -> "I can store that locally in Athena memory."
            l.contains("time") -> java.text.SimpleDateFormat("HH:mm", Locale.UK).format(java.util.Date()).let { "It is $it." }
            else -> "I heard you. Install a compatible local AI model to enable full offline AI answers."
        }
    }

    private fun append(s: String) { chat.append("\n$s\n") }

    private fun saveMemory(role: String, text: String) {
        val arr = try { JSONArray(prefs.getString("memory", "[]")) } catch (_: Exception) { JSONArray() }
        arr.put(org.json.JSONObject().put("role", role).put("text", text).put("time", System.currentTimeMillis()))
        while (arr.length() > 500) arr.remove(0)
        prefs.edit().putString("memory", arr.toString()).apply()
    }

    private fun showMemory() {
        val arr = try { JSONArray(prefs.getString("memory", "[]")) } catch (_: Exception) { JSONArray() }
        val out = StringBuilder("Local memory:\n")
        val start = maxOf(0, arr.length() - 20)
        for (i in start until arr.length()) { val o = arr.optJSONObject(i); out.append(o?.optString("role")); out.append(": "); out.append(o?.optString("text")); out.append('\n') }
        chat.text = out.toString()
    }

    private fun chooseModel() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "application/octet-stream"; addCategory(Intent.CATEGORY_OPENABLE) }
        startActivityForResult(intent, 42)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 42 || resultCode != Activity.RESULT_OK) return
        val uri: Uri = data?.data ?: return
        io.execute {
            try {
                val dir = File(filesDir, "models").apply { mkdirs() }
                val target = File(dir, "athena.task")
                contentResolver.openInputStream(uri)!!.use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
                runOnUiThread { loadModelIfPresent(); status.text = "Local AI model installed" }
            } catch (e: Exception) { runOnUiThread { status.text = "Model import failed" } }
        }
    }

    private fun loadModelIfPresent() {
        val f = File(filesDir, "models/athena.task")
        if (!f.exists()) { status.text = "Ready — local AI model not installed"; return }
        io.execute {
            try {
                val options = LlmInference.LlmInferenceOptions.builder().setModelPath(f.absolutePath).setMaxTokens(512).build()
                val inference = LlmInference.createFromOptions(this, options)
                val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder().setTopK(40).setTopP(0.95f).setTemperature(0.7f).build()
                llm = inference
                session = LlmInferenceSession.createFromOptions(inference, sessionOptions)
                runOnUiThread { status.text = "Local AI ready" }
            } catch (e: Exception) { runOnUiThread { status.text = "Model incompatible with this device/app" } }
        }
    }

    override fun onInit(result: Int) { if (result == TextToSpeech.SUCCESS) tts.language = Locale.UK }
    private fun speak(text: String) { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "athena") }

    override fun onDestroy() { recognizer?.destroy(); tts.shutdown(); io.shutdownNow(); super.onDestroy() }
}
