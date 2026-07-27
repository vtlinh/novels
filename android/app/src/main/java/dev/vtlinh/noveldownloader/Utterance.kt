package dev.vtlinh.noveldownloader

/* Which spoken sentence a TextToSpeech callback belongs to.

   Every sentence is handed to the engine with QUEUE_FLUSH, which throws away
   whatever was mid-utterance — and that discarded utterance still reports. It
   may report onDone, or onError, depending on the engine; either way it
   arrives AFTER the replacement has started, and it looks exactly like the
   replacement finishing.

   The reader used a fixed id for every sentence, so the two were
   indistinguishable and a stale report advanced the cursor: one sentence
   skipped, unread and unmarked, every time the user sought, jumped chapter or
   pressed play. Engine-dependent, so it happens on some phones and not others.

   Tag each utterance with a counter that moves whenever what we are saying
   changes, and a report that does not carry the current tag is a report about
   something we already threw away. */
object Utterance {

    fun id(generation: Long) = "sentence-$generation"

    /* A null id is not ours to act on either: the engine is allowed to report
       without one, and there is then nothing to say it is current. */
    fun isCurrent(reported: String?, generation: Long) = reported != null && reported == id(generation)
}
