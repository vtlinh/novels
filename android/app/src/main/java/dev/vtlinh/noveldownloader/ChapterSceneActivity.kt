package dev.vtlinh.noveldownloader

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/* One chapter's scene: a Composer summary (plot + characters + setting)
   and, only when asked, an illustration from a stronger model.

   Both calls use the Cursor API key from Settings and spend that
   account's included Ultra usage first. The picture is never started
   by the summary. Results live in scenes/ next to the chapters so a
   re-open is free. A .work.json lets a killed poll finish on return. */
class ChapterSceneActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private val store by lazy { DownloadStore(this) }

    private lateinit var folder: String
    private lateinit var dirName: String
    private lateinit var slug: String
    private lateinit var chapter: String
    private lateinit var title: String

    private var scene: Scenes.Scene? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chapter_scene)
        folder = prefs.getString("tree", null) ?: return finish()
        dirName = intent.getStringExtra("dir") ?: return finish()
        slug = intent.getStringExtra("slug") ?: return finish()
        chapter = intent.getStringExtra("chapter") ?: return finish()
        title = intent.getStringExtra("title") ?: dirName
        findViewById<TextView>(R.id.sceneTitle).text = Scenes.chapterBase(chapter)
        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<Button>(R.id.summarizeBtn).setOnClickListener { summarize() }
        findViewById<Button>(R.id.imageBtn).setOnClickListener { illustrate() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun status(s: String) {
        findViewById<TextView>(R.id.statusText).text = s
    }

    private fun cursorKey() = (prefs.getString("cursorApiKey", "") ?: "").trim()

    private fun load() {
        if (busy) return
        lifecycleScope.launch {
            val got = withContext(Dispatchers.IO) { readDisk() }
            scene = got.scene
            showScene(got.scene)
            showImage(got.image)
            val work = got.work
            when {
                work == null -> {
                    status(
                        if (got.scene == null) "Uses your Cursor plan. The image waits until you ask."
                        else if (got.image == null) "Summary saved. Generate an image only if you want one."
                        else "Saved for this chapter.",
                    )
                    setBusy(false)
                }
                work.kind == Scenes.KIND_SUMMARY -> {
                    status("Finishing the summary Cursor already started…")
                    resume(work)
                }
                else -> {
                    status("Finishing the image Cursor already started…")
                    resume(work)
                }
            }
        }
    }

    private class Disk(
        val scene: Scenes.Scene?,
        val image: ByteArray?,
        val work: Scenes.Work?,
    )

    private fun readDisk(): Disk {
        val dir = scenesDir(create = false)
        if (dir == null) return Disk(null, null, null)
        val scene = readNamed(dir, Scenes.jsonName(chapter))?.let { Scenes.parse(it) }
        val image = readBytes(dir, Scenes.imageName(chapter))
        val work = readNamed(dir, Scenes.workName(chapter))?.let { Scenes.parseWork(it) }
        return Disk(scene, image, work)
    }

    private fun showScene(s: Scenes.Scene?) {
        findViewById<TextView>(R.id.summaryText).text =
            s?.summary?.ifBlank { "—" } ?: "No summary yet."
        findViewById<TextView>(R.id.charactersText).text =
            s?.characters?.ifBlank { "—" } ?: "—"
        findViewById<TextView>(R.id.backgroundText).text =
            s?.background?.ifBlank { "—" } ?: "—"
        findViewById<Button>(R.id.summarizeBtn).text =
            if (s == null) "Summarize this chapter" else "Summarize again"
        findViewById<Button>(R.id.imageBtn).isEnabled = s != null && !busy
    }

    private fun showImage(bytes: ByteArray?) {
        val img = findViewById<ImageView>(R.id.sceneImage)
        if (bytes == null || bytes.isEmpty()) {
            img.setImageBitmap(null)
            img.visibility = View.GONE
            findViewById<Button>(R.id.imageBtn).text = "Generate image"
            return
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bmp == null) {
            img.visibility = View.GONE
            return
        }
        img.setImageBitmap(bmp)
        img.visibility = View.VISIBLE
        findViewById<Button>(R.id.imageBtn).text = "Generate image again"
    }

    private fun summarize() {
        val key = cursorKey()
        if (key.isEmpty()) {
            status("Set your Cursor API key in Settings.")
            return
        }
        if (busy) return
        setBusy(true)
        status("Summarizing with a cheap Cursor model — a few minutes. Stay on this screen.")
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO + NonCancellable) {
                try {
                    val text = chapterText()
                        ?: return@withContext Result.failure<Scenes.Scene>(
                            IOException("Could not read this chapter."),
                        )
                    val agent = CursorAgent(key)
                    val work = agent.startSummary(title, text)
                    writeWork(work)
                    val scene = agent.finishSummary(work.agentId, work.runId)
                    saveScene(scene)
                    clearWork()
                    Result.success(scene)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            setBusy(false)
            outcome.fold(
                onSuccess = {
                    scene = it
                    showScene(it)
                    status("Summary saved. Generate an image only if you want one.")
                },
                onFailure = { status(it.message ?: "Could not summarize.") },
            )
        }
    }

    private fun illustrate() {
        val key = cursorKey()
        if (key.isEmpty()) {
            status("Set your Cursor API key in Settings.")
            return
        }
        val have = scene
        if (have == null) {
            status("Summarize this chapter first.")
            return
        }
        if (busy) return
        setBusy(true)
        status("Generating an image with a better model — a few minutes. Stay on this screen.")
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO + NonCancellable) {
                try {
                    val agent = CursorAgent(key)
                    val work = agent.startImage(title, have)
                    writeWork(work)
                    val bytes = agent.finishImage(work.agentId, work.runId)
                    saveImage(bytes)
                    clearWork()
                    Result.success(bytes)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            setBusy(false)
            outcome.fold(
                onSuccess = {
                    showImage(it)
                    status("Image saved for this chapter.")
                },
                onFailure = { status(it.message ?: "Could not generate an image.") },
            )
        }
    }

    private fun resume(work: Scenes.Work) {
        val key = cursorKey()
        if (key.isEmpty()) {
            status("Set your Cursor API key in Settings.")
            return
        }
        if (busy) return
        setBusy(true)
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO + NonCancellable) {
                try {
                    val agent = CursorAgent(key)
                    if (work.kind == Scenes.KIND_IMAGE) {
                        val bytes = agent.finishImage(work.agentId, work.runId)
                        saveImage(bytes)
                        clearWork()
                        Result.success(Pair<Scenes.Scene?, ByteArray?>(null, bytes))
                    } else {
                        val scene = agent.finishSummary(work.agentId, work.runId)
                        saveScene(scene)
                        clearWork()
                        Result.success(Pair<Scenes.Scene?, ByteArray?>(scene, null))
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            setBusy(false)
            outcome.fold(
                onSuccess = { (s, img) ->
                    if (s != null) {
                        scene = s
                        showScene(s)
                        status("Summary saved. Generate an image only if you want one.")
                    }
                    if (img != null) {
                        showImage(img)
                        status("Image saved for this chapter.")
                    }
                },
                onFailure = { status(it.message ?: "Could not finish what Cursor started.") },
            )
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        findViewById<Button>(R.id.summarizeBtn).isEnabled = !b
        findViewById<Button>(R.id.imageBtn).isEnabled = !b && scene != null
    }

    /* Prefer the English file when one exists — the image prompt is English. */
    private fun chapterText(): String? {
        val treeUri = Uri.parse(folder)
        val order = try { store.getChapterOrder(folder, slug) } catch (e: Exception) { emptyMap() }
        val ch = try {
            ChapterListActivity.chapterNames(this, treeUri, dirName, order, slug)
        } catch (e: Exception) { null } ?: return null
        val ref = ch.translated[chapter] ?: ch.source[chapter] ?: return null
        return try {
            if (Zips.isGzRef(ref)) Zips.readGz(contentResolver, treeUri, Zips.gzDocId(ref))
            else Saf.readText(contentResolver, treeUri, ref)
        } catch (e: Exception) { null }
    }

    private fun scenesDir(create: Boolean): DocumentFile? {
        val tree = DocumentFile.fromTreeUri(this, Uri.parse(folder)) ?: return null
        val novel = tree.findFile(dirName)?.takeIf { it.isDirectory } ?: return null
        val existing = novel.findFile(Scenes.DIR)?.takeIf { it.isDirectory }
        if (existing != null || !create) return existing
        return novel.createDirectory(Scenes.DIR)
    }

    private fun saveScene(scene: Scenes.Scene) {
        val dir = scenesDir(create = true) ?: throw IOException("Could not create scenes/.")
        if (!writeText(dir, Scenes.jsonName(chapter), Scenes.encode(scene))) {
            throw IOException("Could not save the summary.")
        }
        try { store.forgetDiskBytes(folder, slug) } catch (e: Exception) {}
    }

    private fun saveImage(bytes: ByteArray) {
        val dir = scenesDir(create = true) ?: throw IOException("Could not create scenes/.")
        if (!writeBytes(dir, Scenes.imageName(chapter), "image/png", bytes)) {
            throw IOException("Could not save the image.")
        }
        try { store.forgetDiskBytes(folder, slug) } catch (e: Exception) {}
    }

    private fun writeWork(work: Scenes.Work) {
        val dir = scenesDir(create = true) ?: return
        writeText(dir, Scenes.workName(chapter), Scenes.encodeWork(work))
    }

    private fun clearWork() {
        val dir = scenesDir(create = false) ?: return
        dir.findFile(Scenes.workName(chapter))?.delete()
    }

    private fun readNamed(dir: DocumentFile, name: String): String? {
        val f = dir.findFile(name) ?: return null
        return try {
            contentResolver.openInputStream(f.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) { null }
    }

    private fun readBytes(dir: DocumentFile, name: String): ByteArray? {
        val f = dir.findFile(name) ?: return null
        return try {
            contentResolver.openInputStream(f.uri)?.use { it.readBytes() }
        } catch (e: Exception) { null }
    }

    /* Write under a name nothing adopts, then rename into place — the same
       as a translated chapter. A kill mid-write otherwise leaves a short
       file this screen would treat as the finished scene. */
    private fun writeText(dir: DocumentFile, name: String, text: String): Boolean =
        writeBytes(dir, name, "application/json", text.toByteArray(Charsets.UTF_8))

    private fun writeBytes(
        dir: DocumentFile,
        name: String,
        mime: String,
        bytes: ByteArray,
    ): Boolean {
        return try {
            dir.findFile(name)?.delete()
            val f = dir.createFile(mime, Zips.partName(name)) ?: return false
            try {
                contentResolver.openOutputStream(f.uri)?.use { it.write(bytes) }
                    ?: throw IOException("could not open $name")
                val done = DocumentsContract.renameDocument(contentResolver, f.uri, name)
                    ?: throw IOException("could not name $name")
                val got = Zips.docName(contentResolver, done)
                if (got != null && got != name) {
                    try { DocumentsContract.deleteDocument(contentResolver, done) } catch (e: Exception) {}
                    return false
                }
                true
            } catch (e: Exception) {
                try { f.delete() } catch (e2: Exception) {}
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
