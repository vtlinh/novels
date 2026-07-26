package dev.vtlinh.noveldownloader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/* The "Install" button on the update-ready notification. The APK is already
   on disk by this point — autoCheck fetched it when it spotted the release —
   so this only has to commit it. Without the "Install unknown apps" grant
   the commit would be blocked, so send the user to that toggle instead; the
   notification is re-posted on the next foreground check. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Updater.ACTION_INSTALL_UPDATE) return
        Updater.cancelUpdateNotification(context)
        if (!Updater.canInstall(context)) {
            Updater.openInstallPermission(context)
            return
        }
        /* committing streams the APK into the session — too slow to risk on
           the main thread, where a receiver has seconds before it's killed */
        val done = goAsync()
        val app = context.applicationContext
        Thread {
            try { Updater.installPending(app) } finally { done.finish() }
        }.start()
    }
}
