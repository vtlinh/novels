package dev.vtlinh.noveldownloader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/* About: app description, author, source, and a manual "Check for updates"
   button that downloads and installs a newer published build if one exists. */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }

        findViewById<TextView>(R.id.aboutVersion).text = "Version " + try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }

        findViewById<TextView>(R.id.aboutRepo).setOnClickListener {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/vtlinh/novels")),
                )
            } catch (e: Exception) {}
        }

        findViewById<Button>(R.id.checkUpdateBtn).setOnClickListener { checkForUpdate() }
    }

    private fun checkForUpdate() {
        val btn = findViewById<Button>(R.id.checkUpdateBtn)
        val status = findViewById<TextView>(R.id.updateStatus)
        btn.isEnabled = false
        status.text = "Checking…"
        lifecycleScope.launch {
            val latest = Updater.latestVersion()
            if (latest == null) {
                status.text = "Couldn't reach the update server — check your connection."
                btn.isEnabled = true
                return@launch
            }
            val current = Updater.currentVersionCode(this@AboutActivity)
            if (latest.first <= current) {
                status.text = "You're on the latest version (v${latest.second})."
                btn.isEnabled = true
                return@launch
            }
            /* newer build available → download and install right away */
            status.text = "Downloading v${latest.second}…"
            val apk = Updater.downloadApk(this@AboutActivity)
            if (apk == null) {
                status.text = "Update download failed — try again."
                btn.isEnabled = true
                return@launch
            }
            status.text = "Installing v${latest.second}…"
            try {
                Updater.install(this@AboutActivity, apk)
                /* returning here (via onResume) means the install prompt was
                   dismissed — offer a retry */
                installInFlight = true
            } catch (e: Exception) {
                status.text = "Install failed — ${e.message}"
                btn.isEnabled = true
            }
        }
    }

    private var installInFlight = false

    override fun onResume() {
        super.onResume()
        if (installInFlight) {
            installInFlight = false
            findViewById<Button>(R.id.checkUpdateBtn).isEnabled = true
            findViewById<TextView>(R.id.updateStatus).text =
                "Install was cancelled — tap Check for updates to retry."
        }
    }
}
