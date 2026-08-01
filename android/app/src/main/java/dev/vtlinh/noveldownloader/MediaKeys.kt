package dev.vtlinh.noveldownloader

import android.view.KeyEvent

/* What a headset's buttons mean, and what the reader should do about them.

   Nothing here touches Android at run time — KeyEvent's key codes are compile
   -time constants and land in the bytecode as the numbers they are — so the
   mapping can be exercised directly. It needs to be: there is no way to press
   an earbud from a test.

   Every decision is made from `speaking` — whether the reader is actually
   reading — and from nothing else. That is the fix. Left to
   MediaSessionCompat, a PLAY_PAUSE press is resolved against the
   PlaybackState the session was last told, which is a COPY of that flag: the
   compat layer reads the copy, picks onPlay or onPause from it, and calls
   that one a third of a second later. Both halves cost presses.

   A copy that has fallen behind picks the wrong one — and the wrong one does
   not do the wrong thing, it does NOTHING, because onPlay is ignored while
   already speaking and onPause while already paused. The press vanishes,
   nothing corrects the state afterwards, and the button stays dead for the
   rest of the session: exactly what an earbud that no longer pauses or
   resumes looks like. The delay is the other half — it exists to watch for
   the second tap that means "skip to next", which a novel reader has nothing
   to do with and never implemented, so a double tap did nothing at all. */
object MediaKeys {

    /* what the key asked for */
    enum class Want { PLAY, PAUSE, TOGGLE }

    /* what to do about it */
    enum class Act { START, PAUSE, NOTHING }

    /* Which reading action a key press asks for, or null when it is not a
       press we act on.

       PLAY and PAUSE are kept apart from PLAY_PAUSE deliberately: an earbud
       with dedicated keys sends the one it means, and treating that as a
       toggle lets a PLAY press stop a read that is already going. Only the
       single-button forms toggle.

       One dispatch per press: the UP half of a press and the auto-repeats of
       a held button are not further presses. */
    fun want(keyCode: Int, action: Int, repeatCount: Int): Want? {
        if (action != KeyEvent.ACTION_DOWN || repeatCount > 0) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> Want.PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> Want.PAUSE
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> Want.TOGGLE
            else -> null
        }
    }

    /* `speaking` is the reader's own flag, never the session's copy of it */
    fun act(want: Want, speaking: Boolean): Act = when (want) {
        Want.PLAY -> if (speaking) Act.NOTHING else Act.START
        Want.PAUSE -> if (speaking) Act.PAUSE else Act.NOTHING
        Want.TOGGLE -> if (speaking) Act.PAUSE else Act.START
    }
}
