package dev.vtlinh.noveldownloader

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/* A silent audio track, played from THIS process for as long as the reader is
   speaking. It exists to be counted.

   Android does not hand the headset's buttons to whichever app asks nicely.
   It hands them to a media session belonging to a UID that is ACTUALLY
   PLAYING AUDIO: the framework keeps a list of audio-playback clients, walks
   it newest first, and the first UID on it that owns a session becomes the
   one media keys are delivered to. Nothing else nominates a session, and
   holding audio focus is not the same thing — the code here assumed it was,
   in as many words, for as long as the buttons have been broken.

   This app never appeared on that list. TextToSpeech.speak renders in the
   engine's own process, so every sound this reader has ever made was made
   under com.google.android.tts's UID; ours has been silent since install. Our
   session was therefore never eligible, the presses went to whatever app was
   eligible, and the earbuds did nothing at all — which no amount of fixing
   what the session DOES with a press could ever have changed, because no
   press was arriving. The on-screen button kept working throughout, which is
   exactly the shape of a delivery fault rather than a decision one.

   So: make a sound. Zeros, at the same usage and content type the speech
   goes out with, written once and looped inside the audio HAL — no thread of
   ours, no wakeups, nothing audible.

   Only while speaking. The choice of session is revisited when some app
   STARTS playing, not when one stops, so ownership survives a pause without
   holding the audio path open through it; something else beginning playback
   takes the buttons away, which is what should happen and what would happen
   to any other player. */
class Silence(private val attrs: AudioAttributes) {

    private var track: AudioTrack? = null

    /* One second of it, looped for ever. MODE_STATIC so the buffer is handed
       over once rather than fed. */
    private val rate = 8_000
    private val frames = rate

    fun start() {
        if (track != null) return
        track = try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(frames * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            /* the samples are zeros; the volume is left alone deliberately,
               since what has to be true is that a player of ours is RUNNING */
            t.write(ShortArray(frames), 0, frames)
            t.setLoopPoints(0, frames, -1)
            t.play()
            t
        } catch (e: Exception) {
            /* A device that won't give us a track is a device where the
               buttons go on being routed elsewhere. Nothing else breaks. */
            null
        }
    }

    fun stop() {
        val t = track ?: return
        track = null
        try { t.stop() } catch (e: Exception) {}
        try { t.release() } catch (e: Exception) {}
    }
}
