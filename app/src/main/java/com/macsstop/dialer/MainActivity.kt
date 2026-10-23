package com.macsstop.dialer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    /** The phone's own ringtone list (Settings → Ringtone → From phone). */
    private val pickRingtone = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        @Suppress("DEPRECATION")
        val uri: Uri? = res.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        val ev = org.json.JSONObject().put("type", "ringPicked")
        if (res.resultCode == RESULT_OK && uri != null) ev.put("uri", uri.toString()).put("title", RingtonePlayer.titleOf(uri))
        else ev.put("uri", "")
        web.evaluateJavascript("window.__native && window.__native($ev)", null)
    }

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startSipService()
        askBatteryExemption()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SipManager.init(this)
        updateLockScreen()
        SipManager.onCallsChangedUi = callsUi

        web = WebView(this)
        setContentView(web)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/rec/", WebViewAssetLoader.InternalStoragePathHandler(this, SipManager.recDir()))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) {}
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val accept = params.acceptTypes.joinToString(",")
                val type = when {
                    accept.contains("image") -> "image/*"
                    accept.contains("audio") || accept.contains(".mp3") -> "audio/*"
                    else -> "*/*"
                }
                return try { pickFile.launch(type); true } catch (e: Exception) { fileCallback = null; false }
            }
        }
        web.addJavascriptInterface(Bridge(), "NativeSip")
        SipManager.listener = { json ->
            runOnUiThread { web.evaluateJavascript("window.__native && window.__native($json)", null) }
        }
        web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })

        requestNeededPermissions()
        handleCallIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallIntent(intent)
    }

    /** "Answer" on the incoming-call notification (also from the lock screen). */
    private fun handleCallIntent(i: Intent?) {
        if (i?.action != SipService.ACTION_ANSWER) return
        val id = i.getStringExtra(SipService.EXTRA_CALL) ?: return
        i.action = null
        SipManager.answer(id)
        SipManager.ready()
    }

    /** Android 14+: full-screen call alerts need this permission; without it the lock screen only shows a small notification. */
    private fun canFullScreen(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 34) getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent() else true
    } catch (e: Exception) { true }

    private fun notificationsOn(): Boolean = try {
        androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
    } catch (e: Exception) { true }

    override fun onResume() {
        super.onResume()
        SipManager.ready()
        SipService.clearMissed(this)
    }

    private val callsUi: () -> Unit = { runOnUiThread { updateLockScreen() } }

    override fun onDestroy() {
        if (SipManager.onCallsChangedUi === callsUi) SipManager.onCallsChangedUi = null
        super.onDestroy()
    }

    /**
     * The app shows on top of the lock screen only while there is a call (so a call can be answered
     * without unlocking). With no call, the phone has to be unlocked to see history, contacts and settings.
     */
    private fun updateLockScreen() {
        val onCall = SipManager.hasCalls()
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(onCall)
            setTurnScreenOn(onCall)
        } else {
            @Suppress("DEPRECATION")
            val f = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (onCall) window.addFlags(f) else window.clearFlags(f)
        }
        if (onCall) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Volume buttons silence the ringtone (the call keeps ringing on screen), like a normal phone. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if ((keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) && SipManager.hasRingingCall()) {
            RingtonePlayer.stop()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { startSipService(); askBatteryExemption() } else askPermissions.launch(missing.toTypedArray())
    }

    private fun startSipService() {
        try { ContextCompat.startForegroundService(this, Intent(this, SipService::class.java)) } catch (e: Exception) {}
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            val prefs = getSharedPreferences("app", MODE_PRIVATE)
            if (!pm.isIgnoringBatteryOptimizations(packageName) && !prefs.getBoolean("askedBattery", false)) {
                prefs.edit().putBoolean("askedBattery", true).apply()
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        } catch (e: Exception) {}
    }

    private fun shareFile(f: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share"))
        } catch (e: Exception) {}
    }

    /** Methods the web UI calls as window.NativeSip.* */
    inner class Bridge {
        @JavascriptInterface fun register(json: String) = SipManager.register(json)
        @JavascriptInterface fun unregister() = SipManager.unregister()
        @JavascriptInterface fun call(target: String): String = SipManager.call(target)
        @JavascriptInterface fun callVia(target: String, via: String): String = SipManager.call(target, via)
        @JavascriptInterface fun registerOut(json: String) = SipManager.registerOut(json)
        @JavascriptInterface fun unregisterOut() = SipManager.unregisterOut()
        @JavascriptInterface fun setRingtone(json: String) = RingtonePlayer.setChoice(json)
        @JavascriptInterface fun previewRingtone(id: String, uri: String) = RingtonePlayer.preview(id, uri)
        @JavascriptInterface fun stopRingPreview() = RingtonePlayer.stopPreview()
        @JavascriptInterface fun saveCustomRingtone(b64: String): Boolean = RingtonePlayer.saveCustom(b64)
        @JavascriptInterface fun pickSystemRingtone(current: String) {
            runOnUiThread {
                try {
                    val i = android.content.Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Ringtone")
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    if (current.isNotEmpty()) i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(current))
                    pickRingtone.launch(i)
                } catch (e: Exception) {}
            }
        }
        @JavascriptInterface fun answer(id: String) = SipManager.answer(id)
        @JavascriptInterface fun decline(id: String) = SipManager.decline(id)
        @JavascriptInterface fun hangup(id: String) = SipManager.hangup(id)
        @JavascriptInterface fun setMute(id: String, on: Boolean) = SipManager.setMute(id, on)
        @JavascriptInterface fun dtmf(id: String, digit: String) = SipManager.dtmf(id, digit)
        @JavascriptInterface fun transfer(id: String, target: String): Boolean = SipManager.transfer(id, target)
        @JavascriptInterface fun barge(target: String): Boolean = SipManager.barge(target)
        @JavascriptInterface fun replace(target: String): Boolean = SipManager.replace(target)
        @JavascriptInterface fun setRecording(on: Boolean) = SipManager.setRecording(on)
        @JavascriptInterface fun listRecordings(): String = SipManager.listRecordings()
        @JavascriptInterface fun deleteRecording(name: String): Boolean = SipManager.deleteRecording(name)
        @JavascriptInterface fun shareRecording(name: String) {
            val f = File(SipManager.recDir(), File(name).name)
            if (f.exists()) runOnUiThread { shareFile(f, "audio/wav") }
        }
        @JavascriptInterface fun shareText(fileName: String, text: String) {
            val f = File(cacheDir, File(fileName).name)
            f.writeText(text)
            runOnUiThread { shareFile(f, if (fileName.endsWith(".csv")) "text/csv" else "text/plain") }
        }
        @JavascriptInterface fun appVersion(): Int = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (e: Exception) { 0 }
        @JavascriptInterface fun deviceId(): String =
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        @JavascriptInterface fun openUrl(url: String) {
            runOnUiThread { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {} }
        }
        @JavascriptInterface fun setSpeaker(on: Boolean) = SipManager.setSpeaker(on)
        @JavascriptInterface fun holdStart(id: String, listen: Boolean): Boolean = SipManager.holdStart(id, listen)
        @JavascriptInterface fun holdStop(id: String) = SipManager.holdStop(id)
        @JavascriptInterface fun setHoldListen(on: Boolean) = SipManager.setHoldListen(on)
        @JavascriptInterface fun saveHoldWav(b64: String, loop: Boolean) = SipManager.saveHoldWav(b64, loop)
        @JavascriptInterface fun clearHold() = SipManager.clearHold()
        @JavascriptInterface fun ready() { SipManager.ready() }
        @JavascriptInterface fun lockScreenStatus(): String = org.json.JSONObject()
            .put("notifications", notificationsOn()).put("fullScreen", canFullScreen()).toString()
        @JavascriptInterface fun openNotificationSettings() {
            runOnUiThread {
                try {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                } catch (e: Exception) {
                    try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e2: Exception) {}
                }
            }
        }
        @JavascriptInterface fun openFullScreenSettings() {
            runOnUiThread {
                try {
                    if (Build.VERSION.SDK_INT >= 34) startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                    else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                } catch (e: Exception) {}
            }
        }
    }
}
