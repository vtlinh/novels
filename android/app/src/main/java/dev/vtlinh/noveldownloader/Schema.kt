package dev.vtlinh.noveldownloader

/* The database's shape, and how an older one is brought to it — with nothing
   Android in it, so the statements that actually run can be run against a real
   SQLite in a plain JVM test.

   This lived inside DownloadStore, where it could only be reasoned about. That
   is not good enough for migrations: they execute once per install, on data
   nobody can get back, and they are the one part of the app a user cannot work
   around. One shipped ordering bug came out of that gap — v19's seed read a
   table v18 creates, but the blocks run in WRITTEN order rather than version
   order, so the statement threw, the throw was swallowed, and every pre-v18
   library upgraded with an empty dir_name. The tests over this object replay
   every version 4..18 against the real schema that version shipped with,
   recovered from git history, and check what comes out. */
object Schema {

    const val VERSION = 21

    const val CHAPTERS_TABLE =
        "CREATE TABLE chapters (" +
            "folder TEXT, slug TEXT, filename TEXT, uri TEXT, url TEXT DEFAULT '', " +
            "size INTEGER DEFAULT 0, hash TEXT DEFAULT '', " +
            "PRIMARY KEY(folder, slug, filename))"
    const val NAMES_TABLE =
        "CREATE TABLE names (" +
            "folder TEXT, slug TEXT, vi TEXT, en TEXT, " +
            "PRIMARY KEY(folder, slug, vi))"
    const val PENDING_TABLE =
        "CREATE TABLE pending_batches (" +
            "batch_id TEXT PRIMARY KEY, folder TEXT, slug TEXT, " +
            "files TEXT, urls TEXT, created INTEGER, tries INTEGER, want_title TEXT)"
    const val TITLES_TABLE =
        "CREATE TABLE titles (" +
            "folder TEXT, slug TEXT, english TEXT, " +
            "PRIMARY KEY(folder, slug))"
    const val NOVELS_TABLE =
        "CREATE TABLE IF NOT EXISTS novels (" +
            "folder TEXT, slug TEXT, url TEXT, title TEXT, " +
            "started INTEGER, total INTEGER DEFAULT -1, complete INTEGER DEFAULT 0, " +
            "author TEXT DEFAULT '', disk_count INTEGER DEFAULT 0, " +
            "last_dl INTEGER DEFAULT 0, last_read INTEGER DEFAULT 0, " +
            "dir_name TEXT DEFAULT '', " +
            /* per-novel settings. `auto_dl` fetches whatever a check finds;
               `translate` is a THREE-state override of the app-wide switch
               — -1 follow it, 0 never, 1 always — because a library is
               mostly one language and the odd novel is the exception. */
            "auto_dl INTEGER DEFAULT 0, translate INTEGER DEFAULT -1, " +
            /* Where the last full read of this novel's listing ended: the
               page that held its final chapters, that page's URL, and how
               many chapters came before it. A check on a novel that is
               already fully downloaded picks up from there instead of
               paging through the whole listing again. 0 = no resume point. */
            "resume_page INTEGER DEFAULT 0, resume_url TEXT DEFAULT '', " +
            "resume_before INTEGER DEFAULT 0, " +
            /* Novel-page info shown above the chapter list. Empty until a
               download or status check has read the page; the chapter list
               also refreshes these when they are still blank. */
            "alt_names TEXT DEFAULT '', genres TEXT DEFAULT '', " +
            "source TEXT DEFAULT '', description TEXT DEFAULT '', " +
            "status_label TEXT DEFAULT '', " +
            "PRIMARY KEY(folder, slug))"
    /* which novel a folder NAME belongs to, so a second novel that
       sanitises onto the same name is sent elsewhere instead of writing
       over the first one's chapters */
    const val FOLDER_OWNER_TABLE =
        "CREATE TABLE IF NOT EXISTS folder_owner (" +
            "folder TEXT, name TEXT, slug TEXT, PRIMARY KEY(folder, name))"
    /* folders whose one-time root scan has been folded into the registry */
    const val SCANNED_TABLE =
        "CREATE TABLE IF NOT EXISTS scanned (folder TEXT PRIMARY KEY, at INTEGER)"
    /* the site's exact chapter order (listing-page sequence), per novel */
    const val ORDER_TABLE =
        "CREATE TABLE IF NOT EXISTS chapter_order (" +
            "folder TEXT, slug TEXT, filename TEXT, ord INTEGER, " +
            "PRIMARY KEY(folder, slug, filename))"
    /* cached resolved chapter listing (see CachedChapterList).
       Invalidated by every write to the chapter index / order, and by
       the compress pass. */
    const val CHLIST_TABLE =
        "CREATE TABLE IF NOT EXISTS chlist (" +
            "folder TEXT, slug TEXT, pos INTEGER, name TEXT, src TEXT, tr TEXT, " +
            "PRIMARY KEY(folder, slug, pos))"

