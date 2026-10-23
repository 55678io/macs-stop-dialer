package com.macsstop.dialer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Plays the incoming-call ringtone the agent picked in Settings.
 * It plays on the phone's ringer channel (like a normal phone call), so it is clean and loud
 * and follows the phone's ring volume and silent / vibrate mode.
 *
 * Choice ids:
 *   "default"          the phone's own ringtone
 *   "builtin:<name>"   a ringtone that ships with the app (assets/ringtones/<name>.ogg)
 *   "system"           a ringtone picked from the phone's list (uri saved with it)
 *   "custom"           an audio file the agent added (saved in the app)
 */
object RingtonePlayer {
    private const val TAG = "MacsRing"
    private const val PREFS = "ring"
    private val main = Handler(Looper.getMainLooper())

    private lateinit var appCtx: Context
    private var player: MediaPlayer? = null
    private var vibrating = false
    private var previewStop: Runnable? = null

    fun init(ctx: Context) { appCtx = ctx.applicationContext }

    private fun prefs() = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun customFile(): File = File(appCtx.filesDir, "ringtone_custom")

    /** Saves the agent's choice: {"id":"builtin:marimba","uri":"","title":"Marimba","vibrate":true} */
    fun setChoice(json: String) {
        try {
            val o = JSONObject(json)
            prefs().edit()
                .putString("id", o.optString("id", "default"))
                .putString("uri", o.optString("uri", ""))
                .putBoolean("vibrate", o.optBoolean("vibrate", true))
                .apply()
        } catch (e: Exception) { Log.w(TAG, "setChoice", e) }
    }

    fun saveCustom(b64: String): Boolean = try {
        customFile().writeBytes(Base64.decode(b64, Base64.DEFAULT)); true
    } catch (e: Exception) { Log.w(TAG, "saveCustom", e); false }

    /** Title of a ringtone from the phone's list. */
    fun titleOf(uri: Uri?): String = try {
        if (uri == null) "" else RingtoneManager.getRingtone(appCtx, uri)?.getTitle(appCtx) ?: ""
    } catch (e: Exception) { "" }

    /** Copies a built-in ringtone out of the app package once, so it plays like a normal file. */
    private fun builtinFile(name: String): File {
        val dir = File(appCtx.filesDir, "ringtones").apply { mkdirs() }
        val f = File(dir, "$name.ogg")
        if (!f.exists() || f.length() == 0L) {
            appCtx.assets.open("ringtones/$name.ogg").use { input -> f.outputStream().use { input.copyTo(it) } }
        }
        return f
    }

    private fun ringerMode(): Int = try {
        appCtx.getSystemService(AudioManager::class.java).ringerMode
    } catch (e: Exception) { AudioManager.RINGER_MODE_NORMAL }

    /** Starts ringing for an incoming call. */
    fun start() = main.post {
        cancelPreview()
        stopInternal()
        val mode = ringerMode()
        if (mode == AudioManager.RINGER_MODE_SILENT) return@post
        if (prefs().getBoolean("vibrate", true) || mode == AudioManager.RINGER_MODE_VIBRATE) vibrate()
        if (mode == AudioManager.RINGER_MODE_NORMAL) play(prefs().getString("id", "default") ?: "default", prefs().getString("uri", "") ?: "", loop = true, preview = false)
    }

    /** A second call during a call: vibrate only, so the caller you are talking to is not drowned out. */
    fun startWaiting() = main.post {
        cancelPreview()
        stopInternal()
        if (ringerMode() != AudioManager.RINGER_MODE_SILENT) vibrate()
    }

    fun stop() = main.post { cancelPreview(); stopInternal() }

    /** Plays a ringtone for 6 seconds so the agent can hear it in Settings. */
    fun preview(id: String, uri: String) = main.post {
        cancelPreview()
        stopInternal()
        play(id, uri, loop = true, preview = true)
        val r = Runnable { stopInternal() }
        previewStop = r
        main.postDelayed(r, 6000)
    }

    /** Stops a Settings preview (never an incoming call's ring). */
    fun stopPreview() = main.post {
        if (previewStop != null) { cancelPreview(); stopInternal() }
    }

    private fun cancelPreview() {
        previewStop?.let { main.removeCallbacks(it) }
        previewStop = null
    }

    private fun play(id: String, uri: String, loop: Boolean, preview: Boolean) {
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (preview) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            when {
                id.startsWith("builtin:") -> {
                    val name = id.removePrefix("builtin:").replace(Regex("[^a-z0-9_]"), "")
                    mp.setDataSource(builtinFile(name).absolutePath)
                }
                id == "custom" && customFile().exists() -> mp.setDataSource(customFile().absolutePath)
                id == "system" && uri.isNotEmpty() -> mp.setDataSource(appCtx, Uri.parse(uri))
                else -> {
                    val def = RingtoneManager.getActualDefaultRingtoneUri(appCtx, RingtoneManager.TYPE_RINGTONE)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    mp.setDataSource(appCtx, def)
                }
            }
            mp.isLooping = loop
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            Log.w(TAG, "play $id", e)
            try { mp.release() } catch (e2: Exception) {}
            // the chosen file is missing or broken: fall back to the app's classic ring
            if (!id.startsWith("builtin:")) play("builtin:classic", "", loop, preview)
        }
    }

    private fun vibrator(): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) appCtx.getSystemService(VibratorManager::class.java).defaultVibrator
        else appCtx.getSystemService(Vibrator::class.java)
    } catch (e: Exception) { null }

    private fun vibrate() {
        val v = vibrator() ?: return
        try {
            val pattern = longArrayOf(0, 800, 600, 800, 1800)
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 0),
                    android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_RINGTONE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(VibrationEffect.createWaveform(pattern, 0),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            }
            vibrating = true
        } catch (e: Exception) { Log.w(TAG, "vibrate", e) }
    }

    private fun stopInternal() {
        try { player?.stop() } catch (e: Exception) {}
        try { player?.release() } catch (e: Exception) {}
        player = null
        if (vibrating) { try { vibrator()?.cancel() } catch (e: Exception) {}; vibrating = false }
    }
}
