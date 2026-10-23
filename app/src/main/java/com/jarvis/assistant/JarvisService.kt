package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.BatteryManager
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JarvisService : Service(), RecognitionListener, TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var awake = false
    private var speaking = false
    private val wakeWords = listOf("jarvis", "friday", "edith", "जार्विस", "जारविस", "जार्विज", "फ्राइडे", "फ्राईडे", "एडिथ")
    private val restart = Runnable { listen() }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "jarvis")
            .setContentTitle("J.A.R.V.I.S online")
            .setContentText("Say \"Jarvis\" to give a command")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { finished() }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { finished() }
        })
        say("Jarvis online. At your service, boss.")
    }

    private fun finished() = main.post { speaking = false; listen() }

    private fun say(t: String) = main.post {
        if (!ttsReady) { listen(); return@post }
        speaking = true
        recognizer?.cancel()
        tts?.language = if (t.any { it in '\u0900'..'\u097F' }) Locale("hi", "IN") else Locale.getDefault()
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "j")
    }

    private fun later(ms: Long = 700) { main.removeCallbacks(restart); main.postDelayed(restart, ms) }

    private fun listen() {
        if (speaking) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, getSharedPreferences("j", MODE_PRIVATE).getString("lang", "hi-IN"))
        recognizer?.startListening(i)
    }

    override fun onResults(b: Bundle?) {
        val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.lowercase(Locale.getDefault())
        if (text.isNullOrBlank()) { later(); return }
        val w = wakeWords.firstOrNull { text.contains(it) }
        val cmd: String? = when {
            w != null -> text.substringAfter(w).trim(' ', ',', '.')
            awake -> text
            else -> null
        }
        if (cmd == null) { later(); return }
        if (cmd.isEmpty()) { awake = true; say("Yes, boss?"); return }
        awake = false
        Thread {
            val reply = try { handle(cmd) } catch (e: Exception) { "Sorry boss, that didn't work." }
            say(reply)
        }.start()
    }

    override fun onError(e: Int) {
        if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) awake = false
        later(if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 600)
    }

    override fun onReadyForSpeech(p: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(v: Float) {}
    override fun onBufferReceived(b: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(b: Bundle?) {}
    override fun onEvent(t: Int, b: Bundle?) {}

    // ---------- commands ----------
    private fun go(i: Intent) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i) }

    private fun handle(c: String): String {
        if (key().isNotBlank()) return brain(c)
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        when {
            c.startsWith("open ") || c.startsWith("launch ") -> return openApp(c.substringAfter(" ").trim())
            c.contains("flashlight") || c.contains("torch") -> {
                val on = !c.contains("off")
                val cm = getSystemService(CAMERA_SERVICE) as CameraManager
                cm.setTorchMode(cm.cameraIdList[0], on)
                return if (on) "Flashlight on." else "Flashlight off."
            }
            c.contains("volume up") || c.contains("louder") -> {
                repeat(3) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
                return "Volume up."
            }
            c.contains("volume down") || c.contains("quieter") -> {
                repeat(3) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
                return "Volume down."
            }
            c.startsWith("call ") -> return call(c.substringAfter("call ").trim())
            c.contains("timer") -> {
                val m = Regex("(\\d+)\\s*(hour|minute|min|second|sec)").find(c) ?: return "For how long, boss?"
                val n = m.groupValues[1].toInt()
                val secs = when (m.groupValues[2]) { "hour" -> n * 3600; "minute", "min" -> n * 60; else -> n }
                go(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, secs).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                return "Timer set."
            }
            c.contains("what time") -> return "It's " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) + ", boss."
            c.startsWith("search ") || c.startsWith("google ") -> {
                val q = URLEncoder.encode(c.substringAfter(" "), "UTF-8")
                go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$q")))
                return "Searching."
            }
        }
        return askClaude(c)
    }

    private fun openApp(name: String): String {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val app = apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(name) }
            ?: return "I couldn't find $name, boss."
        go(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(app.activityInfo.packageName, app.activityInfo.name))
        return "Opening ${app.loadLabel(pm)}."
    }

    private fun call(name: String): String {
        if (checkSelfPermission("android.permission.CALL_PHONE") != PackageManager.PERMISSION_GRANTED)
            return "I need call permission, boss."
        var num: String? = if (name.replace(" ", "").all { it.isDigit() }) name.replace(" ", "") else null
        if (num == null) {
            val P = ContactsContract.CommonDataKinds.Phone
            contentResolver.query(P.CONTENT_URI, arrayOf(P.NUMBER), "${P.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null)
                ?.use { if (it.moveToFirst()) num = it.getString(0) }
        }
        val n = num ?: return "I couldn't find $name in your contacts."
        go(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(n))))
        return "Calling $name."
    }

    private fun askClaude(q: String) = claude("You are JARVIS, a witty phone assistant. Answer in one or two short spoken sentences, no markdown.", q)

    private fun claude(sys: String, q: String): String {
        val key = key()
        if (key.isBlank()) return "Boss, add your Claude API key in the app so I can answer that."
        val body = JSONObject()
            .put("model", "claude-haiku-5-5")
            .put("max_tokens", 500)
            .put("system", sys)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", q)))
        val con = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        con.requestMethod = "POST"
        con.setRequestProperty("x-api-key", key)
        con.setRequestProperty("anthropic-version", "2023-06-01")
        con.setRequestProperty("content-type", "application/json")
        con.connectTimeout = 15000
        con.readTimeout = 30000
        con.doOutput = true
        con.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = con.responseCode
        if (code !in 200..299) return "The AI link returned error $code."
        val txt = con.inputStream.bufferedReader().readText()
        return JSONObject(txt).getJSONArray("content").getJSONObject(0).getString("text")
    }

    private fun key() = getSharedPreferences("j", MODE_PRIVATE).getString("key", "") ?: ""
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun web(u: String) = go(Intent(Intent.ACTION_VIEW, Uri.parse(u)))

    private val BRAIN = """You are JARVIS, Tony Stark's witty AI living inside the user's Android phone. The user speaks English, Hindi or Hinglish; speech-to-text may be misspelled and Hindi may arrive in Devanagari. Understand the intent and reply with ONLY a JSON object: {"say":"short spoken reply","actions":[...]}. Reply in the user's language; for Hindi or Hinglish write "say" in Devanagari Hindi. Call the user boss (or बॉस). Actions use the field "do": open_app{name}, call{name}, sms{name,text}, whatsapp{name,text}, flashlight{on:true|false}, volume{dir:up|down|mute}, media{key:play_pause|next|prev}, timer{seconds}, alarm{hour,minute}, search{text}, youtube{text}, maps{text}, url{text}, settings{page:wifi|bluetooth|airplane|display|sound|location|main}, ui{name:back|home|recents|notifications|quick_settings|lock|screenshot}, click{text}, type{text}, scroll{dir:up|down}, read_screen, read_notifications. Use actions:[] for plain questions and chat. Write app and contact names in Latin letters. Never claim to have done something that is not in actions."""

    private fun brain(q: String): String {
        val bat = (getSystemService(BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val now = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date())
        val out = claude(BRAIN, "[time: $now, battery: $bat%] $q")
        val j = try { JSONObject(out.substring(out.indexOf('{'), out.lastIndexOf('}') + 1)) } catch (e: Exception) { return out }
        var say = j.optString("say")
        val acts = j.optJSONArray("actions") ?: JSONArray()
        for (i in 0 until acts.length()) {
            val r = act(acts.getJSONObject(i))
            if (r.isNotEmpty()) say += " $r"
            Thread.sleep(900)
        }
        return say.trim()
    }

    private fun numberOf(name: String): String? {
        if (name.isBlank()) return null
        if (name.replace(" ", "").all { it.isDigit() }) return name.replace(" ", "")
        val P = ContactsContract.CommonDataKinds.Phone
        var num: String? = null
        contentResolver.query(P.CONTENT_URI, arrayOf(P.NUMBER), "${P.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null)
            ?.use { if (it.moveToFirst()) num = it.getString(0) }
        return num
    }

    private fun act(a: JSONObject): String {
        val t = a.optString("text")
        val acc = JarvisAccess.inst
        val noAcc = "Boss, please enable my accessibility access in the app."
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        when (a.optString("do")) {
            "open_app" -> { val r = openApp(a.optString("name").lowercase()); return if (r.startsWith("Opening")) "" else r }
            "call" -> { val r = call(a.optString("name")); return if (r.startsWith("Calling")) "" else r }
            "sms" -> {
                if (checkSelfPermission("android.permission.SEND_SMS") != PackageManager.PERMISSION_GRANTED) return "I need SMS permission, boss."
                val n = numberOf(a.optString("name")) ?: return "I couldn't find that contact."
                val sm = SmsManager.getDefault()
                sm.sendMultipartTextMessage(n, null, sm.divideMessage(t), null, null)
            }
            "whatsapp" -> {
                var n = (numberOf(a.optString("name")) ?: return "I couldn't find that contact.").filter { it.isDigit() }
                if (n.length == 10) n = "91$n"
                web("https://wa.me/$n?text=" + enc(t))
                Thread.sleep(3500)
                if (acc?.click("Send") != true) return "The message is ready, boss. Tap send."
            }
            "flashlight" -> { val cm = getSystemService(CAMERA_SERVICE) as CameraManager; cm.setTorchMode(cm.cameraIdList[0], a.optBoolean("on", true)) }
            "volume" -> {
                val d = when (a.optString("dir")) { "down" -> AudioManager.ADJUST_LOWER; "mute" -> AudioManager.ADJUST_MUTE; else -> AudioManager.ADJUST_RAISE }
                repeat(if (d == AudioManager.ADJUST_MUTE) 1 else 3) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, AudioManager.FLAG_SHOW_UI) }
            }
            "media" -> {
                val k = when (a.optString("key")) { "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE }
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, k))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, k))
            }
            "timer" -> go(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds", 60)).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            "alarm" -> go(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour")).putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute")).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            "search" -> web("https://www.google.com/search?q=" + enc(t))
            "youtube" -> web("https://www.youtube.com/results?search_query=" + enc(t))
            "maps" -> web("https://www.google.com/maps/search/?api=1&query=" + enc(t))
            "url" -> web(t)
            "settings" -> go(Intent(when (a.optString("page")) {
                "wifi" -> Settings.ACTION_WIFI_SETTINGS
                "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
                "display" -> Settings.ACTION_DISPLAY_SETTINGS
                "sound" -> Settings.ACTION_SOUND_SETTINGS
                "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                else -> Settings.ACTION_SETTINGS
            }))
            "ui" -> { if (acc == null) return noAcc; acc.global(a.optString("name")) }
            "click" -> { if (acc == null) return noAcc; if (!acc.click(t)) return "I couldn't find $t on the screen." }
            "type" -> { if (acc == null) return noAcc; acc.type(t) }
            "scroll" -> { if (acc == null) return noAcc; acc.scroll(a.optString("dir") != "up") }
            "read_screen" -> return acc?.screenText() ?: noAcc
            "read_notifications" -> return synchronized(JarvisNotifs.recent) {
                JarvisNotifs.recent.take(5).joinToString(". ").ifEmpty { "No new notifications." }
            }
        }
        return ""
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
