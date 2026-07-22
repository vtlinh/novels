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
   tab (built-in rules; editable via per-rule overrides, with ⟲ restoring a
   rule to its hard-coded form), plus a full-page add/edit form. */
class SpeechEditsActivity : AppCompatActivity() {

    private var tab = 0            // 0 = User, 1 = Default
    /* selected rows within the current tab (multi-select). Kept ascending so
       "move to top/bottom" preserves the items' on-screen order. Typed as the
       SortedSet interface so both sortedSetOf(...) and Range.toSortedSet()
       assign cleanly. */
    private var selected: java.util.SortedSet<Int> = sortedSetOf()
    private var userList = mutableListOf<SpeechEdit>()

    private lateinit var listBox: LinearLayout
    private lateinit var footer: TextView
    private lateinit var userTab: TextView
    private lateinit var defaultTab: TextView
    private lateinit var toolbar: LinearLayout
    private lateinit var userOnlyBtns: List<View>   // +, ↑, ↓, ✕ (User tab)
    private lateinit var restoreBtn: View           // ⟲ (Default tab)
    private var ovrIds: Set<String> = emptySet()    // overridden default ids
    /* ⋮ menu toggle: list only the enabled edits (persisted) */
    private var hideInactive = false

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
                selected = sortedSetOf()
                switchTab(0)
                Toast.makeText(this, "Imported ${parsed.size} edits", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        userList = SpeechEdits.user(this)
        hideInactive = getSharedPreferences("app", MODE_PRIVATE)
            .getBoolean("hideInactiveEdits", false)

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
        /* ⋮ context menu: import/export live behind it to keep the header clean */
        val menuBtn = TextView(this).apply {
            text = "⋮"; textSize = 20f; setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.fg))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true; isFocusable = true
        }
        menuBtn.setOnClickListener {
            val menu = android.widget.PopupMenu(this, menuBtn)
            menu.menu.add("Import…")
            menu.menu.add("Export…")
            menu.menu.add("Hide inactive").apply {
                isCheckable = true
                isChecked = hideInactive
            }
            menu.setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Import…" -> importLauncher.launch(arrayOf("*/*"))
                    "Export…" -> exportLauncher.launch("replaceeng.txt")
                    "Hide inactive" -> {
                        hideInactive = !hideInactive
                        getSharedPreferences("app", MODE_PRIVATE).edit()
                            .putBoolean("hideInactiveEdits", hideInactive).apply()
                        selected = sortedSetOf()   // hidden rows must not stay selected
                        refresh()
                    }
                }
                true
            }
            menu.show()
        }
        header.addView(menuBtn)
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

        /* toolbar: User tab gets add / edit / up / down / delete; the Default
           tab gets edit / restore (defaults are editable, ⟲ reverts one back
           to its built-in form) */
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
        val addBtn = toolBtn("+") { openEditor(null) }
        toolbar.addView(addBtn)
        toolbar.addView(toolBtn("✎") { editSelected() })
        /* short press nudges one step; long press moves all selected to the
           very top (↑) or very bottom (↓) after a confirm dialog */
        val upBtn = toolBtn("↑") { moveSelected(-1) }
        upBtn.setOnLongClickListener { confirmMoveToEdge(true); true }
        toolbar.addView(upBtn)
        val downBtn = toolBtn("↓") { moveSelected(1) }
        downBtn.setOnLongClickListener { confirmMoveToEdge(false); true }
        toolbar.addView(downBtn)
        val delBtn = toolBtn("✕") { deleteSelected() }
        toolbar.addView(delBtn)
        restoreBtn = toolBtn("⟲") { confirmRestoreSelected() }
        toolbar.addView(restoreBtn)
        userOnlyBtns = listOf(addBtn, upBtn, downBtn, delBtn)
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
        if (tab == 0) userList else SpeechEdits.effectiveDefaults(this)

    private fun switchTab(t: Int) {
        tab = t
        selected = sortedSetOf()
        val on = getColor(R.color.fg)
        val off = getColor(R.color.muted)
        userTab.setTextColor(if (t == 0) on else off)
        defaultTab.setTextColor(if (t == 1) on else off)
        userTab.setTypeface(null, if (t == 0) Typeface.BOLD else Typeface.NORMAL)
        defaultTab.setTypeface(null, if (t == 1) Typeface.BOLD else Typeface.NORMAL)
        userOnlyBtns.forEach { it.visibility = if (t == 0) View.VISIBLE else View.GONE }
        restoreBtn.visibility = if (t == 1) View.VISIBLE else View.GONE
        refresh()
    }

    private fun refresh() {
        listBox.removeAllViews()
        ovrIds = if (tab == 1) SpeechEdits.overrides(this).keys else emptySet()
        val list = currentList()
        /* a row hidden by the filter must not linger in the selection — ✕/⟲
           would silently act on rows the user can't see */
        if (hideInactive) {
            val it = selected.iterator()
            while (it.hasNext()) {
                if (list.getOrNull(it.next())?.enabled == false) it.remove()
            }
        }
        var hidden = 0
        list.forEachIndexed { i, edit ->
            /* indices stay FULL-list positions, so move/delete/restore keep
               addressing the right items while the filter is on */
            if (hideInactive && !edit.enabled) { hidden++; return@forEachIndexed }
            listBox.addView(rowView(i, edit))
        }
        if (list.isEmpty() || listBox.childCount == 0) {
            listBox.addView(
                TextView(this).apply {
                    text = when {
                        list.isNotEmpty() -> "All ${list.size} edits here are inactive (hidden by the ⋮ filter)."
                        tab == 0 -> "No custom edits yet. Tap + to add one."
                        else -> "No default edits."
                    }
                    setTextColor(getColor(R.color.muted)); textSize = 14f
                    setPadding(dp(16), dp(24), dp(16), dp(24)); gravity = Gravity.CENTER
                },
            )
        }
        val enabled = list.count { it.enabled }
        footer.text = "Enabled $enabled of ${list.size}" +
            (if (hidden > 0) "  ·  $hidden hidden" else "") +
            if (selected.isNotEmpty()) "  ·  ${selected.size} selected" else ""
    }

    private fun rowView(index: Int, edit: SpeechEdit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), dp(12), dp(8))
            setBackgroundColor(
                if (index in selected) 0x334F8CFF else 0x00000000,
            )
        }
        val check = CheckBox(this).apply {
            isChecked = edit.enabled
            setOnClickListener {
                if (tab == 0) {
                    userList[index].enabled = isChecked
                    SpeechEdits.saveUser(this@SpeechEditsActivity, userList)
                } else {
                    /* a default's on/off state is stored as an override;
                       ⟲ restore brings back the built-in (enabled) form */
                    SpeechEdits.saveOverride(
                        this@SpeechEditsActivity, edit.copy(enabled = isChecked),
                    )
                }
                refresh()
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
        if (edit.id in ovrIds) {
            texts.addView(
                TextView(this).apply {
                    text = "modified — select and tap ⟲ to restore the built-in"
                    textSize = 11f; setTextColor(getColor(R.color.accent))
                },
            )
        }
        row.addView(texts)

        row.isClickable = true
        row.setOnClickListener {
            /* tap toggles this row in/out of the multi-selection */
            if (index in selected) selected.remove(index) else selected.add(index)
            refresh()
        }
        /* long-press edits just this row (defaults are editable too) */
        row.setOnLongClickListener {
            selected = sortedSetOf(index); refresh(); editSelected(); true
        }
        return row
    }

    private fun editSelected() {
        if (selected.size != 1) {
            Toast.makeText(this, "Select a single edit to edit", Toast.LENGTH_SHORT).show()
            return
        }
        val i = selected.first()
        val list = currentList()
        if (i in list.indices) openEditor(list[i], forDefault = tab == 1)
    }

    /* ⟲ (Default tab): confirm, then drop the overrides of every selected
       default, reverting each to its hard-coded built-in form */
    private fun confirmRestoreSelected() {
        if (tab != 1) return
        val list = currentList()
        val ids = selected.mapNotNull { list.getOrNull(it)?.id }.filter { it in ovrIds }
        if (ids.isEmpty()) {
            Toast.makeText(this, "Select modified default edit(s) to restore", Toast.LENGTH_SHORT).show()
            return
        }
        val what = if (ids.size == 1) "this edit" else "these ${ids.size} edits"
        AlertDialog.Builder(this)
            .setTitle("Restore default")
            .setMessage("Restore $what back to the built-in default?")
            .setPositiveButton("Restore") { _, _ ->
                ids.forEach { SpeechEdits.clearOverride(this, it) }
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /* single-step nudge — one selected row at a time (the bulk move is the
       long-press "to top/bottom") */
    private fun moveSelected(delta: Int) {
        if (tab != 0) return
        if (selected.size != 1) {
            Toast.makeText(this, "Long-press ↑/↓ to move to the top/bottom", Toast.LENGTH_SHORT).show()
            return
        }
        val from = selected.first()
        val to = from + delta
        if (to < 0 || to >= userList.size) return
        val item = userList.removeAt(from)
        userList.add(to, item)
        selected = sortedSetOf(to)
        SpeechEdits.saveUser(this, userList)
        refresh()
    }

    /* confirm, then move every selected edit to the top (or bottom), keeping
       their relative order */
    private fun confirmMoveToEdge(top: Boolean) {
        if (tab != 0 || selected.isEmpty()) {
            Toast.makeText(this, "Select item(s) first", Toast.LENGTH_SHORT).show()
            return
        }
        val n = selected.size
        val where = if (top) "top" else "bottom"
        val what = if (n == 1) "this edit" else "these $n edits"
        AlertDialog.Builder(this)
            .setTitle("Move to $where")
            .setMessage("Move $what to the $where of the list?")
            .setPositiveButton("Move") { _, _ -> moveSelectedToEdge(top) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun moveSelectedToEdge(top: Boolean) {
        if (tab != 0 || selected.isEmpty()) return
        val idxs = selected.toList()              // ascending → on-screen order
        val items = idxs.map { userList[it] }
        for (i in idxs.sortedDescending()) userList.removeAt(i)
        if (top) {
            userList.addAll(0, items)
            selected = (0 until items.size).toSortedSet()
        } else {
            val base = userList.size
            userList.addAll(items)
            selected = (base until base + items.size).toSortedSet()
        }
        SpeechEdits.saveUser(this, userList)
        refresh()
    }

    private fun deleteSelected() {
        if (tab != 0 || selected.isEmpty()) return
        for (i in selected.toList().sortedDescending()) {
            if (i in userList.indices) userList.removeAt(i)
        }
        selected = sortedSetOf()
        SpeechEdits.saveUser(this, userList)
        refresh()
    }

    /* full-page add/edit form; forDefault saves the result as an override of
       the built-in default instead of into the user list */
    private fun openEditor(existing: SpeechEdit?, forDefault: Boolean = false) {
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
        val SAMPLE = "There is a tinge of annoyance on Azure Dragon's face."
        val ttsPara = getSharedPreferences("app", MODE_PRIVATE)
            .getString("lastTtsPara", null)?.takeIf { it.isNotBlank() }
        /* the paragraph TTS last stopped at is the default test text when one
           exists; the checkbox flips between it and a manual text */
        val useTtsPara = CheckBox(this).apply {
            text = "Test with the paragraph TTS stopped at"
            textSize = 14f; setTextColor(getColor(R.color.fg))
            setPadding(dp(6), dp(4), 0, dp(4))
            isEnabled = ttsPara != null
            isChecked = ttsPara != null
        }
        form.addView(useTtsPara)
        val applyAll = CheckBox(this).apply {
            text = "Apply all active edits"
            textSize = 14f; setTextColor(getColor(R.color.fg))
            setPadding(dp(6), 0, 0, dp(4))
        }
        form.addView(applyAll)
        val testInput = field(ttsPara ?: SAMPLE, "text to test against")
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
        /* every active rule in speaking order — user rules then defaults —
           with the form's in-progress version substituted for the rule being
           edited (or appended after the user rules when adding a new one) */
        fun allActiveRules(cur: Pair<Regex, String>): List<Pair<Regex, String>> {
            val out = ArrayList<Pair<Regex, String>>()
            for (e in userList) {
                if (!forDefault && existing != null && e.id == existing.id) out.add(cur)
                else if (e.enabled) e.compiled()?.let { out.add(it) }
            }
            if (existing == null) out.add(cur)
            for (e in SpeechEdits.effectiveDefaults(this)) {
                if (forDefault && existing != null && e.id == existing.id) out.add(cur)
                else if (e.enabled) e.compiled()?.let { out.add(it) }
            }
            return out
        }
        fun updateTest() {
            val edit = previewEdit()
            val compiled = edit.compiled()
            testResult.setTextColor(getColor(if (compiled == null) R.color.err else R.color.accent))
            if (compiled == null) {
                testResult.text = "Invalid pattern"
                return
            }
            testResult.text = try {
                if (applyAll.isChecked) {
                    /* mirror the reader's cleanForSpeech: trailing space so
                       end-anchored rules fire, one bad rule skipped not fatal */
                    var s = testInput.text.toString() + " "
                    for ((re, rep) in allActiveRules(compiled)) {
                        s = try { re.replace(s, rep) } catch (e: Exception) { s }
                    }
                    s.replace(Regex("\\s+"), " ").trim()
                } else {
                    compiled.first.replace(testInput.text.toString(), compiled.second)
                }
            } catch (e: Exception) {
                "Invalid pattern"
            }
        }
        var manualText = SAMPLE
        useTtsPara.setOnCheckedChangeListener { _, checked ->
            if (checked && ttsPara != null) {
                manualText = testInput.text.toString()
                testInput.setText(ttsPara)
            } else {
                testInput.setText(manualText)
            }
            /* testInput's watcher reruns the preview */
        }
        applyAll.setOnCheckedChangeListener { _, _ -> updateTest() }
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
                if (forDefault && existing != null) {
                    /* an edited default is stored as an override in its slot */
                    SpeechEdits.saveOverride(
                        this@SpeechEditsActivity,
                        edit.copy(id = existing.id, enabled = existing.enabled, isDefault = true),
                    )
                } else if (existing == null) {
                    userList.add(
                        edit.copy(id = SpeechEdits.newId()),
                    )
                    selected = sortedSetOf(userList.size - 1)
                    SpeechEdits.saveUser(this@SpeechEditsActivity, userList)
                } else {
                    val idx = userList.indexOfFirst { it.id == existing.id }
                    if (idx >= 0) userList[idx] = edit.copy(enabled = userList[idx].enabled)
                    SpeechEdits.saveUser(this@SpeechEditsActivity, userList)
                }
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
