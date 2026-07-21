package dev.vtlinh.noveldownloader

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/* Watches the whole app's foreground lifecycle so the self-update check runs
   from ANY screen — the reader, chapter list, etc. Previously it only ran on
   the Home screen's onResume, which the "resume into the reader" flow skips,
   so auto-updates never fired. */
class App : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    Updater.autoCheck(applicationContext, scope)
                }
            },
        )
    }
}
