package dev.vtlinh.noveldownloader

import android.content.Intent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout

/* The hamburger destinations shared by Library and Documents. One place
   so a new section cannot be added to the XML and missed on one of the
   screens. The chapter list is a drill-in and uses ← instead. */
object Nav {

    enum class Screen { LIBRARY, DOCUMENTS }

    fun bindDrawer(activity: AppCompatActivity, here: Screen) {
        val drawer = activity.findViewById<DrawerLayout>(R.id.drawerLayout)
        activity.findViewById<TextView>(R.id.menuBtn).setOnClickListener {
            drawer.openDrawer(GravityCompat.START)
        }
        fun close() = drawer.closeDrawer(GravityCompat.START)
        activity.findViewById<TextView>(R.id.navBrowser).setOnClickListener {
            close()
            activity.startActivity(Intent(activity, BrowserActivity::class.java))
        }
        activity.findViewById<TextView>(R.id.navNovels).setOnClickListener {
            close()
            if (here == Screen.LIBRARY) return@setOnClickListener
            activity.startActivity(
                Intent(activity, NovelListActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
            /* Leave a novel (or the documents list) rather than stacking
               another top-level screen on it. REORDER_TO_FRONT, not
               CLEAR_TOP: clearing would destroy a reader that's reading
               aloud. */
            if (here != Screen.LIBRARY) activity.finish()
        }
        activity.findViewById<TextView>(R.id.navDocuments).setOnClickListener {
            close()
            if (here == Screen.DOCUMENTS) return@setOnClickListener
            activity.startActivity(
                Intent(activity, DocumentListActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
        activity.findViewById<TextView>(R.id.navSettings).setOnClickListener {
            close()
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
        }
        activity.findViewById<TextView>(R.id.navAbout).setOnClickListener {
            close()
            activity.startActivity(Intent(activity, AboutActivity::class.java))
        }
    }
}
