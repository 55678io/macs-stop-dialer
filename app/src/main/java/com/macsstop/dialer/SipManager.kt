package com.macsstop.dialer

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.linphone.core.Account
import org.linphone.core.AudioDevice
import org.linphone.core.AuthInfo
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.Player
import org.linphone.core.Reason
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns the Linphone SIP engine (UDP / TCP / TLS) and talks to the web UI through JSON events.
 * All Linphone calls run on the main thread.
 */
object SipManager {
    private const val TAG = "MacsSip"
    private const val REG_EXPIRES = 120 // seconds; a phone that disappears drops off the PBX within 2 minutes

    private var core: Core? = null
    private lateinit var appCtx: Context
    private val main = Handler(Looper.getMainLooper())

    /** Receives JSON events for the web UI. */
    var listener: ((String) -> Unit)? = null
    var onIncoming: ((String) -> Unit)? = null
    var onCallsChanged: (() -> Unit)? = null
    var onRegChanged: ((String) -> Unit)? = null

    private var regState = "None"
    private var domain = ""
    private val calls = LinkedHashMap<String, Call>()
    private val incomingIds = HashSet<String>()

    // recording
    private var recordEnabled = false
    private val recFiles = HashMap<String, File>()
    private val recStarted = HashSet<String>()

    // barge (admin joins the call as a 3-way conference)
    private val hiddenIds = HashSet<String>()
    private var bargeCallId: String? = null
    private var bargeOriginalId: String? = null

    // hold audio
    private var holdLoop = true
    private var holdListen = false
    private var holdCallId: String? = null
    private var holdPlayer: Player? = null
    private var localPlayer: MediaPlayer? = null
    private var loopTask: Runnable? = null
    private val muteBeforeHold = HashMap<String, Boolean>()

    private fun holdFile() = File(appCtx.filesDir, "hold.wav")
    fun recDir(): File = File(appCtx.filesDir, "recordings").apply { mkdirs() }

    fun init(ctx: Context) {
        if (core != null) return
        appCtx = ctx.applicationContext
        recDir()
        recordEnabled = appCtx.getSharedPreferences("sip", Context.MODE_PRIVATE).getBoolean("record", false)
        val factory = Factory.instance()
        factory.setDebugMode(false, TAG)
        val c = factory.createCore(null, null, appCtx)
        setFlag(c, true, "setNativeRingingEnabled", "enableNativeRinging")
        setFlag(c, true, "setKeepAliveEnabled", "enableKeepAlive")
        setFlag(c, true, "setVibrationOnIncomingCallEnabled", "enableVibrationOnIncomingCall")
        c.addListener(coreListener)
        c.start()
        core = c
    }

    // ---------------------------------------------------------------- listener

    private val coreListener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(
            core: Core, account: Account, state: RegistrationState?, message: String
        ) {
            regState = state?.name ?: "None"
            emit(JSONObject().put("type", "reg").put("state", regState).put("message", message))
            onRegChanged?.invoke(regState)
        }

