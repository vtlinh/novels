package dev.vtlinh.noveldownloader

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* New or existing document. X discards; Save writes under `{root}/documents`
   (gzipped when that setting is on) and opens reading mode. */
class DocumentEditActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }

    private lateinit var titleView: TextView
    private lateinit var body: EditText

    private var defaultTitle: String = ""
    private var isNew: Boolean = true
    private var replacing: String? = null
    private var currentTitle: String = ""
    private var saving = false
    private var bodyReady = false
    private lateinit var saveBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_document_edit)
        titleView = findViewById(R.id.docTitle)
        body = findViewById(R.id.docBody)
        saveBtn = findViewById(R.id.saveBtn)

        replacing = intent.getStringExtra(Documents.EXTRA_FILE)
        isNew = replacing == null
        defaultTitle = savedInstanceState?.getString(STATE_DEFAULT)
            ?: Documents.defaultTitle(
                SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
            )
        currentTitle = savedInstanceState?.getString(STATE_TITLE)
            ?: intent.getStringExtra("title")
            ?: if (isNew) defaultTitle else (replacing?.let { Documents.stemOf(it) } ?: Documents.UNTITLED)
        titleView.text = currentTitle

        findViewById<TextView>(R.id.closeBtn).setOnClickListener { finish() }
        titleView.setOnClickListener { showTitleDialog { applyTitle(it) } }
        saveBtn.setOnClickListener { onSaveClicked() }

        /* Rotation (or process death) must not skip the read: the editor
           starts empty, and Save of that empty string would replace the
           file. Restore the body only when the previous instance had
           actually loaded it. */
        val restoredReady = savedInstanceState?.getBoolean(STATE_BODY_READY) == true
        if (isNew) {
            bodyReady = true
            savedInstanceState?.getString(STATE_BODY)?.let { body.setText(it) }
        } else if (restoredReady) {
            body.setText(savedInstanceState?.getString(STATE_BODY) ?: "")
            bodyReady = true
        } else {
            saveBtn.isEnabled = false
            loadExisting()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TITLE, currentTitle)
        outState.putString(STATE_DEFAULT, defaultTitle)
        outState.putString(STATE_BODY, body.text?.toString() ?: "")
        outState.putBoolean(STATE_BODY_READY, bodyReady)
    }

    private fun applyTitle(title: String) {
        currentTitle = title
        titleView.text = title
    }

    private fun loadExisting() {
        val tree = prefs.getString("tree", null) ?: return
        val file = replacing ?: return
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try { DocumentFiles.read(this@DocumentEditActivity, Uri.parse(tree), file) } catch (e: Exception) { null }
            }
            if (isFinishing) return@launch
            if (text == null) {
                Toast.makeText(this@DocumentEditActivity, "Could not read that document", Toast.LENGTH_LONG).show()
                return@launch
            }
            /* Always fill from the file on first arrival. Keystrokes into the
               empty editor while it was loading are not the document. */
            if (!bodyReady) body.setText(text)
            bodyReady = true
            saveBtn.isEnabled = true
        }
    }

    private fun onSaveClicked() {
        if (saving || !Documents.maySave(isNew, bodyReady)) return
        /* A new document still wearing the date stamp has not been named yet
           — ask before writing, so the folder is not full of Document 2026-08-20. */
        if (isNew && currentTitle == defaultTitle) {
            showTitleDialog { applyTitle(it); save() }
            return
        }
        save()
    }

    private fun save() {
        if (saving || !Documents.maySave(isNew, bodyReady)) return
        val tree = prefs.getString("tree", null)
        if (tree == null) {
            Toast.makeText(this, "Pick a download folder first — it's in Settings", Toast.LENGTH_LONG).show()
            return
        }
        saving = true
        saveBtn.isEnabled = false
        val title = Documents.displayTitle(currentTitle)
        applyTitle(title)
        val text = body.text?.toString() ?: ""
        val was = replacing
        lifecycleScope.launch {
            val written = withContext(Dispatchers.IO) {
                try {
                    DocumentFiles.write(this@DocumentEditActivity, Uri.parse(tree), title, text, was)
                } catch (e: Exception) { null }
            }
            if (written == null) {
                saving = false
                saveBtn.isEnabled = true
                Toast.makeText(this@DocumentEditActivity, "Could not save that document", Toast.LENGTH_LONG).show()
                return@launch
            }
            DocumentFiles.openReader(
                this@DocumentEditActivity,
                Documents.stemOf(written) ?: title,
                written,
            )
            finish()
        }
    }

    private fun showTitleDialog(onSave: (String) -> Unit) {
        val pad = dp(22)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, dp(4))
        }
        val input = EditText(this).apply {
            setText(currentTitle)
            setSelection(text.length)
            setTextColor(getColor(R.color.fg))
            setHintTextColor(getColor(R.color.muted))
            hint = "Title"
            background = getDrawable(R.drawable.bg_input)
            val p = dp(11)
            setPadding(p, p, p, p)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle("Document title")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                onSave(Documents.displayTitle(input.text?.toString() ?: ""))
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATE_TITLE = "title"
        private const val STATE_DEFAULT = "defaultTitle"
        private const val STATE_BODY = "body"
        private const val STATE_BODY_READY = "bodyReady"
    }
}
