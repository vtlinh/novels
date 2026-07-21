package dev.vtlinh.noveldownloader

import android.app.Dialog
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/* Manage the TTS "speech edits" — regex find/replace rules applied before a
   sentence is spoken. A User tab (fully editable, reorderable) and a Default
   tab (built-in rules, enable/disable only), plus a full-page add/edit form. */
class SpeechEditsActivity : AppCompatActivity() {

    private var tab = 0            // 0 = User, 1 = Default
    private var selected = -1      // selected row within the current tab
    private var userList = mutableListOf<SpeechEdit>()

    private lateinit var listBox: LinearLayout
    private lateinit var footer: TextView
    private lateinit var userTab: TextView
    private lateinit var defaultTab: TextView
    private lateinit var toolbar: LinearLayout

    /* @Voice-format import/export via the Storage Access Framework */
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> uri?.let { doExport(it) } }
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { confirmImport(it) } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun doExport(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use {
                it.write(SpeechEdits.export(userList).toByteArray())
            }
            Toast.makeText(this, "Exported ${userList.size} edits", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmImport(uri: Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't read file: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        val parsed = SpeechEdits.parse(text)
        if (parsed.isEmpty()) {
            Toast.makeText(this, "No rules found in that file", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Import speech edits")
            .setMessage("Replace your ${userList.size} user edit(s) with ${parsed.size} imported edit(s)?")
            .setPositiveButton("Replace") { _, _ ->
                userList = parsed.toMutableList()
                SpeechEdits.saveUser(this, userList)
                selected = -1
                switchTab(0)
                Toast.makeText(this, "Imported ${parsed.size} edits", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        userList = SpeechEdits.user(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
        }

        /* header */
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.card))
        }
        header.addView(
            TextView(this).apply {
                text = "←"; textSize = 20f; setTextColor(getColor(R.color.fg))
                setPadding(dp(14), dp(12), dp(10), dp(12))
                isClickable = true; isFocusable = true
                setOnClickListener { finish() }
            },
        )
        header.addView(
            TextView(this).apply {
                text = "Speech edits"; textSize = 18f
                setTextColor(getColor(R.color.fg)); setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        fun headerBtn(t: String, onTap: () -> Unit) = TextView(this).apply {
            text = t; textSize = 14f; setTextColor(getColor(R.color.accent))
            setPadding(dp(10), dp(12), dp(10), dp(12))
            isClickable = true; isFocusable = true
            setOnClickListener { onTap() }
        }
        header.addView(headerBtn("Import") { importLauncher.launch(arrayOf("*/*")) })
        header.addView(headerBtn("Export") { exportLauncher.launch("replaceeng.txt") })
        root.addView(header)

        /* User / Default tabs */
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(getColor(R.color.card))
        }
        fun tabView(label: String) = TextView(this).apply {
            text = label; textSize = 15f; gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        userTab = tabView("User").apply { setOnClickListener { switchTab(0) } }
        defaultTab = tabView("Default").apply { setOnClickListener { switchTab(1) } }
        tabs.addView(userTab)
        tabs.addView(defaultTab)
        root.addView(tabs)

        /* toolbar (User tab only): add / edit / up / down / delete */
        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(getColor(R.color.btn_secondary))
        }
        fun toolBtn(glyph: String, onTap: () -> Unit) = TextView(this).apply {
            text = glyph; textSize = 20f; gravity = Gravity.CENTER
            setTextColor(getColor(R.color.fg)); setPadding(0, dp(10), 0, dp(10))
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onTap() }
        }
        toolbar.addView(toolBtn("+") { openEditor(null) })
        toolbar.addView(toolBtn("✎") { editSelected() })
        toolbar.addView(toolBtn("↑") { moveSelected(-1) })
        toolbar.addView(toolBtn("↓") { moveSelected(1) })
        toolbar.addView(toolBtn("✕") { deleteSelected() })
        root.addView(toolbar)

        /* scrollable list */
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listBox)
        root.addView(scroll)

        /* footer count */
        footer = TextView(this).apply {
            textSize = 12f; setTextColor(getColor(R.color.muted))
            setBackgroundColor(getColor(R.color.card))
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        root.addView(footer)

        setContentView(root)
        switchTab(0)
    }

    private fun currentList(): List<SpeechEdit> =
        if (tab == 0) userList else SpeechEdits.defaults

    private fun switchTab(t: Int) {
        tab = t
        selected = -1
        val on = getColor(R.color.fg)
        val off = getColor(R.color.muted)
        userTab.setTextColor(if (t == 0) on else off)
        defaultTab.setTextColor(if (t == 1) on else off)
        userTab.setTypeface(null, if (t == 0) Typeface.BOLD else Typeface.NORMAL)
        defaultTab.setTypeface(null, if (t == 1) Typeface.BOLD else Typeface.NORMAL)
        toolbar.visibility = if (t == 0) View.VISIBLE else View.GONE
        refresh()
    }

    private fun refresh() {
        listBox.removeAllViews()
        val list = currentList()
        list.forEachIndexed { i, edit -> listBox.addView(rowView(i, edit)) }
        if (list.isEmpty()) {
            listBox.addView(
                TextView(this).apply {
                    text = if (tab == 0)
                        "No custom edits yet. Tap + to add one." else "No default edits."
                    setTextColor(getColor(R.color.muted)); textSize = 14f
                    setPadding(dp(16), dp(24), dp(16), dp(24)); gravity = Gravity.CENTER
                },
            )
        }
        val enabled = list.count { it.enabled }
        footer.text = "Enabled $enabled of ${list.size}" +
            if (selected >= 0) "  ·  selected #${selected + 1}" else ""
    }

    private fun rowView(index: Int, edit: SpeechEdit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), dp(12), dp(8))
            setBackgroundColor(
                if (index == selected) 0x334F8CFF else 0x00000000,
            )
        }
        val check = CheckBox(this).apply {
            if (tab == 0) {
                isChecked = edit.enabled
                setOnClickListener {
                    userList[index].enabled = isChecked
                    SpeechEdits.saveUser(this@SpeechEditsActivity, userList)
                    refresh()
                }
            } else {
                // defaults are hard-coded and always applied — read-only
                isChecked = true
                isEnabled = false
            }
        }
        row.addView(check)

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val line1 = edit.title.ifEmpty { edit.pattern }
        val line2 = if (edit.title.isNotEmpty()) edit.pattern else edit.replace
        texts.addView(
            TextView(this).apply {
                text = line1; textSize = 15f
                setTextColor(if (edit.enabled) getColor(R.color.fg) else getColor(R.color.muted))
                if (!edit.enabled) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            },
        )
        if (line2.isNotEmpty()) {
            texts.addView(
                TextView(this).apply {
                    text = line2; textSize = 13f; setTextColor(getColor(R.color.muted))
                    if (!edit.enabled) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                },
            )
        }
        row.addView(texts)

        row.isClickable = true
        row.setOnClickListener {
            selected = if (selected == index) -1 else index
            refresh()
        }
        if (tab == 0) {
            row.setOnLongClickListener { selected = index; editSelected(); true }
        }
        return row
    }

    private fun editSelected() {
        if (tab != 0 || selected < 0 || selected >= userList.size) return
        openEditor(userList[selected])
    }

    private fun moveSelected(delta: Int) {
        if (tab != 0 || selected < 0) return
        val to = selected + delta
        if (to < 0 || to >= userList.size) return
        val item = userList.removeAt(selected)
        userList.add(to, item)
        selected = to
        SpeechEdits.saveUser(this, userList)
        refresh()
    }

    private fun deleteSelected() {
        if (tab != 0 || selected < 0 || selected >= userList.size) return
        userList.removeAt(selected)
        selected = -1
        SpeechEdits.saveUser(this, userList)
        refresh()
    }

    /* full-page add/edit form */
    private fun openEditor(existing: SpeechEdit?) {
        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        val scroll = ScrollView(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(form)

        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 13f; setTextColor(getColor(R.color.muted))
            setPadding(0, dp(14), 0, dp(4))
        }
        fun field(value: String, hintText: String) = EditText(this).apply {
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            setTextColor(getColor(R.color.fg)); setHintTextColor(getColor(R.color.muted))
            textSize = 14f; hint = hintText
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(value)
        }

        form.addView(
            TextView(this).apply {
                text = if (existing == null) "Add speech edit" else "Edit speech edit"
                textSize = 20f; setTextColor(getColor(R.color.fg)); setTypeface(null, Typeface.BOLD)
            },
        )

        form.addView(label("Title (optional)"))
        val titleField = field(existing?.title ?: "", "")
        form.addView(titleField)

        form.addView(label("Type"))
        val typeSpinner = Spinner(this)
        typeSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Case Insensitive", "Case Sensitive", "Regular Expression"),
        )
        typeSpinner.setSelection(existing?.type ?: 2)
        form.addView(typeSpinner)

        val wholeWord = CheckBox(this).apply {
            text = "Match whole words only"; textSize = 14f; setTextColor(getColor(R.color.fg))
            setPadding(dp(6), dp(12), 0, dp(4))
            isChecked = existing?.wholeWord ?: false
        }
        form.addView(wholeWord)

        form.addView(label("Pattern"))
        val patternField = field(existing?.pattern ?: "", "text or regex to match")
        form.addView(patternField)

        form.addView(label("Replace"))
        val replaceField = field(existing?.replace ?: "", "replacement text")
        form.addView(replaceField)

        /* live test */
        form.addView(label("Test"))
        val testInput = field(
            "There is a tinge of annoyance on Azure Dragon's face.",
            "text to test against",
        )
        form.addView(testInput)
        val testResult = TextView(this).apply {
            textSize = 14f; setTextColor(getColor(R.color.accent)); setPadding(0, dp(10), 0, 0)
        }
        form.addView(testResult)

        fun previewEdit(): SpeechEdit = SpeechEdit(
            id = existing?.id ?: "preview",
            title = titleField.text.toString(),
            type = typeSpinner.selectedItemPosition,
            wholeWord = wholeWord.isChecked,
            pattern = patternField.text.toString(),
            replace = replaceField.text.toString(),
            enabled = true,
            isDefault = false,
        )
        fun updateTest() {
            val edit = previewEdit()
            val compiled = edit.compiled()
            testResult.setTextColor(getColor(if (compiled == null) R.color.err else R.color.accent))
            testResult.text = if (compiled == null) {
                "Invalid pattern"
            } else {
                try {
                    compiled.first.replace(testInput.text.toString(), compiled.second)
                } catch (e: Exception) {
                    "Invalid pattern"
                }
            }
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updateTest() }
        }
        patternField.addTextChangedListener(watcher)
        replaceField.addTextChangedListener(watcher)
        testInput.addTextChangedListener(watcher)
        wholeWord.setOnCheckedChangeListener { _, _ -> updateTest() }
        updateTest()

        /* CANCEL / SAVE */
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(20), 0, 0)
        }
        fun bigBtn(t: String) = TextView(this).apply {
            text = t; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(getColor(R.color.fg)); setTypeface(null, Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_input)
            setPadding(0, dp(14), 0, dp(14))
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val cancel = bigBtn("CANCEL").apply { setOnClickListener { dialog.dismiss() } }
        val save = bigBtn("SAVE").apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(12)
            setTextColor(getColor(R.color.accent))
            setOnClickListener {
                val edit = previewEdit()
                if (edit.pattern.isEmpty()) {
                    patternField.error = "Pattern is required"; return@setOnClickListener
                }
                if (edit.compiled() == null) {
                    patternField.error = "Invalid regex"; return@setOnClickListener
                }
                if (existing == null) {
                    userList.add(
                        edit.copy(id = SpeechEdits.newId()),
                    )
                    selected = userList.size - 1
                } else {
                    val idx = userList.indexOfFirst { it.id == existing.id }
                    if (idx >= 0) userList[idx] = edit.copy(enabled = userList[idx].enabled)
                }
                SpeechEdits.saveUser(this@SpeechEditsActivity, userList)
                dialog.dismiss()
                refresh()
            }
        }
        btnRow.addView(cancel)
        btnRow.addView(save)
        form.addView(btnRow)

        dialog.setContentView(scroll)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        )
        dialog.show()
    }
}
