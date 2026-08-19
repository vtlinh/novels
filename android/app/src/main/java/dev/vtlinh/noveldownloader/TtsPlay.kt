package dev.vtlinh.noveldownloader

/* What the reader's play button shows, and whether a press may start reading.

   There is no way to bind a real TTS engine from a test, and the answer it
   gives is the whole difficulty: OnInit SUCCESS means the engine connected,
   not that it can say a word. Voice data loads lazily, and until it does the
   picker lists only "Default" with "No voices yet…".

   THE DEFECT. The button treated that connect as ready — hid the spinner,
   showed the triangle, and a press set speaking. The sentence highlighted,
   the button became pause bars, and nothing was read, because there was
   still no voice. A play that cannot speak is not a play. */
object TtsPlay {

    enum class Face { SPINNER, PLAY, PAUSE }

    /* Engine connected AND at least one voice the reader would offer. The
       locale default ("Default" in the picker) is a voice the engine already
       has, not an empty list — an empty list is "not yet", not a choice. */
    fun canSpeak(engineReady: Boolean, hasVoices: Boolean): Boolean =
        engineReady && hasVoices

    /* Speaking is pause bars so a stuck session can still be stopped. Anything
       short of being able to speak is the spinner. The triangle is only for a
       reader that can actually start. */
    fun face(speaking: Boolean, engineReady: Boolean, hasVoices: Boolean): Face = when {
        speaking -> Face.PAUSE
        !canSpeak(engineReady, hasVoices) -> Face.SPINNER
        else -> Face.PLAY
    }
}
