package dev.vtlinh.noveldownloader

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/* Posts this chapter to Slack as {sha256}.txt. The hash is the unzipped
   UTF-8 bytes only. A Slack watcher (Cursor or ChatGPT) reads the file;
   the app does not call those APIs. */
class ChapterSceneActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private val store by lazy { DownloadStore(this) }

    private lateinit var folder: String
    private lateinit var dirName: String
    private lateinit var slug: String
    private lateinit var chapter: String

    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chapter_scene)
        folder = prefs.getString("tree", null) ?: return finish()
        dirName = intent.getStringExtra("dir") ?: return finish()
        slug = intent.getStringExtra("slug") ?: return finish()
        chapter = intent.getStringExtra("chapter") ?: return finish()
        findViewById<TextView>(R.id.sceneTitle).text = Scenes.chapterBase(chapter)
        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<Button>(R.id.slackBtn).setOnClickListener { postSlack() }
        status("Posts the unzipped chapter as {hash}.txt. Set Slack in Settings.")
    }

    private fun status(s: String) {
        findViewById<TextView>(R.id.statusText).text = s
    }

    private fun slackToken() = (prefs.getString("slackBotToken", "") ?: "").trim()
    private fun slackChannel() = (prefs.getString("slackChannelId", "") ?: "").trim()

    private fun postSlack() {
        val token = slackToken()
        val channel = slackChannel()
        if (token.isEmpty() || channel.isEmpty()) {
            status("Set the Slack bot token and channel ID in Settings.")
            return
        }
        if (busy) return
        setBusy(true)
        status("Posting the unzipped chapter as {hash}.txt…")
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val text = chapterText()
                        ?: return@withContext Result.failure<String>(
                            IOException("Could not read this chapter."),
                        )
                    if (text.isEmpty()) {
                        return@withContext Result.failure<String>(
                            IOException("This chapter is empty."),
                        )
                    }
                    Result.success(SlackPoster(token, channel).postChapter(text))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            setBusy(false)
            outcome.fold(
                onSuccess = { status("Posted $it to Slack.") },
                onFailure = { status(it.message ?: "Could not post to Slack.") },
            )
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        findViewById<Button>(R.id.slackBtn).isEnabled = !b
    }

    /* Prefer the English file when one exists. */
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
}
