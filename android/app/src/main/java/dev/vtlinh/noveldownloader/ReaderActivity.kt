package dev.vtlinh.noveldownloader

import android.net.Uri
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* Reading mode, screen 3: the reader. Starts at the tapped chapter; once the
   reader scrolls past HALF of what's loaded, the next chapter is fetched and
   appended at the bottom for a seamless read. Translated files are preferred
   over the Vietnamese source when they exist. */
class ReaderActivity : AppCompatActivity() {

    private var ordered: List<String> = emptyList()
    private var readIds: Map<String, String> = emptyMap()
    private var treeUri: Uri? = null
    private var nextIdx = 0
    @Volatile private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        val dirName = intent.getStringExtra("dir") ?: return finish()
        val novelTitle = intent.getStringExtra("title") ?: dirName
        val start = intent.getStringExtra("start") ?: return finish()
        val folder = getSharedPreferences("app", MODE_PRIVATE).getString("tree", null) ?: return finish()
        treeUri = Uri.parse(folder)

        val titleBar = findViewById<TextView>(R.id.readerTitle)
        val text = findViewById<TextView>(R.id.readerText)
        val scroll = findViewById<ScrollView>(R.id.readerScroll)
        titleBar.text = novelTitle
        text.text = "Loading…"

        lifecycleScope.launch {
            val (o, r) = withContext(Dispatchers.IO) {
                ChapterListActivity.chapterNames(contentResolver, treeUri!!, dirName)
            }
            ordered = o
            readIds = r
            nextIdx = ordered.indexOf(start).coerceAtLeast(0)
            text.text = ""
            appendNext()
        }

        /* past half of the loaded content -> load the next chapter */
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val content = text.height
            if (content > 0 && (scrollY + scroll.height) * 2 > content) appendNext()
        }
    }

    private fun appendNext() {
        if (loading || nextIdx >= ordered.size) return
        loading = true
        val name = ordered[nextIdx]
        val docId = readIds[name] ?: run { nextIdx++; loading = false; return }
        val text = findViewById<TextView>(R.id.readerText)
        val titleBar = findViewById<TextView>(R.id.readerTitle)
        val scroll = findViewById<ScrollView>(R.id.readerScroll)
        lifecycleScope.launch {
            val body = withContext(Dispatchers.IO) {
                Saf.readText(contentResolver, treeUri!!, docId)
            }
            if (body != null) {
                text.append(if (text.text.isEmpty()) body else "\n\n⁂\n\n$body")
                titleBar.text = "${intent.getStringExtra("title")} — ${name.removeSuffix(".txt")}"
            }
            nextIdx++
            loading = false
            /* short first chapters may not fill the screen — keep filling */
            scroll.post {
                if (text.height > 0 && (scroll.scrollY + scroll.height) * 2 > text.height) appendNext()
            }
        }
    }
}
