package com.macsstop.dialer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
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

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startSipService()
        askBatteryExemption()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }

        SipManager.init(this)

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
    }

    override fun onResume() {
        super.onResume()
        SipManager.ready()
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
    }
}
