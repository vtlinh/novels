package dev.vtlinh.noveldownloader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/* About: app description, author, source, and the update control. Nothing
   here installs on its own — the button downloads a newer build and then
   turns into "Install", which is the only thing that commits it. That
   mirrors the notification the background check posts. */
class AboutActivity : AppCompatActivity() {

    private val btn by lazy { findViewById<Button>(R.id.checkUpdateBtn) }
    private val status by lazy { findViewById<TextView>(R.id.updateStatus) }

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

        btn.setOnClickListener {
            /* an already-downloaded build turns this into the Install tap */
            val pending = Updater.pendingUpdateName(this)
            if (pending != null) installPending(pending) else checkForUpdate()
        }
    }

    /* reflect whatever the background check left on disk */
    private fun showPendingIfAny(): Boolean {
        val pending = Updater.pendingUpdateName(this) ?: run {
            btn.text = "Check for updates"
            return false
        }
        btn.text = "Install v$pending"
        status.text = "v$pending is downloaded and ready to install."
        return true
    }

    private fun checkForUpdate() {
        btn.isEnabled = false
        status.text = "Checking…"
        lifecycleScope.launch {
            val latest = Updater.latestVersion()
            if (latest == null) {
                status.text = "Couldn't reach the update server — check your connection."
                btn.isEnabled = true
                return@launch
            }
            if (latest.first <= Updater.currentVersionCode(this@AboutActivity)) {
                status.text = "You're on the latest version (v${latest.second})."
                btn.isEnabled = true
                return@launch
            }
            status.text = "Downloading v${latest.second}…"
            val apk = Updater.ensureApk(this@AboutActivity, latest.first)
            btn.isEnabled = true
            if (apk == null) {
                status.text = "Update download failed — try again."
                return@launch
            }
            /* downloaded, not installed: the button becomes Install */
            Updater.rememberPendingName(this@AboutActivity, latest.second)
            if (!showPendingIfAny()) status.text = "Update download failed — try again."
        }
    }

    private fun installPending(versionName: String) {
        /* the OS blocks installs unless "Install unknown apps" is granted to
           this app — send the user to the toggle */
        if (!Updater.canInstall(this)) {
            status.text = "Allow Novels to install updates in the screen that opens, " +
                "then tap Install again."
            Updater.openInstallPermission(this)
            return
        }
        Updater.cancelUpdateNotification(this)
        /* a healthy install kills this process within seconds, so still being
           here afterwards means the session stalled — another tap abandons it
           and re-commits the cached APK (no re-download) */
        status.text = "Installing v$versionName… — tap again if nothing happens."
        installInFlight = true
        if (!Updater.installPending(this)) {
            installInFlight = false
            status.text = "Install failed — try again."
        }
    }

    private var installInFlight = false

    override fun onResume() {
        super.onResume()
        val wasInstalling = installInFlight
        installInFlight = false
        val pending = showPendingIfAny()
        /* back here while an install was in flight means the confirmation
           was dismissed — say so, after the label refresh so it isn't
           immediately overwritten */
        if (wasInstalling && pending) {
            btn.isEnabled = true
            status.text = "Install was cancelled — tap Install to retry."
        }
    }
}
