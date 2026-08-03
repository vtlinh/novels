package dev.vtlinh.noveldownloader

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* One novel's own settings, reached from the ⚙ on its chapter list.

   Two switches that outlive the screen — fetch new chapters without asking,
   and translate this novel whatever the app-wide switch says — and two actions
   on the novel itself: check the site for chapters it has gained, and throw
   the whole thing away and download it again. */
class NovelSettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private val store by lazy { DownloadStore(this) }

    private lateinit var slug: String
    private lateinit var dirName: String
    private lateinit var title: String
    private var folder: String? = null

    /* what the last load found, so the buttons don't have to ask again */
    private var rec: NovelRec? = null
    private var local = 0
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_novel_settings)
        slug = intent.getStringExtra("slug") ?: return finish()
        dirName = intent.getStringExtra("dir") ?: return finish()
        title = intent.getStringExtra("title") ?: dirName
        folder = prefs.getString("tree", null)
        findViewById<TextView>(R.id.novelTitle).text = title
        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<Button>(R.id.recheckBtn).setOnClickListener { recheck() }
        findViewById<Button>(R.id.redownloadBtn).setOnClickListener { confirmRedownload() }
    }

    /* Reload on every return: a download started from here changes the counts,
       and so does one started anywhere else while this screen was behind it. */
    override fun onResume() {
        super.onResume()
        load()
    }

    private fun status(s: String) {
        findViewById<TextView>(R.id.statusText).text = s
    }

    /* ---- loading ---- */

    private fun load() {
        val folder = this.folder ?: return status("Pick a download folder first.")
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) { readState(folder) }
            rec = state.rec
            local = state.local
            val got = state.rec
            if (got == null) {
                status("This novel isn't in the library.")
                return@launch
            }
            status(state.summary)
            bindAutoDownload(folder, state)
            bindTranslate(folder, state)
            val point = got.resume
            findViewById<TextView>(R.id.recheckNote).text =
                if (state.resumable && point != null) {
                    "Reads the site's chapter list from page ${point.page}, where it " +
                        "ended last time, rather than from the beginning."
                } else {
                    "Reads the site's whole chapter list."
                }
            findViewById<Button>(R.id.recheckBtn).isEnabled = !busy
            findViewById<Button>(R.id.redownloadBtn).isEnabled = !busy
        }
    }

    /* Everything the screen needs, read off the main thread in one go. */
    private class State(
        val rec: NovelRec?,
        val local: Int,
        val summary: String,
        val chapters: Int,
        val translated: Int,
        /* null when there was no chapter long enough to judge */
        val language: String?,
        /* would a check resume, or read the whole listing? */
        val resumable: Boolean,
    )

    private fun readState(folder: String): State {
        val rec = try { store.novel(folder, slug) } catch (e: Exception) { null }
            ?: return State(null, 0, "", 0, 0, null, false)
        val local = NovelCheck.localCount(store, folder, rec)
        val order = try { store.getChapterOrder(folder, slug) } catch (e: Exception) { emptyMap() }
        val ch = try {
            ChapterListActivity.chapterNames(this, Uri.parse(folder), dirName, order, slug)
        } catch (e: Exception) { null }
        val ordered = ch?.ordered.orEmpty()
        val translated = ordered.count { ch?.translated?.containsKey(it) == true }
        val summary = buildString {
            if (rec.total > 0) append("$local/${rec.total} chapters") else append("$local chapter(s)")
            append(if (rec.complete) " — the site has finished this novel" else " — ongoing")
        }
        return State(
            rec, local, summary, ordered.size, translated, sampleLanguage(folder, ch),
            /* the same test the check itself applies, so what the button
               promises and what it does cannot drift apart */
            Resume.mayResume(rec.resume, order.size, rec.total, local, rec.complete),
        )
    }

    /* Which language this novel is actually written in, measured over a whole
       chapter of it — the same rule the reader picks a voice with.

       Measured rather than taken from the site, because the site is a weaker
       claim: it says what the site mostly publishes, not what is in this
       folder, and a novel already downloaded WITH translation has English
       chapters sitting in a Vietnamese site's directory. The site's own flag
       is the fallback for a novel with nothing readable on disk yet.

       A few chapters are tried, not one: Voices.detect wants a chapter's worth
       of letters and answers null below that, and the first chapter of a novel
       is quite often a two-line author's note. */
    private fun sampleLanguage(
        folder: String,
        ch: ChapterListActivity.Companion.Chapters?,
    ): String? {
        val treeUri = Uri.parse(folder)
        for (name in ch?.ordered.orEmpty().take(5)) {
            val ref = ch?.source?.get(name) ?: continue
            val text = try {
                if (Zips.isGzRef(ref)) Zips.readGz(contentResolver, treeUri, Zips.gzDocId(ref))
                else Saf.readText(contentResolver, treeUri, ref)
            } catch (e: Exception) { null } ?: continue
            Voices.detect(text)?.let { return it }
        }
        return null
    }

    /* ---- the two switches ---- */

    private fun bindAutoDownload(folder: String, state: State) {
        val box = findViewById<CheckBox>(R.id.autoDownloadCheck)
        box.setOnCheckedChangeListener(null)
        box.isChecked = state.rec?.autoDownload == true
        box.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch(Dispatchers.IO) {
                try { store.setAutoDownload(folder, slug, checked) } catch (e: Exception) {}
            }
        }
    }

    /* Translation is offered only where it would do something.

       Off and untouchable when the novel is already in English — there is
       nothing to translate — and when every chapter it has is already
       translated, where the setting would change nothing until the site adds
       more. The BOX still shows what is stored in both cases: a novel whose
       translation has caught up has not stopped being a novel the user wants
       translated, and the next chapters it gains will be. */
    private fun bindTranslate(folder: String, state: State) {
        val box = findViewById<CheckBox>(R.id.translateCheck)
        val note = findViewById<TextView>(R.id.translateNote)
        val rec = state.rec
        box.setOnCheckedChangeListener(null)
        val appWide = prefs.getBoolean("translate", false)
        box.isChecked = rec?.translate ?: appWide
        val siteEnglish = rec?.url?.let { Sites.forUrl(it)?.english }
        /* The measurement first, the site's claim as the fallback — and
           "unknown" is not "English": a novel with no readable chapter yet on
           a Vietnamese site is one the user may well want translated. */
        val english = when {
            state.language != null -> state.language == "en"
            siteEnglish != null -> siteEnglish
            else -> false
        }
        val allTranslated = state.chapters > 0 && state.translated >= state.chapters
        box.isEnabled = !english && !allTranslated
        box.alpha = if (box.isEnabled) 1f else 0.5f
        note.text = when {
            english -> "This novel is already in English — there is nothing to translate."
            allTranslated ->
                "Every chapter is translated already. This turns itself back on " +
                    "when the site adds chapters that aren't."
            else ->
                "Overrides the app-wide translation switch for this novel. Chapters go " +
                    "through Claude, which is billed to your Anthropic API key."
        }
        box.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch(Dispatchers.IO) {
                try { store.setTranslate(folder, slug, checked) } catch (e: Exception) {}
            }
        }
    }

    /* ---- check for new chapters ---- */

    private fun recheck() {
        val folder = this.folder ?: return
        val rec = this.rec ?: return
        if (busy) return
        if (DownloadService.isBusy(Ownership.normKey(slug))) {
            status("This novel is downloading — a check would rename its files mid-write.")
            return
        }
        setBusy(true)
        status("Checking the site…")
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                val engine = DownloadEngine(
                    this@NovelSettingsActivity,
                    { line -> DownloadService.appendLog(line) },
                    {}, { _, _ -> },
                )
                try {
                    NovelCheck.one(engine, store, folder, rec, title, local)
                } catch (e: Exception) { null }
            }
            setBusy(false)
            if (res == null) {
                status("Couldn't read the site's chapter list — nothing was changed.")
                return@launch
            }
            val how = if (res.resumed) "" else " (read in full)"
            /* the BOX, not the snapshot the screen loaded with: ticking
               auto-download and then checking, in that order, is the obvious
               way to use this screen and the write behind the tick is
               asynchronous */
            val auto = findViewById<CheckBox>(R.id.autoDownloadCheck).isChecked
            when {
                res.missing <= 0 -> status("Up to date — ${res.total} chapters$how.")
                auto -> {
                    NovelCheck.startDownload(this@NovelSettingsActivity, res.url)
                    status("${res.missing} new chapter(s)$how — downloading.")
                }
                else -> status("${res.missing} chapter(s) missing$how — turn on auto-download, or use Download in the Library.")
            }
            load()
        }
    }

    /* ---- re-download from scratch ---- */

    private fun confirmRedownload() {
        if (busy) return
        AlertDialog.Builder(this)
            .setTitle("Re-download this novel?")
            .setMessage(
                "Every chapter of \"$title\" will be deleted from the download folder — " +
                    "including translations, which cost money to produce and will be " +
                    "bought again if translation is on. The site's chapter list is then " +
                    "read in full and the whole novel downloaded again.",
            )
            .setPositiveButton("Delete and re-download") { _, _ -> redownload() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun redownload() {
        val folder = this.folder ?: return
        val rec = this.rec ?: return
        if (DownloadService.isBusy(Ownership.normKey(slug))) {
            status("This novel is downloading already.")
            return
        }
        setBusy(true)
        status("Deleting chapters…")
        lifecycleScope.launch {
            val wiped = withContext(Dispatchers.IO) { deleteChapters(folder) }
            if (!wiped.dir) {
                setBusy(false)
                status("Couldn't open this novel's folder — nothing was deleted.")
                return@launch
            }
            val gone = wiped.gone
            withContext(Dispatchers.IO) {
                /* The index describes files that are no longer there, and the
                   resume point describes a listing we are about to read again
                   from the start. Both go, so nothing downstream can act on a
                   record of a novel that has just been emptied. The folder
                   itself and its ownership records STAY: they are what send
                   the download back into this directory rather than beside it. */
                try { store.clear(folder, slug) } catch (e: Exception) {}
                try { store.setChapterOrder(folder, slug, emptyList()) } catch (e: Exception) {}
                try { store.setResumePoint(folder, slug, null) } catch (e: Exception) {}
                try { store.setDiskCount(folder, slug, 0) } catch (e: Exception) {}
                try { store.updateNovelCheck(folder, slug, -1, false) } catch (e: Exception) {}
            }
            status("Deleted $gone file(s). Reading the chapter list…")
            /* A full check, deliberately — there is no resume point left to
               use, and this is the one moment the novel's totals and order are
               known to be worth rebuilding from scratch. The download that
               follows reads the listing too; this one is what puts an honest
               count on the screen while it runs. */
            val res = withContext(Dispatchers.IO) {
                val engine = DownloadEngine(
                    this@NovelSettingsActivity,
                    { line -> DownloadService.appendLog(line) },
                    {}, { _, _ -> },
                )
                try {
                    NovelCheck.one(engine, store, folder, rec.copy(resume = null), title, 0)
                } catch (e: Exception) { null }
            }
            setBusy(false)
            val url = res?.url ?: rec.url
            if (url.isEmpty()) {
                status("Deleted $gone file(s), but no site would answer for this novel.")
                return@launch
            }
            /* The download reads the listing itself, so it can still go ahead
               on the recorded URL when the check above came back with nothing
               — say which of the two happened rather than report "0 chapters". */
            NovelCheck.startDownload(this@NovelSettingsActivity, url)
            /* Say so when something survived. The download will find those
               files at listed chapters' names and skip them as already
               present, so they are the chapters of a re-download that was not
               one — silence there is the failure worth naming. */
            val kept = if (wiped.kept > 0) " ${wiped.kept} file(s) would not delete and were left as they are." else ""
            status(
                (
                    if (res == null) "Deleted $gone file(s) — downloading again."
                    else "Deleted $gone file(s) — downloading ${res.total} chapters again."
                    ) + kept,
            )
            load()
        }
    }

    /* How a deletion pass went. `dir` is false when the folder could not even
       be read; `kept` is the files the provider refused to remove. */
    private class Deleted(val dir: Boolean, val gone: Int, val kept: Int)

    /* Empty this novel's directory, keeping the directory itself.

       Contents rather than the folder: the directory is recorded against this
       novel and claimed in its name, and those records are what stop the next
       download building a second folder beside this one — or walking into
       another novel's. Deleting the folder and letting the download recreate
       it would put the novel wherever its title happens to compute to today,
       which for a novel that has been translated, or pushed off a colliding
       name, is somewhere else entirely.

       A refusal is REPORTED, not thrown — DocumentsContract.deleteDocument
       answers false rather than raising. Counting only the successes and
       carrying on would be the quiet failure worth avoiding here: the index is
       cleared straight after this, so a file that survived is one the download
       then finds sitting at a listed chapter's name, skips as already present,
       and leaves in place — the one chapter of a re-download that did not get
       re-downloaded, with nothing said about it. */
    private fun deleteChapters(folder: String): Deleted {
        val treeUri = Uri.parse(folder)
        val dir = try {
            Saf.children(contentResolver, treeUri, Saf.rootId(treeUri))
                .firstOrNull { it.isDir && it.name == dirName }
        } catch (e: Exception) { null } ?: return Deleted(false, 0, 0)
        val kids = try {
            Saf.children(contentResolver, treeUri, dir.docId)
        } catch (e: Exception) { return Deleted(false, 0, 0) }
        var gone = 0
        var kept = 0
        for (kid in kids) {
            /* the chapters, their compressed forms, and translated/ whole —
               a partly-emptied novel is worse than either end of this */
            val isChapter = ChapterName.RE.matches(kid.name.removeSuffix(".gz"))
            if (!isChapter && !(kid.isDir && kid.name == "translated")) continue
            val ok = try {
                DocumentsContract.deleteDocument(
                    contentResolver,
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, kid.docId),
                )
            } catch (e: Exception) { false }
            if (ok) gone++ else kept++
        }
        return Deleted(true, gone, kept)
    }

    private fun setBusy(b: Boolean) {
        busy = b
        findViewById<Button>(R.id.recheckBtn).isEnabled = !b
        findViewById<Button>(R.id.redownloadBtn).isEnabled = !b
        findViewById<CheckBox>(R.id.autoDownloadCheck).isEnabled = !b
    }
}
