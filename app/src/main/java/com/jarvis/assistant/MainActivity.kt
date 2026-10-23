package com.jarvis.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin

class Reactor(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    override fun onDraw(cv: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) * 0.9f
        t += 1.5f
        p.color = 0xFF00E5FF.toInt()
        p.style = Paint.Style.STROKE
        p.alpha = 255
        p.strokeWidth = 6f
        cv.drawCircle(cx, cy, r, p)
        p.strokeWidth = 3f
        cv.drawCircle(cx, cy, r * 0.7f, p)
        for (i in 0 until 8) {
            val a = Math.toRadians((t + i * 45).toDouble())
            cv.drawLine(
                cx + (r * 0.4f * cos(a)).toFloat(), cy + (r * 0.4f * sin(a)).toFloat(),
                cx + (r * 0.7f * cos(a)).toFloat(), cy + (r * 0.7f * sin(a)).toFloat(), p
            )
        }
        p.style = Paint.Style.FILL
        p.alpha = (150 + 100 * sin(t / 12.0)).toInt()
        cv.drawCircle(cx, cy, r * 0.25f, p)
        postInvalidateOnAnimation()
    }
}

class MainActivity : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val prefs = getSharedPreferences("j", MODE_PRIVATE)
        val bg = 0xFF050B14.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(bg)
            setPadding(48, 80, 48, 48)
        }
        fun txt(t: String, size: Float) = TextView(this).apply {
            text = t; textSize = size; setTextColor(0xFF00E5FF.toInt()); gravity = Gravity.CENTER
        }
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { f() }
        }

        root.addView(txt("J.A.R.V.I.S", 30f))
        root.addView(Reactor(this), LinearLayout.LayoutParams(600, 600).apply { topMargin = 32; bottomMargin = 32 })
        val status = txt("Offline", 16f)
        root.addView(status)

        val key = EditText(this).apply {
            hint = "Claude API key (optional, for smart answers)"
            setHintTextColor(0xFF557788.toInt()); setTextColor(0xFFFFFFFF.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        root.addView(key)

        root.addView(btn("1. Allow mic, contacts, calls") {
            requestPermissions(arrayOf(
                "android.permission.RECORD_AUDIO", "android.permission.READ_CONTACTS",
                "android.permission.CALL_PHONE", "android.permission.SEND_SMS", "android.permission.POST_NOTIFICATIONS"), 1)
        })
        root.addView(btn("2. Allow display over other apps") {
            if (!Settings.canDrawOverlays(this))
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(btn("2b. Enable Accessibility (screen control)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(btn("2c. Enable Notification access") {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        })
        val lang = btn("") {}
        fun showLang() {
            lang.text = "Voice language: " + if (prefs.getString("lang", "hi-IN") == "hi-IN") "Hindi / Hinglish" else "English"
        }
        showLang()
        lang.setOnClickListener {
            prefs.edit().putString("lang", if (prefs.getString("lang", "hi-IN") == "hi-IN") "en-IN" else "hi-IN").apply()
            showLang()
        }
        root.addView(lang)
        root.addView(btn("3. START JARVIS") {
            prefs.edit().putString("key", key.text.toString().trim()).apply()
            startForegroundService(Intent(this, JarvisService::class.java))
            status.text = "Online. Say \"Jarvis\" + command"
        })
        root.addView(btn("Stop") {
            stopService(Intent(this, JarvisService::class.java))
            status.text = "Offline"
        })
        root.addView(txt("Try: \"Jarvis open YouTube\", \"Jarvis call Mom\", \"Jarvis flashlight on\", \"Jarvis set timer 5 minutes\", \"Jarvis volume up\", or ask anything.", 13f).apply { setPadding(0, 32, 0, 0) })

        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(root) })
    }
}
