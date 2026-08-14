package dev.vtlinh.noveldownloader

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/* Watches the whole app's foreground lifecycle so the self-update check and
   the optional library status auto-check run from ANY screen — the reader,
   chapter list, etc. Previously the updater only ran on the old Home
   screen's onResume, which the "resume into the reader" flow skips, so
   auto-updates never fired. */
class App : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        /* if we're now running the version we had cached, the update went
           through — delete the leftover APK (kept otherwise for a retry) */
        try { Updater.cleanupIfInstalled(applicationContext) } catch (e: Exception) {}
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    Updater.autoCheck(applicationContext, scope)
                    StatusAutoCheck.autoCheck(applicationContext, scope)
                    /* Downloads first. The compress pass stands aside for a
                       running download, and starting it first meant it read
                       runningFlow before the resumed queue had set it — so on
                       every resume the two ran over the same folders anyway. */
                    try { DownloadService.resumeQueueIfNeeded(applicationContext) } catch (e: Exception) {}
                    /* resume a compress/uncompress pass interrupted by an app
                       kill — done on foreground so starting the service is
                       allowed on Android 12+ */
                    try { CompressService.resumeIfNeeded(applicationContext) } catch (e: Exception) {}
                }
            },
        )
    }
}
