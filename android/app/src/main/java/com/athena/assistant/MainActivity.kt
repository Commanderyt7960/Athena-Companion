package com.athena.assistant

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    companion object {
        const val EXTRA_WAKE_COMMAND = "athena_wake_command"
        private const val REQ_MIC = 100
        private const val REQ_NOTIFY = 101
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null
    private var wakeMode = false
    private val io = Executors.newCachedThreadPool()
    private var llm: LlmInference? = null
    private lateinit var status: TextView
    private lateinit var chat: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private var permissionDialog: AlertDialog? = null
    private var listeningForCommand = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("athena_local", Context.MODE_PRIVATE)
        tts = TextToSpeech(this, this)
        buildUi()
        loadModelIfPresent()
        handleWakeIntent(intent)
        ensureMicrophonePermission()
    }

    override fun onResume() {
        super.onResume()
        // Never finish the Activity because of microphone permission state.
        if (!hasMicPermission()) {
            window.decorView.postDelayed({ ensureMicrophonePermission() }, 350)
        }
    }

    private fun hasMicPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensureMicrophonePermission() {
        if (isFinishing || isDestroyed) return
        if (hasMicPermission()) {
            status.text = "Microphone ready"
            requestNotificationPermissionIfNeeded()
            return
        }
        if (permissionDialog?.isShowing == true) return
        permissionDialog = AlertDialog.Builder(this)
            .setTitle("Always-on microphone")
            .setMessage("Athena needs microphone access so she can hear the wake phrase “Athena”.\n\nChoose “While using the app” on the Android permission screen.\n\nIf you choose another option, Athena will stay open and ask again.")
            .setCancelable(false)
            .setPositiveButton("CONTINUE") { _, _ ->
                permissionDialog = null
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            }
            .setNegativeButton("TRY LATER") { _, _ ->
                permissionDialog = null
                window.decorView.postDelayed({ ensureMicrophonePermission() }, 700)
            }
            .create()
        permissionDialog?.setOnDismissListener { permissionDialog = null }
        permissionDialog?.show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_MIC -> {
                if (hasMicPermission()) {
                    status.text = "Microphone ready"
                    requestNotificationPermissionIfNeeded()
                } else {
                    status.text = "Microphone permission needed"
                    window.decorView.postDelayed({ ensureMicrophonePermission() }, 450)
                }
            }
            REQ_NOTIFY -> {
                // Notifications are useful for the foreground listener, but never block the app.
                if (hasMicPermission()) status.text = "Microphone ready"
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 16)
            setBackgroundColor(Color.rgb(7, 8, 13))
        }
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 18)
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.athena_icon)
            layoutParams = LinearLayout.LayoutParams(92, 92).apply { bottomMargin = 10 }
        }
        val title = TextView(this).apply {
            text = "ATHENA"
            textSize = 30f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(244, 241, 255))
            letterSpacing = 0.16f
        }
        val welcome = TextView(this).apply {
            text = "What can I do for you?"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(180, 176, 198))
        }
        hero.addView(icon); hero.addView(title); hero.addView(welcome)

        status = TextView(this).apply {
            text = "Starting Athena…"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(150, 222, 188))
            setPadding(0, 0, 0, 10)
        }
        chat = TextView(this).apply {
            text = "Athena is starting…"
            textSize = 16f
            setTextColor(Color.rgb(232, 230, 239))
            setPadding(18, 18, 18, 18)
            setBackgroundResource(R.drawable.athena_panel)
        }
        scroll = ScrollView(this).apply { addView(chat) }
        input = EditText(this).apply {
            hint = "Ask Athena anything…"
            textSize = 16f
            setSingleLine(true)
            setTextColor(Color.rgb(239, 237, 246))
            setHintTextColor(Color.rgb(118, 115, 133))
            setPadding(18, 14, 18, 14)
            setBackgroundResource(R.drawable.athena_input)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND || event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                    val text = text.toString().trim()
                    if (text.isNotEmpty()) { setText(""); askAthena(text) }
                    true
                } else false
            }
        }
        val tools = Button(this).apply {
            text = "☰  TOOLS"
            textSize = 15f
            setTextColor(Color.rgb(235, 231, 255))
            setBackgroundResource(R.drawable.athena_tools)
            setOnClickListener { showTools() }
        }
        root.addView(hero)
        root.addView(status)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(input, LinearLayout.LayoutParams(-1, 64).apply { topMargin = 12; bottomMargin = 10 })
        root.addView(tools, LinearLayout.LayoutParams(-1, 54))
        setContentView(root)
    }

    private fun showTools() {
        val items = arrayOf(
            "🎙  Talk to Athena",
            "✨  Always-on microphone",
            "🖥  Connect to my PC",
            "📱  Give Athena device access",
            "🧠  Memories",
            "🧹  Clear conversation",
            "ℹ  About Athena"
        )
        AlertDialog.Builder(this).setTitle("Tools").setItems(items) { _, which ->
            when (which) {
                0 -> startListening(false)
                1 -> enableAlwaysOn()
                2 -> showPcDialog()
                3 -> showDeviceAccess()
                4 -> showMemory()
                5 -> { chat.text = ""; Toast.makeText(this, "Conversation cleared.", Toast.LENGTH_SHORT).show() }
                6 -> showAboutAthena()
            }
        }.setNegativeButton("Done", null).show()
    }

    private fun enableAlwaysOn() {
        if (!hasMicPermission()) { ensureMicrophonePermission(); return }
        prefs.edit().putBoolean("background_wake", true).apply()
        requestNotificationPermissionIfNeeded()
        try {
            val intent = Intent(this, AthenaWakeService::class.java)
            ContextCompat.startForegroundService(this, intent)
            status.text = "Listening for “Athena”…"
            Toast.makeText(this, "Always-on microphone enabled.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            status.text = "Could not start listening"
            Toast.makeText(this, "Android stopped the background listener. Athena is still open.", Toast.LENGTH_LONG).show()
        }
    }

    private fun disableAlwaysOn() {
        prefs.edit().putBoolean("background_wake", false).apply()
        try { stopService(Intent(this, AthenaWakeService::class.java)) } catch (_: Exception) { }
        status.text = if (hasMicPermission()) "Microphone ready" else "Microphone permission needed"
    }

    private fun showDeviceAccess() {
        val controlsReady = AthenaAccessibilityService.instance != null
        val message = "Microphone: ${if (hasMicPermission()) "Ready" else "Please allow"}\nDevice controls: ${if (controlsReady) "Ready" else "Please enable"}"
        AlertDialog.Builder(this).setTitle("Give Athena access").setMessage(message)
            .setPositiveButton("OPEN DEVICE CONTROLS") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNegativeButton("DONE", null).show()
    }

    private fun showAboutAthena() {
        AlertDialog.Builder(this).setTitle("About Athena")
            .setMessage("Athena is a private assistant with a local conversation engine. Your conversations are kept on your devices by the app and do not require an AI account.")
            .setPositiveButton("Done", null).show()
    }

    private fun startListening(wake: Boolean) {
        if (!hasMicPermission()) { ensureMicrophonePermission(); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { status.text = "Speech recognition unavailable"; return }
        wakeMode = wake
        try { recognizer?.cancel() } catch (_: Exception) { }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status.text = if (wakeMode) "Waiting for “Athena”…" else "Listening…" }
                override fun onBeginningOfSpeech() { status.text = "Hearing you…" }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { status.text = "Processing…" }
                override fun onError(error: Int) { status.text = "Ready"; if (wakeMode) postWakeListen() }
                override fun onResults(results: Bundle?) {
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (spoken.isNotBlank()) handleVoice(spoken, wakeMode)
                    if (wakeMode) postWakeListen()
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.UK.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try { recognizer?.startListening(speechIntent) } catch (_: Exception) { status.text = "Ready" }
    }

    private fun postWakeListen() {
        window.decorView.postDelayed({ if (wakeMode && !isFinishing && hasMicPermission()) startListening(true) }, 700)
    }

    private fun handleVoice(raw: String, wake: Boolean) {
        val spoken = raw.trim()
        if (wake) {
            val lower = spoken.lowercase(Locale.UK)
            if (!(lower == "athena" || lower.startsWith("athena ") || lower.startsWith("athena,") || lower.startsWith("athena."))) return
        }
        val clean = if (wake) stripWakeWord(spoken) else spoken
        if (clean.isBlank()) {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 40).startTone(ToneGenerator.TONE_PROP_BEEP, 70)
            return
        }
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 35).startTone(ToneGenerator.TONE_PROP_BEEP, 45)
        askAthena(clean)
    }

    private fun stripWakeWord(spoken: String): String {
        if (spoken.equals("athena", true)) return ""
        var index = 6
        while (index < spoken.length && spoken[index].isWhitespace()) index++
        if (index < spoken.length && spoken[index] in charArrayOf(',', '.', ':', ';', '-', '!', '?')) index++
        while (index < spoken.length && spoken[index].isWhitespace()) index++
        return spoken.substring(index).trim()
    }

    private fun handleWakeIntent(intent: Intent?) {
        val command = intent?.getStringExtra(EXTRA_WAKE_COMMAND)?.trim().orEmpty()
        if (command.isNotBlank()) {
            try { startService(Intent(this, AthenaWakeService::class.java).setAction(AthenaWakeService.ACTION_PAUSE)) } catch (_: Exception) { }
            window.decorView.postDelayed({ askAthena(command) }, 250)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    private fun askAthena(text: String) {
        append("You: $text")
        saveMemory("user", text)
        status.text = "One moment…"
        val local = handleLocalCommand(text)
        if (local != null) { finishReply(local); return }
        val engine = llm
        if (engine == null) { finishReply("I’m still getting ready. Please try again in a moment."); return }
        io.execute {
            try {
                val options = LlmInferenceSession.LlmInferenceSessionOptions.builder().setTopK(40).setTopP(0.95f).setTemperature(0.7f).build()
                val session = LlmInferenceSession.createFromOptions(engine, options)
                session.addQueryChunk(buildPrompt(text))
                val result = cleanAiOutput(session.generateResponse())
                session.close()
                runOnUiThread { finishReply(if (result.isBlank()) "I could not generate an answer." else result) }
            } catch (_: Exception) {
                runOnUiThread { finishReply("I couldn’t work that out just now. Please try again.") }
            }
        }
    }

    private fun cleanAiOutput(raw: String): String = raw.replace("<start_of_turn>model", "", true).replace("<end_of_turn>", "", true).trim()

    private fun buildPrompt(text: String): String = "<start_of_turn>user\nYou are Athena. Reply naturally and directly. Do not mention being an AI unless asked. Keep normal answers concise but useful.\n\nConversation context:\n${recentHistory()}\nUser message: $text\n<end_of_turn>\n<start_of_turn>model\n"

    private fun handleLocalCommand(text: String): String? {
        val l = text.lowercase(Locale.UK).trim()
        val a = AthenaAccessibilityService.instance
        if (l in listOf("hello", "hi", "hey")) return "Hello. Athena is ready."
        if (l == "time" || l.contains("what time")) return "It is ${SimpleDateFormat("HH:mm", Locale.UK).format(Date())}."
        if (l == "date" || l.contains("what date") || l.contains("what day is it")) return SimpleDateFormat("EEEE, d MMMM yyyy", Locale.UK).format(Date()) + "."
        if (l.startsWith("remember ")) { val x = text.substring(9).trim(); if (x.isNotEmpty()) { saveMemory("memory", x); return "I'll remember that locally." } }
        if (l == "show memory" || l == "what do you remember") { showMemory(); return null }
        if (l in listOf("go home", "home", "press home")) return if (a == null) "Please give Athena device access in Tools first." else if (a.home()) "Done." else "Android rejected the Home action."
        if (l in listOf("go back", "back", "press back")) return if (a == null) "Please give Athena device access in Tools first." else if (a.back()) "Done." else "Android rejected the Back action."
        if (l in listOf("show recent apps", "open recents", "recents")) return if (a == null) "Please give Athena device access in Tools first." else if (a.recents()) "Opening recent apps." else "Android rejected the Recents action."
        if (l in listOf("open notifications", "show notifications")) return if (a == null) "Please give Athena device access in Tools first." else if (a.notifications()) "Opening notifications." else "Android rejected the action."
        if (l in listOf("quick settings", "open quick settings")) return if (a == null) "Please give Athena device access in Tools first." else if (a.quickSettings()) "Opening quick settings." else "Android rejected the action."
        if (l in listOf("lock phone", "lock screen")) return if (a == null) "Please give Athena device access in Tools first." else if (a.lockScreen()) "Locking the phone." else "Android rejected the lock action."
        if (l in listOf("take screenshot", "screenshot")) return if (a == null) "Please give Athena device access in Tools first." else if (a.screenshot()) "Taking a screenshot." else "Android rejected the screenshot action."
        if (l.startsWith("click ")) return if (a == null) "Please give Athena device access in Tools first." else { val q = text.substring(6).trim(); if (a.clickText(q)) "Clicked $q." else "I couldn't find a visible control named $q." }
        if (l.startsWith("type ")) return if (a == null) "Please give Athena device access in Tools first." else if (a.typeText(text.substring(5))) "Typed it into the focused field." else "I couldn't type into the current field."
        if (l.startsWith("open ")) { val target = text.substring(5).trim(); val url = when (target.lowercase(Locale.UK)) { "youtube" -> "https://www.youtube.com"; "google" -> "https://www.google.com"; "discord" -> "https://discord.com/app"; "spotify" -> "https://open.spotify.com"; "gmail" -> "https://mail.google.com"; else -> if (target.startsWith("http")) target else "https://www.google.com/search?q=" + Uri.encode(target) }; startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); return "Opening $target." }
        if (l == "connect to pc" || l == "pc status") { sendPc("status") { r -> runOnUiThread { finishReply(r) } }; return "Checking the PC." }
        if (l.startsWith("pc ")) { sendPc(text.substring(3)) { r -> runOnUiThread { finishReply(r) } }; return "Sending that to the PC." }
        return null
    }

    private fun finishReply(reply: String) { append("Athena: $reply"); saveMemory("athena", reply); speak(reply); status.text = "Ready" }

    private fun speak(text: String) { try { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "athena") } catch (_: Exception) {} }

    private fun sendPc(command: String, done: (String) -> Unit) {
        val host = prefs.getString("pc_host", "") ?: ""
        val token = prefs.getString("pc_token", "") ?: ""
        if (host.isBlank() || token.isBlank()) { done("Your PC isn’t connected yet. Open Tools and choose Connect to my PC."); return }
        io.execute {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, prefs.getInt("pc_port", 49321)), 2500)
                    socket.soTimeout = 5000
                    val req = JSONObject().put("token", token).put("command", command).toString() + "\n"
                    socket.getOutputStream().write(req.toByteArray(Charsets.UTF_8)); socket.getOutputStream().flush()
                    done(JSONObject(socket.getInputStream().bufferedReader().readLine() ?: "{}").optString("reply", "No response from PC."))
                }
            } catch (_: Exception) { done("I couldn’t reach your PC. Make sure Athena is open there and both devices are on the same Wi‑Fi.") }
        }
    }

    private fun showPcDialog() {
        AlertDialog.Builder(this).setTitle("Connect to your PC")
            .setMessage("Make sure Athena is open on your Windows PC and both devices are on the same Wi‑Fi. Athena will find it for you — nothing to type.")
            .setNegativeButton("DONE", null)
            .setPositiveButton("FIND MY PC") { _, _ -> discoverPc() }.show()
    }

    private fun discoverPc() {
        Toast.makeText(this, "Looking for your PC…", Toast.LENGTH_SHORT).show()
        io.execute {
            var foundHost = ""; var foundToken = ""
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true; socket.soTimeout = 3000
                    val bytes = "ATHENA_DISCOVER".toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), 49322))
                    val buf = ByteArray(4096); val packet = DatagramPacket(buf, buf.size); socket.receive(packet)
                    val obj = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                    if (obj.optString("service") == "athena" && obj.optString("token").isNotBlank()) { foundHost = packet.address.hostAddress ?: ""; foundToken = obj.optString("token") }
                }
            } catch (_: Exception) {}
            runOnUiThread {
                if (foundHost.isNotBlank()) { prefs.edit().putString("pc_host", foundHost).putString("pc_token", foundToken).apply(); Toast.makeText(this, "Your PC is connected.", Toast.LENGTH_LONG).show() }
                else Toast.makeText(this, "I couldn't find Athena on your PC.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun append(s: String) { chat.append("\n$s\n"); scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
    private fun saveMemory(role: String, text: String) { val arr = try { JSONArray(prefs.getString("memory", "[]")) } catch (_: Exception) { JSONArray() }; arr.put(JSONObject().put("role", role).put("text", text).put("time", System.currentTimeMillis())); while (arr.length() > 500) arr.remove(0); prefs.edit().putString("memory", arr.toString()).apply() }
    private fun recentHistory(): String { val arr = try { JSONArray(prefs.getString("memory", "[]")) } catch (_: Exception) { JSONArray() }; val start = maxOf(0, arr.length() - 6); val out = StringBuilder(); for (i in start until arr.length()) { val o = arr.optJSONObject(i) ?: continue; out.append(o.optString("role")).append(": ").append(o.optString("text").takeLast(350)).append('\n') }; return out.toString() }
    private fun showMemory() { val arr = try { JSONArray(prefs.getString("memory", "[]")) } catch (_: Exception) { JSONArray() }; val out = StringBuilder("LOCAL MEMORY\n\n"); for (i in maxOf(0, arr.length() - 30) until arr.length()) { val o = arr.optJSONObject(i); out.append(o?.optString("role")).append(": ").append(o?.optString("text")).append('\n') }; chat.text = out.toString(); scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }

    private fun loadModelIfPresent() {
        io.execute {
            try {
                val dir = File(filesDir, "models").apply { mkdirs() }
                val f = File(dir, "athena.task")
                if (!f.exists()) assets.open("athena.task").use { input -> FileOutputStream(f).use { output -> input.copyTo(output) } }
                val options = LlmInference.LlmInferenceOptions.builder().setModelPath(f.absolutePath).setMaxTokens(512).build()
                llm = LlmInference.createFromOptions(this, options)
                runOnUiThread { chat.text = "Athena is ready.\n\nAsk me anything."; status.text = if (hasMicPermission()) "Microphone ready" else "Microphone permission needed" }
            } catch (_: Exception) {
                runOnUiThread { status.text = "Conversation engine unavailable"; chat.text = "Athena is open, but her local conversation engine could not start." }
            }
        }
    }

    override fun onInit(result: Int) { if (result == TextToSpeech.SUCCESS) tts.language = Locale.UK }

    override fun onDestroy() {
        wakeMode = false
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        try { tts.shutdown() } catch (_: Exception) {}
        io.shutdownNow()
        try { llm?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
