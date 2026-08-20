package dev.vtlinh.noveldownloader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* Top-level list of pasted documents. Same chrome as Library: hamburger,
   title, and a single action on the right (here, new document). */
class DocumentListActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private var folderKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_documents)
        folderKey = prefs.getString("tree", null)
        Nav.bindDrawer(this, Nav.Screen.DOCUMENTS)
        findViewById<Button>(R.id.addBtn).setOnClickListener { newDocument() }
    }

    override fun onResume() {
        super.onResume()
        val tree = prefs.getString("tree", null)
        if (tree != folderKey) folderKey = tree
        render()
    }

    private fun newDocument() {
        if (folderKey == null) {
            Toast.makeText(this, "Pick a download folder first — it's in Settings", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        startActivity(Intent(this, DocumentEditActivity::class.java))
    }

    private fun render() {
        val list = findViewById<LinearLayout>(R.id.documentList)
        val status = findViewById<TextView>(R.id.statusText)
        if (folderKey == null) {
            list.removeAllViews()
            status.text = "Pick a download folder first."
            return
        }
        val stillGranted = try {
            contentResolver.persistedUriPermissions.any {
                it.isReadPermission && it.uri.toString() == folderKey
            }
        } catch (e: Exception) { true }
        if (!stillGranted) {
            list.removeAllViews()
            status.text = "The download folder is no longer available — pick it again in Settings."
            return
        }
        status.text = "Loading…"
        val tree = folderKey ?: return
        lifecycleScope.launch {
            val items = try {
                withContext(Dispatchers.IO) { DocumentFiles.list(this@DocumentListActivity, Uri.parse(tree)) }
            } catch (e: Exception) {
                status.text = "List error: ${e.message}"
                return@launch
            }
            list.removeAllViews()
            if (items.isEmpty()) {
                status.text = "No documents yet. Tap + to add one."
                return@launch
            }
            status.text = "${items.size} document(s)"
            for (item in items) {
                list.addView(row(item))
            }
        }
    }

    private fun row(item: Documents.Item): TextView =
        TextView(this).apply {
            text = item.title
            textSize = 16f
            setTextColor(getColor(R.color.fg))
            setPadding(0, dp(14), 0, dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                DocumentFiles.openReader(this@DocumentListActivity, item.title, item.plainName)
            }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