        override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) {
            val st = state ?: return
            val id = idOf(call)

            if (id in hiddenIds) { onBargeCallState(call, id, st); return }

            when (st) {
                Call.State.IncomingReceived -> {
                    calls[id] = call
                    incomingIds.add(id)
                    emit(
                        JSONObject().put("type", "incoming").put("id", id)
                            .put("user", call.remoteAddress.username ?: "")
                            .put("display", call.remoteAddress.displayName ?: "")
                    )
                    onIncoming?.invoke(displayOf(call))
                }
                Call.State.OutgoingInit -> calls[id] = call
                Call.State.Connected -> incomingIds.remove(id)
                Call.State.StreamsRunning -> {
                    incomingIds.remove(id)
                    if (recFiles.containsKey(id) && recStarted.add(id)) {
                        try { call.startRecording() } catch (e: Exception) { Log.w(TAG, "startRecording", e) }
                    }
                }
                Call.State.End, Call.State.Error, Call.State.Released -> {
                    if (holdCallId == id) stopHoldInternal(id, call, restoreMic = false)
                    if (bargeOriginalId == id) endBarge()
                    calls.remove(id)
                    incomingIds.remove(id)
                }
                else -> {}
            }
            val ev = JSONObject().put("type", "call").put("id", id).put("state", st.name).put("message", message)
            if (st == Call.State.End || st == Call.State.Error) {
                recStarted.remove(id)
                val f = recFiles.remove(id)
                if (f != null && f.exists() && f.length() > 2000) ev.put("rec", f.name)
                else if (f != null) try { f.delete() } catch (e: Exception) {}
            }
            emit(ev)
            onCallsChanged?.invoke()
        }
    }

    private fun idOf(call: Call): String = call.callLog.callId ?: ("c" + System.identityHashCode(call))
    private fun displayOf(call: Call): String {
        val a = call.remoteAddress
        return a.displayName?.takeIf { it.isNotBlank() } ?: a.username ?: "Unknown caller"
    }

    fun hasRingingCall(): Boolean = calls.any { (id, c) -> id in incomingIds && c.state == Call.State.IncomingReceived }

    private fun emit(o: JSONObject) {
        val s = o.toString()
        main.post { listener?.invoke(s) }
    }

    // ---------------------------------------------------------------- helpers

    private fun <T> onMain(default: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return try { block() } catch (e: Exception) { Log.e(TAG, "error", e); default }
        }
        var result = default
        val latch = CountDownLatch(1)
        main.post {
            try { result = block() } catch (e: Exception) { Log.e(TAG, "error", e) } finally { latch.countDown() }
        }
        latch.await(6, TimeUnit.SECONDS)
        return result
    }

    private fun boxed(c: Class<*>): Class<*> = when (c) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> c
    }

    /** Calls a method by name, picking the overload whose parameter types fit the arguments. */
    private fun invoke(target: Any, name: String, vararg args: Any?): Any? {
        val m = target.javaClass.methods.firstOrNull { m ->
            m.name == name && m.parameterTypes.size == args.size &&
                m.parameterTypes.indices.all { i -> args[i] == null || boxed(m.parameterTypes[i]).isInstance(args[i]) }
        } ?: throw NoSuchMethodException(name)
        return m.invoke(target, *args)
    }

    private fun tryInvoke(target: Any, name: String, vararg args: Any?): Boolean =
        try { invoke(target, name, *args); true } catch (e: Exception) { false }

    private fun setFlag(target: Any, v: Boolean, vararg names: String) {
        for (n in names) if (tryInvoke(target, n, v)) return
        Log.w(TAG, "No method found for ${names.joinToString()}")
    }

    private fun createAuthInfo(f: Factory, user: String, userId: String, pass: String, dom: String): AuthInfo? {
        val methods = f.javaClass.methods.filter { it.name == "createAuthInfo" }.sortedByDescending { it.parameterTypes.size }
        for (m in methods) {
            val args: Array<Any?> = when (m.parameterTypes.size) {
                7 -> arrayOf(user, userId, pass, null, null, dom, null)
                6 -> arrayOf(user, userId, pass, null, null, dom)
                else -> continue
            }
            try { return m.invoke(f, *args) as AuthInfo } catch (e: Exception) { Log.w(TAG, "createAuthInfo", e) }
        }
        return null
    }

    private fun failReg(msg: String) {
        regState = "Failed"
        emit(JSONObject().put("type", "reg").put("state", "Failed").put("message", msg))
        onRegChanged?.invoke("Failed")
    }

    // ---------------------------------------------------------------- account

    /** Sends a real un-REGISTER (expires=0) for every account, then removes them. */
    private fun dropAccounts(c: Core, then: (() -> Unit)?) {
        val accounts = try { c.accountList } catch (e: Exception) { emptyArray<Account>() }
        if (accounts.isEmpty()) { c.clearAllAuthInfo(); then?.invoke(); return }
        for (a in accounts) {
            try {
                val p = a.params.clone()
                setFlag(p, false, "setRegisterEnabled", "enableRegister")
                a.setParams(p)
            } catch (e: Exception) { Log.w(TAG, "disable register", e) }
        }
        // give the un-REGISTER time to reach the server before the account is removed
        main.postDelayed({
            try { c.clearAccounts() } catch (e: Exception) {}
            try { c.clearAllAuthInfo() } catch (e: Exception) {}
            then?.invoke()
        }, 2500)
    }

    fun register(json: String) = onMain(Unit) {
        val c = core ?: return@onMain
        regState = "Progress"
        emit(JSONObject().put("type", "reg").put("state", "Progress").put("message", ""))
        onRegChanged?.invoke("Progress")
        dropAccounts(c) { doRegister(c, json) }
    }

    private fun doRegister(c: Core, json: String) {
        try {
            val o = JSONObject(json)
            val user = o.optString("user").trim()
            val pass = o.optString("pass")
            val authUser = o.optString("auth").trim().ifBlank { user }
            domain = o.optString("domain").trim().removePrefix("sip:").removePrefix("sips:").trimEnd('/')
            val port = o.optString("port").trim()
            val transport = when (o.optString("transport").lowercase()) {
                "tcp" -> TransportType.Tcp
                "tls" -> TransportType.Tls
                else -> TransportType.Udp
            }
            if (user.isEmpty() || domain.isEmpty()) { failReg("Enter the username and domain"); return }

            val f = Factory.instance()
            val auth = createAuthInfo(f, user, authUser, pass, domain) ?: run { failReg("Could not create login"); return }
            c.addAuthInfo(auth)

            val identity = f.createAddress("sip:$user@$domain") ?: run { failReg("Invalid username or domain"); return }
            val displayName = o.optString("displayName").trim()
            if (displayName.isNotEmpty()) identity.setDisplayName(displayName)

            val server = f.createAddress("sip:$domain" + (if (port.isNotEmpty()) ":$port" else ""))
                ?: run { failReg("Invalid domain or port"); return }
            server.setTransport(transport)

            val params = c.createAccountParams()
            params.setIdentityAddress(identity)
            if (!tryInvoke(params, "setServerAddress", server)) {
                tryInvoke(params, "setServerAddr", server.asStringUriOnly())
            }
            tryInvoke(params, "setExpires", REG_EXPIRES)
            setFlag(params, true, "setRegisterEnabled", "enableRegister")

            val stun = o.optString("stun").trim().removePrefix("stun:").removePrefix("stuns:")
            if (stun.isNotEmpty()) {
                val nat = c.createNatPolicy()
                tryInvoke(nat, "setStunServer", stun)
                setFlag(nat, true, "setStunEnabled", "enableStun")
                if (!tryInvoke(params, "setNatPolicy", nat)) tryInvoke(c, "setNatPolicy", nat)
            }

            val account = c.createAccount(params)
            c.addAccount(account)
            tryInvoke(c, "setDefaultAccount", account)
        } catch (e: Exception) {
            Log.e(TAG, "register", e)
            failReg("Registration error")
        }
    }

    fun unregister() = onMain(Unit) {
        val c = core ?: return@onMain
        dropAccounts(c) {
            regState = "Cleared"
            emit(JSONObject().put("type", "reg").put("state", "Cleared").put("message", ""))
            onRegChanged?.invoke("Cleared")
        }
    }

    // ---------------------------------------------------------------- calls

    private fun newRecFile(number: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val clean = number.replace(Regex("[^0-9+]"), "").take(20).ifEmpty { "call" }
        return File(recDir(), "rec_${stamp}_$clean.wav")
    }

    fun setRecording(on: Boolean) {
        recordEnabled = on
        appCtx.getSharedPreferences("sip", Context.MODE_PRIVATE).edit().putBoolean("record", on).apply()
    }

    fun call(target: String): String = onMain("") {
        val c = core ?: return@onMain ""
        val f = Factory.instance()
        val addr = f.createAddress(target) ?: return@onMain ""
        val params = c.createCallParams(null)
        var rec: File? = null
        if (recordEnabled && params != null) {
            rec = newRecFile(addr.username ?: "")
            tryInvoke(params, "setRecordFile", rec.absolutePath)
        }
        val call: Call? = when {
            params != null && c.javaClass.methods.any { it.name == "inviteAddressWithParams" && it.parameterTypes.size == 2 } ->
                invoke(c, "inviteAddressWithParams", addr, params) as Call?
            else -> invoke(c, "inviteAddress", addr) as Call?
        }
        if (call == null) return@onMain ""
        val id = idOf(call)
        calls[id] = call
        if (rec != null) recFiles[id] = rec
        id
    }

    fun answer(id: String) = onMain(Unit) {
        val c = core ?: return@onMain
        val call = calls[id] ?: return@onMain
        if (recordEnabled) {
            val p = c.createCallParams(call)
            if (p != null) {
                val rec = newRecFile(call.remoteAddress.username ?: "")
                tryInvoke(p, "setRecordFile", rec.absolutePath)
                recFiles[id] = rec
                if (tryInvoke(call, "acceptWithParams", p)) return@onMain
                recFiles.remove(id)
            }
        }
        call.accept()
    }

    fun decline(id: String) = onMain(Unit) { calls[id]?.decline(Reason.Declined) }
    fun hangup(id: String) = onMain(Unit) { calls[id]?.terminate() }
    fun setMute(id: String, on: Boolean) = onMain(Unit) {
        val call = calls[id] ?: return@onMain
        if (holdCallId == id) muteBeforeHold[id] = on else call.microphoneMuted = on
    }
    fun dtmf(id: String, d: String) = onMain(Unit) { if (d.isNotEmpty()) calls[id]?.sendDtmf(d[0]) }

    private fun targetUri(t: String): String =
        if (t.startsWith("sip:")) t else "sip:${t.replace(Regex("[\\s()-]"), "")}@$domain"

    /** Blind transfer. Used by "Transfer call" and by the admin's "Replace". */
    fun transfer(id: String, t: String): Boolean = onMain(false) {
        val call = calls[id] ?: return@onMain false
        val addr = Factory.instance().createAddress(targetUri(t)) ?: return@onMain false
        call.transferTo(addr) == 0
    }

    /** Replace: moves the active call to the admin's number. */
    fun replace(target: String): Boolean = onMain(false) {
        val c = core ?: return@onMain false
        val call = c.currentCall ?: calls.values.lastOrNull { it.state == Call.State.StreamsRunning } ?: return@onMain false
        val addr = Factory.instance().createAddress(targetUri(target)) ?: return@onMain false
        call.transferTo(addr) == 0
    }

    /** Barge: calls the admin and merges the admin, the agent and the customer into one conference. */
    fun barge(target: String): Boolean = onMain(false) {
        val c = core ?: return@onMain false
        if (bargeCallId != null) return@onMain false
        val original = c.currentCall ?: calls.values.lastOrNull { it.state == Call.State.StreamsRunning } ?: return@onMain false
        val addr = Factory.instance().createAddress(targetUri(target)) ?: return@onMain false
        val params = c.createCallParams(null)
        val bc: Call? = if (params != null && c.javaClass.methods.any { it.name == "inviteAddressWithParams" && it.parameterTypes.size == 2 })
            invoke(c, "inviteAddressWithParams", addr, params) as Call? else invoke(c, "inviteAddress", addr) as Call?
        if (bc == null) return@onMain false
        val bid = idOf(bc)
        hiddenIds.add(bid)
        bargeCallId = bid
        bargeOriginalId = idOf(original)
        emit(JSONObject().put("type", "barge").put("state", "calling"))
        true
    }

    private fun onBargeCallState(call: Call, id: String, st: Call.State) {
        when (st) {
            Call.State.StreamsRunning -> mergeBarge(call)
            Call.State.End, Call.State.Error, Call.State.Released -> {
                hiddenIds.remove(id)
                if (bargeCallId == id) {
                    bargeCallId = null
                    // bring the customer back if they were paused while the admin was dialled
                    val orig = bargeOriginalId?.let { calls[it] }
                    if (orig != null && orig.state == Call.State.Paused) try { orig.resume() } catch (e: Exception) {}
                    bargeOriginalId = null
                    emit(JSONObject().put("type", "barge").put("state", "ended"))
                }
            }
            else -> {}
        }
    }

    private var merged = false
    private fun mergeBarge(bc: Call) {
        if (merged) return
        val c = core ?: return
        val orig = bargeOriginalId?.let { calls[it] } ?: return
        merged = true
        try {
            var ok = tryInvoke(c, "addAllToConference")
            if (!ok) {
                val cp = try { invoke(c, "createConferenceParams", null) } catch (e: Exception) { null }
                if (cp != null) {
                    setFlag(cp, false, "setVideoEnabled", "enableVideo")
                    val conf = try { invoke(c, "createConferenceWithParams", cp) } catch (e: Exception) { null }
                    if (conf != null) ok = tryInvoke(conf, "addParticipant", orig) && tryInvoke(conf, "addParticipant", bc)
                }
            }
            emit(JSONObject().put("type", "barge").put("state", if (ok) "joined" else "failed"))
            if (!ok) { try { bc.terminate() } catch (e: Exception) {} }
        } catch (e: Exception) {
            Log.e(TAG, "merge", e)
            emit(JSONObject().put("type", "barge").put("state", "failed"))
        }
    }

    private fun endBarge() {
        merged = false
        val id = bargeCallId ?: return
        val c = core ?: return
        c.calls.firstOrNull { idOf(it) == id }?.let { try { it.terminate() } catch (e: Exception) {} }
        try { tryInvoke(c, "terminateConference") } catch (e: Exception) {}
    }

    fun setSpeaker(on: Boolean) = onMain(Unit) {
        val c = core ?: return@onMain
        val call = c.currentCall ?: calls.values.lastOrNull() ?: return@onMain
        val want = if (on) AudioDevice.Type.Speaker else AudioDevice.Type.Earpiece
        val dev = c.audioDevices.firstOrNull { it.type == want } ?: return@onMain
        call.setOutputAudioDevice(dev)
    }

    // ---------------------------------------------------------------- recordings

    fun listRecordings(): String {
        val arr = JSONArray()
        recDir().listFiles()?.filter { it.isFile && it.length() > 0 }?.sortedByDescending { it.lastModified() }?.forEach {
            arr.put(JSONObject().put("name", it.name).put("size", it.length()).put("time", it.lastModified()))
        }
        return arr.toString()
    }

    fun deleteRecording(name: String): Boolean {
        val f = File(recDir(), File(name).name)
        return f.exists() && f.delete()
    }

    // ---------------------------------------------------------------- hold audio

    fun saveHoldWav(b64: String, loop: Boolean) {
        holdLoop = loop
        try {
            holdFile().writeBytes(Base64.decode(b64, Base64.DEFAULT))
        } catch (e: Exception) { Log.e(TAG, "saveHoldWav", e) }
    }

    fun clearHold() { try { holdFile().delete() } catch (e: Exception) {} }

    /** Mutes the mic and streams the hold file to the caller. The call itself stays connected. */
    fun holdStart(id: String, listen: Boolean): Boolean = onMain(false) {
        val call = calls[id] ?: return@onMain false
        if (holdCallId != null) stopHoldInternal(holdCallId!!, calls[holdCallId!!], restoreMic = true)
        muteBeforeHold[id] = call.microphoneMuted
        call.microphoneMuted = true
        holdCallId = id
        holdListen = listen
        val file = holdFile()
        if (!file.exists() || file.length() < 100) return@onMain false

        val p = call.player
        if (p != null) {
            p.open(file.absolutePath)
            p.start()
            holdPlayer = p
            val task = object : Runnable {
                override fun run() {
                    if (holdCallId != id) return
                    try {
                        val dur = p.duration
                        val ended = p.state.name != "Playing" || (dur > 0 && p.currentPosition >= dur - 250)
                        if (ended && holdLoop) { p.seek(0); p.start() }
                    } catch (e: Exception) {}
                    main.postDelayed(this, 400)
                }
            }
            loopTask = task
            main.postDelayed(task, 800)
        }

        try {
            localPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                isLooping = holdLoop
                prepare()
                val v = if (holdListen) 1f else 0f
                setVolume(v, v)
                start()
            }
        } catch (e: Exception) { Log.w(TAG, "local hold player", e) }
        true
    }

    fun holdStop(id: String) = onMain(Unit) {
        if (holdCallId == id) stopHoldInternal(id, calls[id], restoreMic = true)
    }

    fun setHoldListen(on: Boolean) = onMain(Unit) {
        holdListen = on
        val v = if (on) 1f else 0f
        try { localPlayer?.setVolume(v, v) } catch (e: Exception) {}
    }

    private fun stopHoldInternal(id: String, call: Call?, restoreMic: Boolean) {
        loopTask?.let { main.removeCallbacks(it) }
        loopTask = null
        try { holdPlayer?.pause() } catch (e: Exception) {}
        try { holdPlayer?.close() } catch (e: Exception) {}
        holdPlayer = null
        try { localPlayer?.stop() } catch (e: Exception) {}
        try { localPlayer?.release() } catch (e: Exception) {}
        localPlayer = null
        val prev = muteBeforeHold.remove(id) ?: false
        if (restoreMic && call != null) {
            try { call.microphoneMuted = prev } catch (e: Exception) {}
        }
        holdCallId = null
    }

    // ---------------------------------------------------------------- sync

    fun ready() = main.post {
        val arr = JSONArray()
        for ((id, c) in calls) {
            arr.put(
                JSONObject().put("id", id)
                    .put("user", c.remoteAddress.username ?: "")
                    .put("display", c.remoteAddress.displayName ?: "")
                    .put("incoming", id in incomingIds || c.dir == Call.Dir.Incoming)
                    .put("state", c.state.name)
            )
        }
        listener?.invoke(
            JSONObject().put("type", "sync").put("reg", regState).put("record", recordEnabled).put("calls", arr).toString()
        )
    }
}
