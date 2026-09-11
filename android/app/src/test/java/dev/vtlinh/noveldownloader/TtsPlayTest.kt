package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/* What the reader's play button shows while TTS is coming up.

   There is no way to bind a real engine from a test, so the deciding lives
   where it can be asked instead. */
class TtsPlayTest {

    /* THE DEFECT. OnInit SUCCESS is not "can speak": voice data loads later,
       and until it does the picker is "Default" / "No voices yet…". Treating
       the connect as ready hid the spinner, showed the triangle, and a press
       flipped the button to pause and highlighted a sentence that was never
       read. */
    @Test
    fun `a connected engine with no voices is still the spinner`() {
        assertFalse(
            "an empty voice list is not a selected voice",
            TtsPlay.canSpeak(engineReady = true, hasVoices = false),
        )
        assertEquals(
            TtsPlay.Face.SPINNER,
            TtsPlay.face(speaking = false, engineReady = true, hasVoices = false),
        )
    }

    @Test
    fun `binding is the spinner, and a press must not start reading`() {
        assertFalse(TtsPlay.canSpeak(engineReady = false, hasVoices = false))
        assertEquals(
            TtsPlay.Face.SPINNER,
            TtsPlay.face(speaking = false, engineReady = false, hasVoices = false),
        )
        assertFalse(
            "voices reported before the engine is not a start",
            TtsPlay.canSpeak(engineReady = false, hasVoices = true),
        )
        assertEquals(
            TtsPlay.Face.SPINNER,
            TtsPlay.face(speaking = false, engineReady = false, hasVoices = true),
        )
    }

    @Test
    fun `only a connected engine with voices may start, and then it is the triangle`() {
        assertTrue(TtsPlay.canSpeak(engineReady = true, hasVoices = true))
        assertEquals(
            TtsPlay.Face.PLAY,
            TtsPlay.face(speaking = false, engineReady = true, hasVoices = true),
        )
    }

    /* A session that somehow is already speaking has to be stoppable. The
       spinner's tap retries a bind; pause's tap stops. Showing the spinner
       over a live session would trap the user in a read they cannot halt. */
    @Test
    fun `speaking is pause bars even if the voices have since gone`() {
        assertEquals(
            TtsPlay.Face.PAUSE,
            TtsPlay.face(speaking = true, engineReady = true, hasVoices = true),
        )
        assertEquals(
            TtsPlay.Face.PAUSE,
            TtsPlay.face(speaking = true, engineReady = true, hasVoices = false),
        )
        assertEquals(
            TtsPlay.Face.PAUSE,
            TtsPlay.face(speaking = true, engineReady = false, hasVoices = false),
        )
    }

    /* ---- loading voices without opening the settings sheet ---- */

    @Test
    fun `voices already there means stop asking`() {
        assertFalse(TtsPlay.shouldKeepPolling(0, hasVoices = true))
        assertFalse(TtsPlay.shouldRebind(5, hasVoices = true, connecting = false))
    }

    @Test
    fun `a just-started load does not rebind on the first tick`() {
        assertFalse(
            "tick 0 is the bind we just did",
            TtsPlay.shouldRebind(0, hasVoices = false, connecting = false),
        )
        assertTrue(TtsPlay.shouldKeepPolling(0, hasVoices = false))
    }

    @Test
    fun `an empty list is rebound on the settings-sheet cadence`() {
        assertTrue(TtsPlay.shouldRebind(5, hasVoices = false, connecting = false))
        assertTrue(TtsPlay.shouldRebind(10, hasVoices = false, connecting = false))
        assertFalse("in between is a wait, not another bind",
            TtsPlay.shouldRebind(6, hasVoices = false, connecting = false))
        assertFalse(
            "a bind already in flight is not stacked",
            TtsPlay.shouldRebind(5, hasVoices = false, connecting = true),
        )
    }

    @Test
    fun `a load that never grows voices is given up`() {
        assertTrue(TtsPlay.shouldKeepPolling(TtsPlay.LOAD_MAX_TICKS - 1, hasVoices = false))
        assertFalse(TtsPlay.shouldKeepPolling(TtsPlay.LOAD_MAX_TICKS, hasVoices = false))
    }

    /* THE DEFECT. Returning to the app starts a warmup bind; opening the
       reader then shuts it down so the reader can own the only engine.
       OnInit still fires for the shut-down connection, and without this
       gate the SUCCESS path posted another poll that rebound beside the
       reader's — two connections, silent play. */
    @Test
    fun `a warmup does not continue once the reader is open`() {
        assertFalse(
            TtsPlay.shouldContinueWarmup(readerOpen = true, generation = 1, current = 1),
        )
        assertTrue(
            TtsPlay.shouldContinueWarmup(readerOpen = false, generation = 1, current = 1),
        )
    }

    @Test
    fun `a warmup OnInit from a dropped bind is stale`() {
        assertFalse(
            "onBackground bumped the token",
            TtsPlay.shouldContinueWarmup(readerOpen = false, generation = 0, current = 1),
        )
        assertFalse(
            TtsPlay.shouldContinueWarmup(readerOpen = true, generation = 0, current = 1),
        )
    }
}
