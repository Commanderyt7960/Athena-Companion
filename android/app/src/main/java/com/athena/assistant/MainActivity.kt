package com.athena.assistant

import android.Manifest
import android.app.Activity
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
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    companion object {
        const val EXTRA_WAKE_COMMAND = "athena_wake_command"
        const val REQUEST_MIC = 10
        const val REQUEST_NOTIFICATIONS = 11
    }
    private lateinit var prefs: SharedPreferences
    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null
    private var wakeMode = false
    private val io = Executors.newCachedThreadPool()
    private var llm: LlmInference? = null
    private var session: LlmInferenceSession? = null
    private var backgroundWakeCommandActive = false
    private lateinit var status: TextView
    private lateinit var chat: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("athena_local", Context.MODE_PRIVATE)
        tts = TextToSpeech(this, this)
        buildUi(); requestPermissions(); loadModelIfPresent()
        handleWakeIntent(intent)
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; setOnClickListener { action() } }

    private fun handleWakeIntent(intent: Intent?) {
        val command = intent?.getStringExtra(EXTRA_WAKE_COMMAND)?.trim().orEmpty()
        if (command.isNotBlank()) {
            backgroundWakeCommandActive = true
            startService(Intent(this, AthenaWakeService::class.java).setAction(AthenaWakeService.ACTION_PAUSE))
            window.decorView.postDelayed({ askAthena(command) }, 250)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Do not start the microphone service merely because the activity resumed.
        // Android requires the microphone permission to be granted before a
        // microphone foreground service is created on modern Android versions.
        // The permission callback below starts it after the user explicitly allows it.
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
            setPadding(0, 6, 0, 0)
        }
        hero.addView(icon); hero.addView(title); hero.addView(welcome)

        status = TextView(this).apply {
            text = "Ready"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(150, 222, 188))
            setPadding(0, 0, 0, 10)
        }

        chat = TextView(this).apply {
            text = ""
            textSize = 16f
            setTextColor(Color.rgb(232, 230, 239))
            setPadding(18, 18, 18, 18)
            setBackgroundResource(R.drawable.athena_panel)
        }
        scroll = ScrollView(this).apply { addView(chat) }

        input = EditText(this).apply {
            hint = "Ask Athena anything…"
            textSize = 16f
            setSingleLine(false)
            minLines = 1
            maxLines = 4
            setTextColor(Color.rgb(239, 237, 246))
            setHintTextColor(Color.rgb(118, 115, 133))
            setPadding(18, 14, 18, 14)
            setBackgroundResource(R.drawable.athena_input)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                    (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.isShiftPressed.not())) {
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
            setPadding(16, 0, 16, 0)
            setOnClickListener { showTools() }
        }

        root.addView(hero)
        root.addView(status)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(input, LinearLayout.LayoutParams(-1, 64).apply { topMargin = 12; bottomMargin = 10 })
        root.addView(tools, LinearLayout.LayoutParams(-1, 54))
        setContentView(root)
        chat.text = "Athena is ready."
    }

    private fun showTools(){
        val items=arrayOf(
            "🎙  Talk to Athena",
            "✨  Background listening",
            "🖥  Connect to my PC",
            "📱  Give Athena device access",
            "🧠  Memories",
            "🧹  Clear conversation",
            "ℹ  About Athena"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("Tools")
            .setItems(items){_,which->
                when(which){
                    0->startListening(false)
                    1->showBackgroundWakeInfo()
                    2->showPcDialog()
                    3->showPermissions()
                    4->showMemory()
                    5->{chat.text=""; Toast.makeText(this,"Conversation cleared.",Toast.LENGTH_SHORT).show()}
                    6->showAboutAthena()
                }
            }
            .setNegativeButton("Done",null)
            .show()
    }

    private fun showBackgroundWakeInfo(){
        android.app.AlertDialog.Builder(this)
            .setTitle("Background listening")
            .setMessage("Athena keeps listening for the wake phrase “Athena” while you use other apps. When you say it, Athena wakes and listens to your request.\n\nAndroid shows a small listening notification while this is active. On newer Android versions, microphone background listening must be started while Athena is open; Android does not allow ordinary apps to silently start a microphone service after a reboot.")
            .setPositiveButton("Keep listening",null)
            .setNegativeButton("Done",null)
            .show()
    }

    private fun showAboutAthena(){
        android.app.AlertDialog.Builder(this)
            .setTitle("About Athena")
            .setMessage("Athena is your private assistant. She can answer questions, remember things you choose to save, respond to your voice and carry out the device actions you ask for.\n\nYour assistant is designed to keep its conversations on your devices rather than requiring an online AI account.")
            .setPositiveButton("Done",null)
            .show()
    }

    private fun startBackgroundWakeIfAllowed(){
        if (!prefs.getBoolean("background_wake", true)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        try {
            val serviceIntent = Intent(this, AthenaWakeService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (_: SecurityException) {
            // Never let a foreground-service permission failure close Athena.
            status.text = "Ready"
        } catch (_: IllegalStateException) {
            status.text = "Ready"
        } catch (_: Exception) {
            status.text = "Ready"
        }
    }

    private fun requestPermissions(){
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        requestNotificationPermissionIfNeeded()
        startBackgroundWakeIfAllowed()
    }

    private fun requestNotificationPermissionIfNeeded(){
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode:Int, permissions:Array<out String>, grantResults:IntArray){
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionIfNeeded()
                // Start only after the microphone permission is definitely granted.
                window.decorView.post { startBackgroundWakeIfAllowed() }
            } else {
                status.text = "Microphone access is needed for voice features."
            }
        }
    }

    private fun showLocalAiInfo(){
        android.app.AlertDialog.Builder(this)
            .setTitle("Athena is ready")
            .setMessage("Athena can answer questions and help you with everyday tasks without requiring you to create an AI account.\n\nThe first answer after opening the app may take a little longer while Athena wakes up.")
            .setPositiveButton("DONE",null)
            .show()
    }

    private fun startListening(wake:Boolean){
        if(!SpeechRecognizer.isRecognitionAvailable(this)){status.text="Speech recognition unavailable";return}
        if (!wake) {
            try { recognizer?.cancel() } catch (_: Exception) { }
        }
        if(recognizer==null){recognizer=SpeechRecognizer.createSpeechRecognizer(this);recognizer!!.setRecognitionListener(object:RecognitionListener{
            override fun onReadyForSpeech(p:Bundle?){status.text=if(wake)"Waiting for “Athena”…" else "Listening…"}
            override fun onBeginningOfSpeech(){status.text="Hearing you…"};override fun onRmsChanged(v:Float){};override fun onBufferReceived(b:ByteArray?){ }
            override fun onEndOfSpeech(){status.text="Processing…"}
            override fun onError(e:Int){status.text=if(wakeMode)"Waiting for “Athena”…" else "Ready";if(wakeMode)postListen()}
            override fun onResults(r:Bundle?){val spoken=r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty();if(spoken.isNotBlank())handleVoice(spoken,wake);if(wakeMode)postListen()}
            override fun onPartialResults(r:Bundle?){ };override fun onEvent(t:Int,p:Bundle?){ }
        })}
        val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.UK.toLanguageTag());putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3);putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false)}
        try{recognizer!!.startListening(i)}catch(_:Exception){}
    }
    private fun postListen(){window.decorView.postDelayed({if(wakeMode)startListening(true)},500)}
    private fun handleVoice(raw:String,wake:Boolean){
        val spoken=raw.trim()
        val lower=spoken.lowercase(Locale.UK)
        if(wake && !(lower=="athena" || lower.startsWith("athena ") || lower.startsWith("athena,") || lower.startsWith("athena."))) return
        val clean=if(wake) stripWakeWord(spoken) else spoken
        if(clean.isBlank()){
            ToneGenerator(AudioManager.STREAM_NOTIFICATION,45).startTone(ToneGenerator.TONE_PROP_BEEP,80)
            return
        }
        ToneGenerator(AudioManager.STREAM_NOTIFICATION,35).startTone(ToneGenerator.TONE_PROP_BEEP,45)
        askAthena(clean)
    }

    private fun stripWakeWord(spoken:String):String{
        val lower=spoken.lowercase(Locale.UK)
        if(lower=="athena") return ""
        var index=6
        while(index<spoken.length && spoken[index].isWhitespace()) index++
        if(index<spoken.length && spoken[index] in charArrayOf(',', '.', ':', ';', '-', '!','?')) index++
        while(index<spoken.length && spoken[index].isWhitespace()) index++
        return spoken.substring(index).trim()
    }

    private fun askAthena(text:String){
        append("You: $text");saveMemory("user",text);status.text="One moment…"
        val local=handleLocalCommand(text)
        if(local!=null){finishReply(local);return}
        val engine=llm
        if(engine==null){finishReply("I’m getting ready. Please try that again in a moment.");return}
        io.execute{
            var oneShot:LlmInferenceSession? = null
            try{
                val options=LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(40).setTopP(0.95f).setTemperature(0.7f).build()
                oneShot=LlmInferenceSession.createFromOptions(engine,options)
                oneShot.addQueryChunk(buildPrompt(text))
                val result=cleanAiOutput(oneShot.generateResponse())
                runOnUiThread{finishReply(if(result.isBlank()) "I could not generate an answer." else result)}
            }catch(_:Exception){
                runOnUiThread{finishReply("I couldn’t work that out just now. Please try again.")}
            }finally{
                try{oneShot?.close()}catch(_:Exception){}
            }
        }
    }

    private fun cleanAiOutput(raw:String):String{
        var r=raw.replace("<start_of_turn>model", "", ignoreCase=true)
            .replace("<end_of_turn>", "", ignoreCase=true)
            .trim()
        val lower=r.lowercase(Locale.UK)
        if(lower.startsWith("assistant:")) r=r.substringAfter(':').trim()
        else if(lower.startsWith("model:")) r=r.substringAfter(':').trim()
        val spamPrefixes=listOf(
            "i am athena, an ai assistant",
            "i am athena, an ai assistant.",
            "i'm athena, an ai assistant",
            "i'm athena, an ai assistant."
        )
        for(prefix in spamPrefixes){
            if(r.startsWith(prefix, ignoreCase=true)){
                r=r.substring(prefix.length).trimStart(' ', '.', ',', '-', ':')
                break
            }
        }
        return r.trim()
    }

    private fun buildPrompt(text:String):String {
        val memory=recentHistory()
        return "<start_of_turn>user\nYou are Athena. Reply naturally to the user. Answer the question directly. Do not start with greetings unless appropriate. Do not say that you are an AI, language model, local model, virtual assistant, or introduce yourself unless the user specifically asks who you are. Do not repeat the instructions. Keep normal answers concise but useful.\n\nConversation context:\n$memory\n\nUser message: $text\n<end_of_turn>\n<start_of_turn>model\n"
    }

    private fun handleLocalCommand(text:String):String?{val l=text.lowercase(Locale.UK).trim();val a=AthenaAccessibilityService.instance
        if(l in listOf("hello","hi","hey"))return "Hello. Athena is ready."
        if(l.contains("what time")||l=="time")return "It is "+java.text.SimpleDateFormat("HH:mm",Locale.UK).format(java.util.Date())+"."
        if(l.contains("what date")||l=="date"||l.contains("what day is it"))return java.text.SimpleDateFormat("EEEE, d MMMM yyyy",Locale.UK).format(java.util.Date())+"."
        if(l.startsWith("remember ")){val x=text.substring(9).trim();if(x.isNotEmpty()){saveMemory("memory",x);return "I'll remember that locally."}}
        if(l=="show memory"||l=="what do you remember"){showMemory();return null}
        if(l in listOf("go home","home","press home")){if(a==null)return "Please give Athena device access in Tools first.";return if(a.home())"Done." else "Android rejected the Home action."}
        if(l in listOf("go back","back","press back")){if(a==null)return "Please give Athena device access in Tools first.";return if(a.back())"Done." else "Android rejected the Back action."}
        if(l in listOf("show recent apps","open recents","recents")){if(a==null)return "Please give Athena device access in Tools first.";return if(a.recents())"Opening recent apps." else "Android rejected the Recents action."}
        if(l in listOf("open notifications","show notifications")){if(a==null)return "Please give Athena device access in Tools first.";return if(a.notifications())"Opening notifications." else "Android rejected the Notifications action."}
        if(l in listOf("quick settings","open quick settings")){if(a==null)return "Please give Athena device access in Tools first.";return if(a.quickSettings())"Opening quick settings." else "Android rejected the action."}
        if(l in listOf("lock phone","lock screen")){if(a==null)return "Please give Athena device access in Tools first.";return if(a.lockScreen())"Locking the phone." else "Android rejected the lock action."}
        if(l in listOf("take screenshot","screenshot")){if(a==null)return "Please give Athena device access in Tools first.";return if(a.screenshot())"Taking a screenshot." else "Android rejected the screenshot action."}
        if(l.startsWith("click ")){if(a==null)return "Please give Athena device access in Tools first.";val q=text.substring(6).trim();return if(a.clickText(q))"Clicked $q." else "I couldn't find a visible control named $q."}
        if(l.startsWith("type ")){if(a==null)return "Please give Athena device access in Tools first.";val q=text.substring(5);return if(a.typeText(q))"Typed it into the focused field." else "I couldn't type into the current field."}
        if(l.startsWith("open ")){val target=text.substring(5).trim();val url=when(target.lowercase(Locale.UK)){"youtube"->"https://www.youtube.com";"google"->"https://www.google.com";"discord"->"https://discord.com/app";"spotify"->"https://open.spotify.com";"gmail"->"https://mail.google.com";else->if(target.startsWith("http"))target else "https://www.google.com/search?q="+Uri.encode(target)};startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)));return "Opening $target."}
        if(l=="connect to pc"||l=="pc status"){sendPc("status"){reply->runOnUiThread{finishReply(reply)}};return "Checking the PC."}
        if(l.startsWith("pc ")){sendPc(text.substring(3)){reply->runOnUiThread{finishReply(reply)}};return "Sending that to the PC."}
        return null}

    private fun finishReply(reply:String){append("Athena: $reply");saveMemory("athena",reply);speak(reply);status.text="Ready"}
    private fun sendPc(command:String,done:(String)->Unit){
        val host=prefs.getString("pc_host","") ?: ""
        val port=prefs.getInt("pc_port",49321)
        val token=prefs.getString("pc_token","") ?: ""
        if(host.isBlank()||token.isBlank()){done("Your PC isn’t connected yet. Open Tools and choose Connect to my PC.");return}
        io.execute{try{
            Socket().use{socket->socket.connect(InetSocketAddress(host,port),2500);socket.soTimeout=5000
                val req=JSONObject().put("token",token).put("command",command).toString()+"\n"
                socket.getOutputStream().write(req.toByteArray(Charsets.UTF_8));socket.getOutputStream().flush()
                val reply=socket.getInputStream().bufferedReader().readLine() ?: error("empty")
                done(JSONObject(reply).optString("reply","No response from PC."))
            }
        } catch (_: Exception) {
            done("I couldn’t reach your PC. Make sure Athena is open there and both devices are on the same Wi‑Fi.")
        }}
    }

    private fun showPcDialog(){
        val connected=prefs.getString("pc_host","")?.isNotBlank()==true && prefs.getString("pc_token","")?.isNotBlank()==true
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(34,12,34,8);gravity=Gravity.CENTER}
        val info=TextView(this).apply{
            text=if(connected) "Your PC is already connected.\n\nIf you are setting up another PC, tap FIND MY PC below." else "Make sure Athena is open on your Windows PC and both devices are on the same Wi‑Fi.\n\nAthena will find it for you — nothing to type."
            textSize=15f;setTextColor(Color.DKGRAY);gravity=Gravity.CENTER;setPadding(0,0,0,20)
        }
        box.addView(info)
        val dialog=android.app.AlertDialog.Builder(this).setTitle("Connect to your PC").setView(box)
            .setNegativeButton("DONE",null).create()
        dialog.setButton(android.app.AlertDialog.BUTTON_POSITIVE,"FIND MY PC"){_,_->
            discoverPc(null,null)
        }
        dialog.show()
    }

    private fun discoverPc(hostField:EditText?,tokenField:EditText?){
        Toast.makeText(this,"Looking for your PC…",Toast.LENGTH_SHORT).show()
        io.execute{
            var foundHost="";var foundToken=""
            try{
                val socket=java.net.DatagramSocket().apply{broadcast=true;soTimeout=3000}
                val bytes="ATHENA_DISCOVER".toByteArray(Charsets.UTF_8)
                val request=java.net.DatagramPacket(bytes,bytes.size,java.net.InetAddress.getByName("255.255.255.255"),49322)
                socket.send(request)
                val buf=ByteArray(4096);val packet=java.net.DatagramPacket(buf,buf.size);socket.receive(packet)
                val obj=JSONObject(String(packet.data,0,packet.length,Charsets.UTF_8))
                if(obj.optString("service")=="athena" && obj.optString("token").isNotBlank()){
                    foundHost=packet.address.hostAddress ?: obj.optString("host");foundToken=obj.optString("token")
                }
                socket.close()
            }catch(_:Exception){}
            runOnUiThread{
                if(foundHost.isNotBlank()){
                    prefs.edit().putString("pc_host",foundHost).putString("pc_token",foundToken).apply()
                    Toast.makeText(this,"Your PC is connected.",Toast.LENGTH_LONG).show()
                }else Toast.makeText(this,"I couldn't find Athena on your PC. Make sure the Windows app is open and both devices are on the same Wi‑Fi.",Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun append(s:String){chat.append("\n$s\n");scroll.post{scroll.fullScroll(View.FOCUS_DOWN)}}
    private fun saveMemory(role:String,text:String){val arr=try{JSONArray(prefs.getString("memory","[]"))}catch(_:Exception){JSONArray()};arr.put(JSONObject().put("role",role).put("text",text).put("time",System.currentTimeMillis()));while(arr.length()>500)arr.remove(0);prefs.edit().putString("memory",arr.toString()).apply()}
    private fun recentHistory(): String {
        val arr = try {
            JSONArray(prefs.getString("memory", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        val start = maxOf(0, arr.length() - 6)
        val out = StringBuilder()
        for (i in start until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val role = item.optString("role", "user")
            val text = item.optString("text", "").replace("<start_of_turn>", "").replace("<end_of_turn>", "").takeLast(350)
            if (text.isNotBlank()) out.append(role).append(": ").append(text).append('\n')
        }
        return out.toString()
    }

    private fun showMemory(){val arr=try{JSONArray(prefs.getString("memory","[]"))}catch(_:Exception){JSONArray()};val out=StringBuilder("LOCAL MEMORY\n\n");val start=maxOf(0,arr.length()-30);for(i in start until arr.length()){val o=arr.optJSONObject(i);out.append(o?.optString("role")).append(": ").append(o?.optString("text")).append('\n')};chat.text=out.toString();scroll.post{scroll.fullScroll(View.FOCUS_DOWN)}}
    private fun loadModelIfPresent(){
        io.execute{
            try{
                val dir=File(filesDir,"models").apply{mkdirs()}
                val f=File(dir,"athena.task")
                if(!f.exists()){
                    assets.open("athena.task").use{input->FileOutputStream(f).use{output->input.copyTo(output)}}
                }
                val options=LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(f.absolutePath)
                    .setMaxTokens(512)
                    .build()
                val inference=LlmInference.createFromOptions(this,options)
                val sessionOptions=LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(40).setTopP(0.95f).setTemperature(0.7f).build()
                llm=inference
                session=LlmInferenceSession.createFromOptions(inference,sessionOptions)
                runOnUiThread{status.text="Ready"}
            }catch(e:Exception){
                runOnUiThread{status.text="I need a moment to get ready";Toast.makeText(this,"Athena could not start her conversation engine. Please reopen the app.",Toast.LENGTH_LONG).show()}
            }
        }
    }
    override fun onInit(result:Int){if(result==TextToSpeech.SUCCESS)tts.language=Locale.UK}
    private fun speak(text:String){
        try{
            if(backgroundWakeCommandActive){
                tts.setOnUtteranceProgressListener(object:android.speech.tts.UtteranceProgressListener(){
                    override fun onStart(utteranceId:String?){}
                    override fun onDone(utteranceId:String?){
                        backgroundWakeCommandActive=false
                        startService(Intent(this@MainActivity,AthenaWakeService::class.java).setAction(AthenaWakeService.ACTION_RESUME))
                    }
                    override fun onError(utteranceId:String?){
                        backgroundWakeCommandActive=false
                        startService(Intent(this@MainActivity,AthenaWakeService::class.java).setAction(AthenaWakeService.ACTION_RESUME))
                    }
                })
            }
            tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"athena")
        }catch(_:Exception){
            if(backgroundWakeCommandActive){
                backgroundWakeCommandActive=false
                startService(Intent(this,AthenaWakeService::class.java).setAction(AthenaWakeService.ACTION_RESUME))
            }
        }
    }
    override fun onDestroy(){wakeMode=false;recognizer?.destroy();tts.shutdown();io.shutdownNow();llm?.close();session?.close();super.onDestroy()}
}
