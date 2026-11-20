package dev.vtlinh.noveldownloader

import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/* The Reading tab of the settings page (SettingsPage): font size, pauses,
   all-caps, sleep timer, shake to reset. Everything here is a saved
   preference the reader reads when it opens, so the tab works from the
   chapter list too. `reader` is the reader this page was opened from, if
   any — it applies a change on the spot, and offers what only makes sense
   inside it: the language switch and the chapter picture button. */
class ReadingSettingsPanel(
    private val host: AppCompatActivity,
    private val reader: Reader?,
) {

    class Reader(
        val fontSp: () -> Float,
        val adjustFont: (Float) -> Unit,
        /* "Switch to English" or the like; null when there is no translation */
        val languageSwitch: String?,
        val toggleLanguage: () -> Unit,
        /* null hides the picture card (a document has none) */
        val bindImage: ((TextView, LinearLayout) -> Unit)?,
        val sleepChanged: () -> Unit,
        val shakeChanged: () -> Unit,
    )

    companion object {
        const val FONT_KEY = "readerFontSize"
        const val FONT_DEFAULT = 16f
        fun stepFont(current: Float, delta: Float): Float = (current + delta).coerceIn(12f, 50f)
    }

    private val prefs = host.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)

    val view = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }

    /* set by the page: closes it */
    var closePage: () -> Unit = {}

    private var saveSleep: () -> Unit = {}
    private var saveShake: () -> Unit = {}
    private var stopShakeTest: () -> Unit = {}

    private fun dp(v: Int): Int = (v * host.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = host.getColor(id)

    init { build() }

    /* Persist the timer + shake settings and stop the shake tester. */
    fun close() {
        saveSleep()
        saveShake()
        stopShakeTest()
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = androidx.core.content.ContextCompat.getDrawable(host, R.drawable.bg_settings_card)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        view.addView(
            c,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { if (view.childCount > 0) topMargin = dp(14) },
        )
        return c
    }

    private fun cardTitle(c: LinearLayout, t: String) = c.addView(
        TextView(host).apply {
            text = t; textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(color(R.color.fg))
        },
    )

    private fun hint(c: LinearLayout, t: String) = c.addView(
        TextView(host).apply {
            text = t; textSize = 12f; setTextColor(color(R.color.muted))
            setPadding(0, dp(6), 0, 0)
        },
    )

    private fun build() {
        val ctx = host
        reader?.bindImage?.let { bind ->
            val img = card()
            val gen = TextView(ctx).apply {
                text = "Generate image"
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, dp(2))
                isFocusable = true
            }
            img.addView(gen)
            bind(gen, img)
        }

        /* ── Display: language + font ── */
        val disp = card()
        cardTitle(disp, "Display")
        val switchLabel = reader?.languageSwitch
        if (reader != null && switchLabel != null) {
            disp.addView(
                TextView(ctx).apply {
                    text = switchLabel
                    textSize = 15f; setTextColor(color(R.color.accent))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, dp(12), 0, dp(6))
                    isClickable = true; isFocusable = true
                    setOnClickListener { closePage(); reader.toggleLanguage() }
                },
            )
        }
        fun fontNow(): Float = reader?.fontSp?.invoke() ?: prefs.getFloat(FONT_KEY, FONT_DEFAULT)
        val fontRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        fontRow.addView(
            TextView(ctx).apply {
                text = "Font size"; textSize = 15f; setTextColor(color(R.color.fg))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        val fontVal = TextView(ctx).apply {
            text = fontNow().toInt().toString(); textSize = 15f; setTextColor(color(R.color.fg))
            minWidth = dp(36); gravity = android.view.Gravity.CENTER
        }
        /* one-line preview of the current size (ellipsized, never wrapped) */
        val fontSample = TextView(ctx).apply {
            text = "The quick brown fox jumps over the lazy dog"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontNow())
            setTextColor(color(R.color.muted))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(10), 0, 0)
        }
        fun fontBtn(t: String, d: Float) = TextView(ctx).apply {
            text = t; textSize = 22f; setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(color(R.color.accent)); setPadding(dp(16), dp(2), dp(16), dp(2))
            isClickable = true; isFocusable = true
            setOnClickListener {
                if (reader != null) {
                    reader.adjustFont(d)
                } else {
                    prefs.edit().putFloat(FONT_KEY, stepFont(fontNow(), d)).apply()
                }
                fontVal.text = fontNow().toInt().toString()
                fontSample.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontNow())
            }
        }
        fontRow.addView(fontBtn("−", -1f))
        fontRow.addView(fontVal)
        fontRow.addView(fontBtn("+", +1f))
        disp.addView(fontRow)
        disp.addView(fontSample)

        /* ── Pause duration ── */
        val pauses = card()
        cardTitle(pauses, "Pause duration")
        fun pauseRow(label: String, key: String) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            row.addView(
                TextView(ctx).apply {
                    text = label; textSize = 15f; setTextColor(color(R.color.fg))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            val valTv = TextView(ctx).apply {
                text = TtsPause.format(prefs.getFloat(key, 0f))
                textSize = 15f; setTextColor(color(R.color.fg))
                minWidth = dp(44); gravity = android.view.Gravity.CENTER
            }
            fun nudge(delta: Float) {
                val next = TtsPause.step(prefs.getFloat(key, 0f), delta)
                prefs.edit().putFloat(key, next).apply()
                valTv.text = TtsPause.format(next)
            }
            fun pauseBtn(t: String, d: Float) = TextView(ctx).apply {
                text = t; textSize = 22f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(color(R.color.accent)); setPadding(dp(16), dp(2), dp(16), dp(2))
                isClickable = true; isFocusable = true
                setOnClickListener { nudge(d) }
            }
            row.addView(pauseBtn("−", -TtsPause.STEP))
            row.addView(valTv)
            row.addView(pauseBtn("+", +TtsPause.STEP))
            pauses.addView(row)
        }
        pauseRow("Between sentences", TtsPause.SENTENCE_KEY)
        pauseRow("Between paragraphs", TtsPause.PARAGRAPH_KEY)
        pauseRow("Between chapters", TtsPause.CHAPTER_KEY)
        hint(pauses, "Seconds of silence after each sentence, paragraph, or chapter. The voice keeps going — it just says nothing for that long.")

        /* ── All-caps sentences ── */
        val caps = card()
        cardTitle(caps, "All-caps sentences")
        val capsCheck = android.widget.CheckBox(ctx).apply {
            text = "Read them as lowercase"
            textSize = 14f; setTextColor(color(R.color.fg))
            setPadding(dp(6), dp(10), 0, 0)
            isChecked = prefs.getBoolean(SpeechText.FOLD_ALL_CAPS_KEY, SpeechText.FOLD_ALL_CAPS_DEFAULT)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(SpeechText.FOLD_ALL_CAPS_KEY, checked).apply()
            }
        }
        caps.addView(capsCheck)
        hint(caps, "A sentence written in full capitals is spoken as ordinary text, and speech-edit replacements see the lowercase form. The page on screen does not change.")

        /* ── Sleep timer ── */
        val sleep = card()
        cardTitle(sleep, "Sleep timer")
        val sleepInput = android.widget.EditText(ctx).apply {
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "0 = off"
            setTextColor(color(R.color.fg)); setHintTextColor(color(R.color.muted))
            textSize = 14f
            val cur = prefs.getInt("sleepMinutes", 0)
            if (cur > 0) setText(cur.toString())
        }
        sleep.addView(
            sleepInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        hint(sleep, "Stop reading after this many minutes. The timer restarts each time you press play. Blank or 0 turns it off. When it stops reading, the chapter list marks the chapters read since you last moved.")
        saveSleep = {
            val mins = sleepInput.text.toString().toIntOrNull() ?: 0
            prefs.edit().putInt("sleepMinutes", mins.coerceAtLeast(0)).apply()
            reader?.sleepChanged?.invoke()
        }

        /* ── Shake to reset ── */
        val shake = card()
        cardTitle(shake, "Shake to reset")
        val shakeCheck = android.widget.CheckBox(ctx).apply {
            text = "Reset the sleep timer when I shake the phone"
            textSize = 14f; setTextColor(color(R.color.fg))
            setPadding(dp(6), dp(10), 0, 0)
            isChecked = prefs.getBoolean("shakeEnabled", false)
        }
        shake.addView(shakeCheck)
        val startLevel = prefs.getInt("shakeLevel", 5).coerceIn(1, 10)
        val shakeLabel = TextView(ctx).apply {
            text = "Shake threshold: $startLevel"; textSize = 13f
            setTextColor(color(R.color.muted)); setPadding(0, dp(12), 0, dp(4))
        }
        shake.addView(shakeLabel)
        val shakeBar = android.widget.SeekBar(ctx).apply {
            min = 1; max = 10; progress = startLevel
            // visible notch at each whole step so the slider reads as integer 1–10
            tickMark = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.seekbar_tick)
        }
        shake.addView(
            shakeBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        shakeBar.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, u: Boolean) {
                    shakeLabel.text = "Shake threshold: ${p.coerceAtLeast(1)}"
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
            },
        )
        hint(shake, "1 = the gentlest nudge (most sensitive), 10 = a firm shake. Shake the phone now to test — the status below flashes red when a shake passes this level.")
        /* live tester: flashes here so the threshold can be calibrated while
           this page is open (uses the slider's current level, not the saved one) */
        val shakeStatus = TextView(ctx).apply {
            text = "shaking — sleep timer reset"; textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(color(R.color.err)); setPadding(0, dp(12), 0, 0)
            visibility = android.view.View.INVISIBLE   // reserve space, no jump
        }
        shake.addView(shakeStatus)
        val sm = host.getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        val accel = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        if (sm != null && accel != null) {
            var shownAt = 0L
            val listener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(e: android.hardware.SensorEvent) {
                    val g = Math.sqrt(
                        (e.values[0] * e.values[0] + e.values[1] * e.values[1] +
                            e.values[2] * e.values[2]).toDouble(),
                    ) - android.hardware.SensorManager.GRAVITY_EARTH
                    val thr = shakeBar.progress.coerceAtLeast(1) / 5f
                    if (Math.abs(g) > thr) {
                        val now = System.currentTimeMillis()
                        if (now - shownAt < 2500) return
                        shownAt = now
                        shakeStatus.visibility = android.view.View.VISIBLE
                        shakeStatus.postDelayed(
                            { shakeStatus.visibility = android.view.View.INVISIBLE }, 2000,
                        )
                    }
                }
                override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
            }
            sm.registerListener(listener, accel, android.hardware.SensorManager.SENSOR_DELAY_GAME)
            stopShakeTest = { sm.unregisterListener(listener) }
        }
        saveShake = {
            prefs.edit()
                .putBoolean("shakeEnabled", shakeCheck.isChecked)
                .putInt("shakeLevel", shakeBar.progress.coerceIn(1, 10))
                .apply()
            /* re-arm with the new config if currently reading */
            reader?.shakeChanged?.invoke()
        }
    }
}
