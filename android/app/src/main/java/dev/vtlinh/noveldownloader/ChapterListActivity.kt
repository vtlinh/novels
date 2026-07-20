package dev.vtlinh.noveldownloader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* Reading mode, screen 2: the chapters of one novel, in order. Tapping a
   chapter opens the reader there. */
class ChapterListActivity : AppCompatActivity() {

    companion object {
        val CHAPTER_RE = Regex("Chapter (\\d+)(?:-(\\d+))?\\.txt")

        /* ordered chapter names of a novel dir, translated preferred */
        fun chapterNames(
            cr: android.content.ContentResolver,
            treeUri: Uri,
            dirName: String,
        ): Pair<List<String>, Map<String, String>> {   // (ordered names, name -> docId to read)
            val dirs = Saf.children(cr, treeUri, Saf.rootId(treeUri))
            val dir = dirs.firstOrNull { it.isDir && it.name == dirName } ?: return Pair(emptyList(), emptyMap())
            val source = HashMap<String, String>()
            var translatedId: String? = null
            for (e in Saf.children(cr, treeUri, dir.docId)) {
                if (e.isDir && e.name == "translated") translatedId = e.docId
                else if (!e.isDir && CHAPTER_RE.matches(e.name)) source[e.name] = e.docId
            }
            val read = HashMap<String, String>(source)
            translatedId?.let {
                for (e in Saf.children(cr, treeUri, it)) {
                    if (!e.isDir && e.name in source) read[e.name] = e.docId   // prefer translated
                }
            }
            val ordered = source.keys.sortedWith(
                compareBy({ CHAPTER_RE.find(it)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE }, { it }),
            )
            return Pair(ordered, read)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chapters)
        val dirName = intent.getStringExtra("dir") ?: return finish()
        val title = intent.getStringExtra("title") ?: dirName
        findViewById<TextView>(R.id.chapterTitle).text = title
        val status = findViewById<TextView>(R.id.statusText)
        val listView = findViewById<ListView>(R.id.chapterListView)
        val folder = getSharedPreferences("app", MODE_PRIVATE).getString("tree", null) ?: return finish()

        status.text = "Loading…"
        lifecycleScope.launch {
            val (ordered, _) = withContext(Dispatchers.IO) {
                chapterNames(contentResolver, Uri.parse(folder), dirName)
            }
            if (ordered.isEmpty()) {
                status.text = "No chapters found in \"$dirName\"."
                return@launch
            }
            status.text = "${ordered.size} chapter(s)"
            val labels = ordered.map { it.removeSuffix(".txt") }
            listView.adapter = ArrayAdapter(
                this@ChapterListActivity, android.R.layout.simple_list_item_1, labels,
            )
            listView.setOnItemClickListener { _, _, pos, _ ->
                startActivity(
                    Intent(this@ChapterListActivity, ReaderActivity::class.java)
                        .putExtra("dir", dirName)
                        .putExtra("title", title)
                        .putExtra("start", ordered[pos]),
                )
            }
        }
    }
}
