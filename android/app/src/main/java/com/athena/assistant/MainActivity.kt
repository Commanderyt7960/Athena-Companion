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
    private lateinit var prefs: SharedPreferences
    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null
    private var wakeMode = false
    private val io = Executors.newCachedThreadPool()
    private var llm: LlmInference? = null
    private var session: LlmInferenceSession? = null
    private lateinit var status: TextView
    private lateinit var chat: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private lateinit var pcAddress: EditText
    private lateinit var pcToken: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("athena_local", Context.MODE_PRIVATE)
        tts = TextToSpeech(this, this)
        buildUi(); requestPermissions(); loadModelIfPresent()
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; setOnClickListener { action() } }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,28,24,18); setBackgroundColor(Color.rgb(8,10,16)) }
        val title=TextView(this).apply{text="ATHENA";textSize=34f;gravity=Gravity.CENTER;setTextColor(Color.rgb(237,235,255))}
        val sub=TextView(this).apply{text="AI assistant • voice • memory • device + PC control";textSize=14f;gravity=Gravity.CENTER;setTextColor(Color.rgb(169,165,189));setPadding(0,0,0,10)}
        status=TextView(this).apply{text="Ready";gravity=Gravity.CENTER;setTextColor(Color.rgb(159,231,196));setPadding(0,4,0,10)}
        chat=TextView(this).apply{text="Athena: Ready. Say “Athena” or type a message.\n";textSize=16f;setTextColor(Color.rgb(232,230,239));setPadding(12,12,12,12)}
        scroll=ScrollView(this).apply{addView(chat)}
        input=EditText(this).apply{hint="Talk to Athena…";setSingleLine(false);setTextColor(Color.rgb(232,230,239));setHintTextColor(Color.rgb(119,116,135));setBackgroundColor(Color.rgb(17,20,29))}
        root.addView(title);root.addView(sub);root.addView(status);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));root.addView(input,LinearLayout.LayoutParams(-1,90))
        root.addView(button("SEND"){val s=input.text.toString().trim();if(s.isNotEmpty()){input.setText("");askAthena(s)}})
        root.addView(button("🎙 TALK"){startListening(false)})
        root.addView(button("ATHENA WAKE LISTEN"){wakeMode=!wakeMode;if(wakeMode){startListening(true)}else recognizer?.cancel()})
        root.addView(button("🧠 ATHENA LOCAL AI"){showLocalAiInfo()})
        root.addView(button("🔐 DEVICE PERMISSIONS"){showPermissions()})
        root.addView(button("🖥 PC CONTROL / PAIR"){showPcDialog()})
        root.addView(button("🧠 MEMORY"){showMemory()})
                root.addView(button("CLEAR CHAT"){chat.text="Athena: Chat cleared.\n"})
        setContentView(root)
    }

    private fun requestPermissions(){
        val needed=mutableListOf<String>()
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.RECORD_AUDIO)
        if(android.os.Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if(needed.isNotEmpty()) ActivityCompat.requestPermissions(this,needed.toTypedArray(),10)
    }

    private fun showPermissions(){
        val d=android.app.AlertDialog.Builder(this).setTitle("Athena device permissions")
            .setMessage("ACCESSIBILITY DISCLOSURE\n\nAthena uses Android AccessibilityService to perform user-requested, deterministic device actions such as Home, Back, Recents, notifications, screenshots, clicking visible controls and typing into the focused field. Accessibility data is used only to carry out these requested actions. Athena does not silently enable the service, bypass Android security controls, or make autonomous decisions to control the device.\n\nMicrophone: ${if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) "ON" else "OFF"}\nAccessibility control: ${if(AthenaAccessibilityService.instance!=null) "ON" else "OFF"}")
            .setPositiveButton("OPEN ACCESSIBILITY SETTINGS"){_,_->startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}
            .setNegativeButton("CLOSE",null).create();d.show()
    }

    private fun showLocalAiInfo(){
        android.app.AlertDialog.Builder(this)
            .setTitle("Athena Local AI")
            .setMessage("Athena's language model is bundled inside this app.\n\nNo Gemini API key is required for AI chat. The local Gemma model runs on the phone itself.\n\nBecause the model is large, the APK is substantially larger than a normal app and the first AI response may take a little longer while the model initializes.")
            .setPositiveButton("OK",null)
            .show()
    }

    private fun startListening(wake:Boolean){
        if(!SpeechRecognizer.isRecognitionAvailable(this)){status.text="Speech recognition unavailable";return}
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
    private fun handleVoice(raw:String,wake:Boolean){val lower=raw.lowercase(Locale.UK).trim();if(wake&&!Regex("^athena\\b").containsMatchIn(lower))return;val clean=if(wake)raw.replaceFirst(Regex("(?i)^\\s*athena\\b"),"").trim(' ',',','.',':',';') else raw;if(clean.isBlank()){ToneGenerator(AudioManager.STREAM_NOTIFICATION,45).startTone(ToneGenerator.TONE_PROP_BEEP,80);return};ToneGenerator(AudioManager.STREAM_NOTIFICATION,35).startTone(ToneGenerator.TONE_PROP_BEEP,45);askAthena(clean)}

    private fun askAthena(text:String){
        append("You: $text");saveMemory("user",text);status.text="Thinking…"
        val local=handleLocalCommand(text)
        if(local!=null){finishReply(local);return}
        val s=session
        if(s==null){finishReply("Athena's local AI is still starting. Please try again in a moment.");return}
        io.execute{
            try{
                s.addQueryChunk(buildPrompt(text))
                val result=s.generateResponse()
                runOnUiThread{finishReply(result)}
            }catch(_:Exception){
                runOnUiThread{finishReply("Athena's local AI could not generate a response. Try again in a moment.")}
            }
        }
    }

    private fun buildPrompt(text:String):String {
        val memory=recentHistory()
        return "You are Athena, a private local AI assistant running entirely on the user's Android device. Be natural, concise and useful. You have local conversation memory. You may explain how to perform actions, but never claim an Android action happened unless Athena's command system confirmed it.\n\nRecent conversation:\n$memory\n\nUser: $text"
    }

    private fun handleLocalCommand(text:String):String?{val l=text.lowercase(Locale.UK).trim();val a=AthenaAccessibilityService.instance
        if(l in listOf("hello","hi","hey"))return "Hello. Athena is ready."
        if(l.contains("what time")||l=="time")return "It is "+java.text.SimpleDateFormat("HH:mm",Locale.UK).format(java.util.Date())+"."
        if(l.contains("what date")||l=="date"||l.contains("what day is it"))return java.text.SimpleDateFormat("EEEE, d MMMM yyyy",Locale.UK).format(java.util.Date())+"."
        if(l.startsWith("remember ")){val x=text.substring(9).trim();if(x.isNotEmpty()){saveMemory("memory",x);return "I'll remember that locally."}}
        if(l=="show memory"||l=="what do you remember"){showMemory();return null}
        if(l in listOf("go home","home","press home")){if(a==null)return "Please enable Athena Accessibility in Device Permissions first.";return if(a.home())"Done." else "Android rejected the Home action."}
        if(l in listOf("go back","back","press back")){if(a==null)return "Please enable Athena Accessibility in Device Permissions first.";return if(a.back())"Done." else "Android rejected the Back action."}
        if(l in listOf("show recent apps","open recents","recents")){if(a==null)return "Please enable Athena Accessibility in Device Permissions first.";return if(a.recents())"Opening recent apps." else "Android rejected the Recents action."}
        if(l in listOf("open notifications","show notifications")){if(a==null)return "Please enable Athena Accessibility in Device Permissions first.";return if(a.notifications())"Opening notifications." else "Android rejected the Notifications action."}
        if(l in listOf("quick settings","open quick settings")){if(a==null)return "Please enable Athena Accessibility in Device Permissions first.";return if(a.quickSettings())"Opening quick settings." else "Android rejected the action."}
        if(l in listOf("lock phone","lock screen")){if(a==null)return "Please enable Athena Accessibility in Device Permissions first.";return if(a.lockScreen())"Locking the phone." else "Android rejected the lock action."}
        if(l in listOf("take screenshot","screenshot")){if(a==null)return "Please enable Athena Accessibility in Device Permissions first.";return if(a.screenshot())"Taking a screenshot." else "Android rejected the screenshot action."}
        if(l.startsWith("click ")){if(a==null)return "Please enable Athena Accessibility in Device Permissions first.";val q=text.substring(6).trim();return if(a.clickText(q))"Clicked $q." else "I couldn't find a visible control named $q."}
        if(l.startsWith("type ")){if(a==null)return "Please enable Athena Accessibility in Device Permissions first.";val q=text.substring(5);return if(a.typeText(q))"Typed it into the focused field." else "I couldn't type into the current field."}
        if(l.startsWith("open ")){val target=text.substring(5).trim();val url=when(target.lowercase(Locale.UK)){"youtube"->"https://www.youtube.com";"google"->"https://www.google.com";"discord"->"https://discord.com/app";"spotify"->"https://open.spotify.com";"gmail"->"https://mail.google.com";else->if(target.startsWith("http"))target else "https://www.google.com/search?q="+Uri.encode(target)};startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)));return "Opening $target."}
        if(l=="connect to pc"||l=="pc status"){sendPc("status"){reply->runOnUiThread{finishReply(reply)}};return "Checking the PC."}
        if(l.startsWith("pc ")){sendPc(text.substring(3)){reply->runOnUiThread{finishReply(reply)}};return "Sending that to the PC."}
        return null}

    private fun finishReply(reply:String){append("Athena: $reply");saveMemory("athena",reply);speak(reply);status.text="Ready"}
    private fun sendPc(command:String,done:(String)->Unit){
        val host=prefs.getString("pc_host","") ?: ""
        val port=prefs.getInt("pc_port",49321)
        val token=prefs.getString("pc_token","") ?: ""
        if(host.isBlank()||token.isBlank()){done("The PC is not paired yet.");return}
        io.execute{try{
            Socket().use{socket->socket.connect(InetSocketAddress(host,port),2500);socket.soTimeout=5000
                val req=JSONObject().put("token",token).put("command",command).toString()+"\n"
                socket.getOutputStream().write(req.toByteArray(Charsets.UTF_8));socket.getOutputStream().flush()
                val reply=socket.getInputStream().bufferedReader().readLine() ?: error("empty")
                done(JSONObject(reply).optString("reply","No response from PC."))
            }
        } catch (_: Exception) {
            done("I couldn't reach the PC. Check its address, firewall and pairing token.")
        }}
    }

    private fun showPcDialog(){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(35,10,35,5)}
        pcAddress=EditText(this).apply{hint="PC address, e.g. 192.168.1.10";setText(prefs.getString("pc_host",""))}
        pcToken=EditText(this).apply{hint="Pairing token";setText(prefs.getString("pc_token",""))}
        box.addView(pcAddress);box.addView(pcToken)
        android.app.AlertDialog.Builder(this).setTitle("Pair Athena with Windows PC").setView(box)
            .setPositiveButton("SAVE & TEST"){_,_->prefs.edit().putString("pc_host",pcAddress.text.toString().trim()).putString("pc_token",pcToken.text.toString().trim()).apply();sendPc("ping"){r->runOnUiThread{Toast.makeText(this,r,Toast.LENGTH_LONG).show()}}}
            .setNegativeButton("CANCEL",null).show()
    }

    private fun append(s:String){chat.append("\n$s\n");scroll.post{scroll.fullScroll(View.FOCUS_DOWN)}}
    private fun saveMemory(role:String,text:String){val arr=try{JSONArray(prefs.getString("memory","[]"))}catch(_:Exception){JSONArray()};arr.put(JSONObject().put("role",role).put("text",text).put("time",System.currentTimeMillis()));while(arr.length()>500)arr.remove(0);prefs.edit().putString("memory",arr.toString()).apply()}
    private fun recentHistory(): String {
        val arr = try {
            JSONArray(prefs.getString("memory", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        val start = maxOf(0, arr.length() - 12)
        val out = StringBuilder()
        for (i in start until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val role = item.optString("role", "user")
            val text = item.optString("text", "")
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
                runOnUiThread{status.text="Local Athena AI ready • offline"}
            }catch(e:Exception){
                runOnUiThread{status.text="Local AI failed to initialize";Toast.makeText(this,"Bundled Athena model could not be loaded: ${e.message}",Toast.LENGTH_LONG).show()}
            }
        }
    }
    override fun onInit(result:Int){if(result==TextToSpeech.SUCCESS)tts.language=Locale.UK}
    private fun speak(text:String){try{tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"athena")}catch(_:Exception){}}
    override fun onDestroy(){wakeMode=false;recognizer?.destroy();tts.shutdown();io.shutdownNow();llm?.close();session?.close();super.onDestroy()}
}
