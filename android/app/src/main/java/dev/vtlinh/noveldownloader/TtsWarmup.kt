package dev.vtlinh.noveldownloader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/* Loads Google TTS when the app comes to the foreground — the same
   lifecycle as the self-updater.

   THE DEFECT. Voice data is not present just because the engine is
   installed. OnInit SUCCESS means the service connected; voices arrive
   later, and after a Google TTS update they often never arrive at all
   until something binds and asks. The only thing that asked was the
   reader's settings sheet, so a return to the library left TTS unloaded
   and the next play sat on the spinner until that sheet was opened. A
   foreground check can do the same bind without being asked. */
object TtsWarmup {

    private const val GOOGLE_TTS = "com.google.android.tts"

    private val handler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var connecting = false
    private var ticks = 0
    private var app: Context? = null

    private val poll = Runnable { tick() }

    /* Bind (or keep asking) whenever the process is visible. Skip when
       voices are already on this engine — a fresh bind every minute was
       how the updater is throttled, but here the work is "until loaded". */
    fun onForeground(context: Context) {
        app = context.applicationContext
        /* A live reader already binds (and rebinds) for itself. Starting a
           second engine beside it is how a play went silent on one device. */
        if (ReaderActivity.isOpen()) return
        if (TtsPlay.canSpeak(!connecting && engine != null, hasVoices())) return
        if (connecting) return
        ticks = 0
        bind()
    }

    /* Drop the engine when the process backgrounds, or when a reader is
       about to bind its own — two connections is how a play went silent.
       The next foreground binds again if no reader is open. */
    fun onBackground() {
        handler.removeCallbacks(poll)
        release()
        connecting = false
        ticks = 0
    }

    private fun hasVoices(): Boolean = try {
        engine?.voices.orEmpty().any { !isNetwork(it) }
    } catch (e: Exception) { false }

    private fun isNetwork(v: android.speech.tts.Voice): Boolean =
        try { v.isNetworkConnectionRequired } catch (e: Exception) { false } ||
            Voices.isNetworkName(v.name.orEmpty())

    private fun bind() {
        val ctx = app ?: return
        connecting = true
        val listener = TextToSpeech.OnInitListener { st ->
            connecting = false
            if (st != TextToSpeech.SUCCESS) {
                handler.post {
                    release()
                    if (TtsPlay.shouldKeepPolling(ticks, hasVoices = false)) {
                        ticks++
                        handler.postDelayed({ bind() }, TtsPlay.LOAD_POLL_MS)
                    }
                }
                return@OnInitListener
            }
            /* Asking for the two languages we speak is what makes the
               engine start downloading voice data it has not yet loaded —
               the same kick opening the system TTS settings gives, without
               opening that screen. */
            try {
                engine?.setLanguage(Locale.US)
                engine?.setLanguage(Locale("vi", "VN"))
            } catch (e: Exception) {}
            handler.removeCallbacks(poll)
            handler.post(poll)
        }
        val next = TextToSpeech(ctx, listener, GOOGLE_TTS)
        /* Replace after construct: the failure callback may run inside
           the constructor and must not shut down a previous engine we
           still need. */
        val prev = engine
        engine = next
        if (prev != null && prev !== next) {
            try { prev.shutdown() } catch (e: Exception) {}
        }
    }

    private fun tick() {
        if (hasVoices()) {
            /* Keep the engine. Releasing it is what made voices vanish
               again on the next bind; the next foreground finds them
               already here. */
            ticks = 0
            return
        }
        ticks++
        if (!TtsPlay.shouldKeepPolling(ticks, hasVoices = false)) return
        if (TtsPlay.shouldRebind(ticks, hasVoices = false, connecting)) {
            try { engine?.shutdown() } catch (e: Exception) {}
            engine = null
            bind()
            return
        }
        handler.postDelayed(poll, TtsPlay.LOAD_POLL_MS)
    }

    private fun release() {
        handler.removeCallbacks(poll)
        try { engine?.shutdown() } catch (e: Exception) {}
        engine = null
    }
}