    /* Somewhere to send the statements. `soft` is for the ones whose failure is
       an ordinary outcome rather than a fault — an ALTER adding a column the
       table already has, which is what an upgrade from a version that already
       had it looks like. Keeping that distinction here rather than at each call
       site is the point: a swallowed exception on a statement that was NOT
       meant to fail is how the v19 bug hid. */
    interface Exec {
        fun exec(sql: String)
        fun soft(sql: String) {
            try { exec(sql) } catch (e: Exception) {}
        }
    }

    fun create(db: Exec) {
        db.exec(CHAPTERS_TABLE)
        db.exec(NAMES_TABLE)
        db.exec(PENDING_TABLE)
        db.exec(TITLES_TABLE)
        db.exec(FOLDER_OWNER_TABLE)
        db.exec(NOVELS_TABLE)
        db.exec(SCANNED_TABLE)
        db.exec(ORDER_TABLE)
        db.exec(CHLIST_TABLE)
    }

    fun upgrade(db: Exec, oldVersion: Int) {
        if (oldVersion < 4) {
            /* pre-v4 schemas differ in place — rebuild (it's all cache/index) */
            db.exec("DROP TABLE IF EXISTS chapters")
            db.exec("DROP TABLE IF EXISTS names")
            db.exec("DROP TABLE IF EXISTS pending_batches")
            db.exec("DROP TABLE IF EXISTS titles")
            db.exec("DROP TABLE IF EXISTS novels")
            create(db)
            return
        }
        /* v4+ upgrades are additive; keep everything else (the names
           glossary especially is not rebuildable) */
        if (oldVersion < 5) db.exec(NOVELS_TABLE)   // creates the full current shape
        if (oldVersion == 5) db.exec("ALTER TABLE novels ADD COLUMN author TEXT DEFAULT ''")
        if (oldVersion in 5..6) db.exec("ALTER TABLE novels ADD COLUMN disk_count INTEGER DEFAULT 0")
        if (oldVersion < 7) db.exec(SCANNED_TABLE)
        if (oldVersion in 5..7) db.exec("ALTER TABLE novels ADD COLUMN last_dl INTEGER DEFAULT 0")
        if (oldVersion in 5..8) db.exec("ALTER TABLE novels ADD COLUMN last_read INTEGER DEFAULT 0")
        if (oldVersion < 10) db.exec(ORDER_TABLE)
        /* v10 orders were polluted by the sites' "latest chapters" widget —
           purge so downloads / Check status re-index with correct scoping */
        if (oldVersion == 10) db.exec("DELETE FROM chapter_order")
        if (oldVersion < 12) db.exec(CHLIST_TABLE)
        /* v12 orders were polluted the same way v10's were: the scoped
           chapter list was consulted only for links we hadn't seen, so a
           listing page of already-seen chapters counted as empty and fell
           through to the whole document — where the "latest chapters" widget
           lives. Purge the orders and the cached listings built from them so
           both re-index cleanly. */
        if (oldVersion < 13) {
            db.exec("DELETE FROM chapter_order")
            db.exec("DELETE FROM chlist")
        }
        /* a chapter's page URL, so its file keeps the same name for life
           however the site relabels or renumbers it. Blank for everything
           downloaded before this column existed; the engine fills those in
           as it recognises them. */
        if (oldVersion < 14) {
            db.soft("ALTER TABLE chapters ADD COLUMN url TEXT DEFAULT ''")
            /* v14 is also where "Chapter N" stopped meaning the number the
               site printed and started meaning the Nth entry in its listing.
               Both of these index chapters BY FILENAME, so every row written
               before the change now names a different chapter than it meant
               to — and the reader sorts by exactly these. Drop them; a
               download or a Check status rebuilds both. */
            db.exec("DELETE FROM chapter_order")
            db.exec("DELETE FROM chlist")
        }
        /* size + content hash, so a duplicate left behind by an earlier
           naming scheme can be recognised as the same chapter and dropped */
        if (oldVersion < 15) {
            db.soft("ALTER TABLE chapters ADD COLUMN size INTEGER DEFAULT 0")
            db.soft("ALTER TABLE chapters ADD COLUMN hash TEXT DEFAULT ''")
        }
        /* v15 hashed the whole file, heading included, so the same chapter
           saved under two numbering schemes never matched itself. Hashes are
           of the body now — drop the old ones rather than compare across
           two meanings. */
        if (oldVersion == 15) db.exec("UPDATE chapters SET hash=''")
        /* the page each chapter in a submitted batch came from, so results
           collected after the listing shifted are filed by identity rather
           than by a filename that has since moved to another chapter */
        if (oldVersion < 17) {
            db.soft("ALTER TABLE pending_batches ADD COLUMN urls TEXT DEFAULT ''")
        }
        /* Folder ownership. v17 briefly kept this in `titles`, which is the
           translated-title cache — seed the new table from the novels that
           already have chapters, so an existing library owns the folders it
           has been using rather than being evicted from them by whichever
           novel happens to run first. */
        if (oldVersion < 18) {
            db.exec(FOLDER_OWNER_TABLE)
            db.soft(
                "INSERT OR IGNORE INTO folder_owner(folder,name,slug) " +
                    "SELECT folder, english, slug FROM titles WHERE english<>''",
            )
        }
        /* Which directory each novel actually uses. Nothing recorded it
           before, so ownership had to be inferred — and the inference was
           "this slug has chapters somewhere in this tree", which is true of
           every novel on its second run. Two novels whose titles sanitise to
           the same name therefore collapsed into one folder: the second one
           skipped the disambiguating suffix, resolved to the first one's
           directory, renamed its files and wrote into it.

           AFTER the v18 block, not before it. These run in written order, not
           in version order, and the seed below reads folder_owner — which for
           anything older than v18 is a table that does not exist yet. The
           statement threw, the throw was swallowed, and every pre-v18 library
           upgraded with an empty dir_name for every novel: exactly the state
           this column exists to avoid. SchemaTest pins the ordering now. */
        if (oldVersion < 19) {
            db.soft("ALTER TABLE novels ADD COLUMN dir_name TEXT DEFAULT ''")
            /* Seed it from the ownership table, which v18 has just populated.
               Without this an existing library has no recorded directory, so
               every guard that asks "which folder is this novel's?" falls back
               to rebuilding the name from the title — which gives the
               UNSUFFIXED one, i.e. another novel's folder — until each novel
               happens to be downloaded again. */
            db.soft(
                "UPDATE novels SET dir_name = COALESCE((" +
                    "SELECT name FROM folder_owner " +
                    "WHERE folder_owner.folder = novels.folder AND folder_owner.slug = novels.slug" +
                    "), '') WHERE dir_name = ''",
            )
        }
        /* Per-novel settings, and the resume point a check reads.
           All five default to "as before": auto-download off, translation
           following the app-wide switch, and no resume point — so every
           existing novel keeps being checked by reading its whole listing
           until one full read records where it ends. Nothing to seed: a
           resume point is a measurement of a listing, and there is no
           listing here to measure. */
        if (oldVersion < 20) {
            db.soft("ALTER TABLE novels ADD COLUMN auto_dl INTEGER DEFAULT 0")
            db.soft("ALTER TABLE novels ADD COLUMN translate INTEGER DEFAULT -1")
            db.soft("ALTER TABLE novels ADD COLUMN resume_page INTEGER DEFAULT 0")
            db.soft("ALTER TABLE novels ADD COLUMN resume_url TEXT DEFAULT ''")
            db.soft("ALTER TABLE novels ADD COLUMN resume_before INTEGER DEFAULT 0")
        }
        /* Novel-info fields scraped from the novel page (author was already
           here; these are the rest of what the chapter-list header shows).
           Empty defaults — nothing to seed: they are measurements of a page,
           and there is no page here to measure. */
        if (oldVersion < 21) {
            db.soft("ALTER TABLE novels ADD COLUMN alt_names TEXT DEFAULT ''")
            db.soft("ALTER TABLE novels ADD COLUMN genres TEXT DEFAULT ''")
            db.soft("ALTER TABLE novels ADD COLUMN source TEXT DEFAULT ''")
            db.soft("ALTER TABLE novels ADD COLUMN description TEXT DEFAULT ''")
            db.soft("ALTER TABLE novels ADD COLUMN status_label TEXT DEFAULT ''")
        }
    }
}
