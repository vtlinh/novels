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
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, NO_STATUS)
        when (status) {
            NO_STATUS -> return                       // not a session result at all
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(confirm) } catch (e: Exception) {}
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.cancelUpdateNotification(context)
            /* Every commit shares one PendingIntent, so a session abandoned to
               clear a stalled install reports ABORTED down the same channel as
               a real failure — and saying "the install was cancelled" while
               the replacement session is committing normally is just noise.
               A genuine user cancellation comes back around as a retry. */
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                /* Not silent — just not called a failure. This also arrives
                   when we abandon a stalled session to make room for a fresh
                   commit, and "the install was cancelled" while the
                   replacement is committing is a lie. Put the offer back
                   instead: if the user really did dismiss the dialog, the
                   update is still there to install; if we abandoned it
                   ourselves, the commit in flight supersedes this. */
                Updater.reofferPendingUpdate(context)
            /* Everything else is a failure, and every one of them used to
               land here and stop. The tap that started the install is long
               gone by now, so nothing was left saying the update didn't
               happen — the app just carried on as the old version. */
            else -> {
                /* Refusals of the package itself will refuse it identically
                   every time, so keeping the bytes and re-offering them is an
                   endless loop the user can't break. Storage and the like are
                   about this moment, not this build — those keep it. */
                if (status in TERMINAL) Updater.rejectPendingUpdate(context)
                Updater.notifyInstallFailed(
                    context,
                    describe(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)),
                )
            }
        }
    }

    private fun describe(status: Int, msg: String?): String {
        val what = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "The install was cancelled."
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "The system blocked the install."
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "It conflicts with the copy already installed — the signing key may differ."
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This build isn't compatible with the device."
            PackageInstaller.STATUS_FAILURE_INVALID -> "The downloaded APK was not valid."
            PackageInstaller.STATUS_FAILURE_STORAGE -> "There wasn't enough free storage."
            else -> "The install failed."
        }
        return if (msg.isNullOrBlank()) what else "$what ($msg)"
    }

    private companion object {
        const val NO_STATUS = Int.MIN_VALUE

        /* verdicts on the package, not on the moment — retrying these with
           the same bytes gets the same answer */
        val TERMINAL = setOf(
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
        )
    }
}
