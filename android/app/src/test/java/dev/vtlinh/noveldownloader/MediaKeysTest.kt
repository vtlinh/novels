package dev.vtlinh.noveldownloader

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/* What a headset's buttons do to a read in progress.

   There is no way to press an earbud from a test, so the deciding lives where
   it can be asked instead. */
class MediaKeysTest {

    private fun press(keyCode: Int) =
        MediaKeys.want(keyCode, KeyEvent.ACTION_DOWN, 0)

    /* THE DEFECT. MediaSessionCompat resolves a headset press against the
       PlaybackState it was last told — a COPY of `speaking` — and calls the
       branch it picks a third of a second later. When the copy has fallen
       behind it picks the branch that then does nothing: onPlay is ignored
       while already speaking, onPause while already paused. The press
       vanishes, nothing corrects the copy afterwards, and the button stays
       dead for the rest of the session — which is what an earbud that no
       longer pauses or resumes looks like from the outside.

       Deciding from `speaking` itself means a toggle always does one of the
       two, and there is nothing left for a stale copy to get wrong. */
    @Test
    fun `a toggle always does one of the two, whatever a stale state would say`() {
        assertEquals(MediaKeys.Act.PAUSE, MediaKeys.act(MediaKeys.Want.TOGGLE, speaking = true))
        assertEquals(MediaKeys.Act.START, MediaKeys.act(MediaKeys.Want.TOGGLE, speaking = false))
    }

    /* An earbud with dedicated keys sends the one it means. Treating those as
       a toggle let a PLAY press stop a read that was already going. */
    @Test
    fun `a dedicated play cannot stop a read that is already going`() {
        assertEquals(MediaKeys.Act.NOTHING, MediaKeys.act(MediaKeys.Want.PLAY, speaking = true))
        assertEquals(MediaKeys.Act.START, MediaKeys.act(MediaKeys.Want.PLAY, speaking = false))
    }

    @Test
    fun `a dedicated pause cannot start one that is not`() {
        assertEquals(MediaKeys.Act.PAUSE, MediaKeys.act(MediaKeys.Want.PAUSE, speaking = true))
        assertEquals(MediaKeys.Act.NOTHING, MediaKeys.act(MediaKeys.Want.PAUSE, speaking = false))
    }

    @Test
    fun `the keys a headset actually sends`() {
        assertEquals(MediaKeys.Want.PLAY, press(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(MediaKeys.Want.PAUSE, press(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(MediaKeys.Want.PAUSE, press(KeyEvent.KEYCODE_MEDIA_STOP))
        assertEquals(MediaKeys.Want.TOGGLE, press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        /* the single button on a one-button headset */
        assertEquals(MediaKeys.Want.TOGGLE, press(KeyEvent.KEYCODE_HEADSETHOOK))
        /* the double/triple tap on a one-button headset */
        assertEquals(MediaKeys.Want.NEXT, press(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(MediaKeys.Want.PREV, press(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
    }

    /* A skip moves the reading spot whether or not it is being read out —
       paused, it moves where the next play resumes, so it must never be
       swallowed by the not-speaking state the way a dedicated pause is. */
    @Test
    fun `a skip works speaking or paused`() {
        assertEquals(MediaKeys.Act.NEXT_PARA, MediaKeys.act(MediaKeys.Want.NEXT, speaking = true))
        assertEquals(MediaKeys.Act.NEXT_PARA, MediaKeys.act(MediaKeys.Want.NEXT, speaking = false))
        assertEquals(MediaKeys.Act.PREV_PARA, MediaKeys.act(MediaKeys.Want.PREV, speaking = true))
        assertEquals(MediaKeys.Act.PREV_PARA, MediaKeys.act(MediaKeys.Want.PREV, speaking = false))
    }

    @Test
    fun `keys that are not ours are left alone`() {
        assertNull(press(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
    }

    /* One dispatch per press. The UP half is the same press ending, and a
       held button auto-repeats — acting on either would toggle the read
       twice for one press, which is indistinguishable from ignoring it. */
    @Test
    fun `one press is one dispatch`() {
        assertNull(
            "the UP half is not a second press",
            MediaKeys.want(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_UP, 0),
        )
        assertNull(
            "a held button is not a stream of presses",
            MediaKeys.want(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN, 3),
        )
    }
}
