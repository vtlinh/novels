package dev.vtlinh.noveldownloader

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/* Watches the whole app's foreground lifecycle so the self-update check,
   the optional library status auto-check, and a TTS load all run from ANY
   screen — the reader, chapter list, etc. Previously the updater only ran
   on the old Home screen's onResume, which the "resume into the reader"
   flow skips, so auto-updates never fired. TTS had the same shape: it
   only loaded when the reader's settings sheet asked, so a return from
   the background left it missing until that sheet was opened. */
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
                    TtsWarmup.onForeground(applicationContext)
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
                override fun onStop(owner: LifecycleOwner) {
                    TtsWarmup.onBackground()
                }
            },
        )
    }
}
