package dev.vtlinh.noveldownloader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/* Receives the PackageInstaller session result for a self-update. When the
   system still requires confirmation (Android < 12, or this app is not yet
   its own installer of record), it hands back the confirmation intent —
   launch it so the user gets the one-tap Install dialog. Silent installs
   never hit that branch. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(confirm) } catch (e: Exception) {}
            }
        }
    }
}
