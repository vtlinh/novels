package dev.vtlinh.noveldownloader

import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/* The migrations, run against a REAL SQLite.

   Everything else in this suite tests decisions. This one tests statements —
   and statements are where the damage is unrecoverable: a migration executes
   once per install, on the only copy of a library the user has, and there is
   no re-run. The `names` glossary in particular cannot be rebuilt from
   anything.

   The baseline for each version is not written here. It is EXTRACTED FROM GIT
   HISTORY by tools/extract-schemas.py — the CREATE statements that version
   actually shipped with — into resources/schema/vN.sql. That independence is
   the point: a test that built the old schema out of the current code's own
   idea of it would agree with any bug the current code has, which is exactly
   how the one shipped migration bug survived review. It ordered v19's seed
   before the v18 block that creates the table it reads, the statement threw,
   the throw was swallowed, and every pre-v18 library upgraded with an empty
   dir_name — the precise state the column exists to prevent. */
class SchemaTest {

    /* Schema.Exec over JDBC. `soft` keeps its default: swallow. So a statement
       the migration marked soft may fail here exactly as it may on a device,
       and one it did NOT mark soft will fail this test rather than vanish. */
    private class Jdbc(val c: Connection) : Schema.Exec {
        val softFailures = ArrayList<String>()
        override fun exec(sql: String) {
            c.createStatement().use { it.execute(sql) }
        }
        override fun soft(sql: String) {
            try { exec(sql) } catch (e: Exception) { softFailures.add(sql) }
        }
    }

    private fun open() = DriverManager.getConnection("jdbc:sqlite::memory:")

    private fun sqlFor(version: Int): List<String> {
        val res = javaClass.getResourceAsStream("/schema/v$version.sql")
            ?: throw AssertionError("no captured schema for v$version")
        return res.use { it.readBytes().toString(Charsets.UTF_8) }
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("--") }
            .joinToString(" ")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /* table -> its columns, read back from SQLite itself rather than from what
       we think we asked for */
    private fun shape(c: Connection): Map<String, Set<String>> {
        val tables = ArrayList<String>()
        c.createStatement().use { s ->
            s.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
            ).use { r -> while (r.next()) tables.add(r.getString(1)) }
        }
        return tables.associateWith { t ->
            val cols = HashSet<String>()
            c.createStatement().use { s ->
                s.executeQuery("PRAGMA table_info($t)").use { r ->
                    while (r.next()) cols.add(r.getString("name"))
                }
            }
            cols
        }
    }

    private fun fresh(): Map<String, Set<String>> {
        open().use { c ->
            Schema.create(Jdbc(c))
            return shape(c)
        }
    }

    private fun at(version: Int, c: Connection) {
        val j = Jdbc(c)
        for (sql in sqlFor(version)) j.exec(sql)
    }

    private val versions = 4..(Schema.VERSION - 1)

    @Test
    fun `every captured schema is real SQL`() {
        for (v in 4..Schema.VERSION) {
            open().use { c ->
                at(v, c)
                assertTrue("v$v: no tables", shape(c).isNotEmpty())
            }
        }
    }

    /* The captured v19 is what onCreate built at that commit; Schema.create is
       what it builds now. If those have drifted apart, every assertion below
       is measuring the wrong target. */
    @Test
    fun `the captured newest schema matches what create builds today`() {
        open().use { c ->
            at(Schema.VERSION, c)
            assertEquals(fresh(), shape(c))
        }
    }

    /* THE ONE THAT MATTERS. Upgrading from any shipped version has to land on
       exactly the shape a fresh install has — not a subset, not a superset.
       A missing column here is a crash on the first query that names it. */
    @Test
    fun `every version upgrades to the current shape`() {
        val want = fresh()
        for (v in versions) {
            open().use { c ->
                at(v, c)
                Schema.upgrade(Jdbc(c), v)
                assertEquals("upgrading from v$v", want, shape(c))
            }
        }
    }

    /* A migration may not throw on a statement it did not mark as soft. The
       swallowed-exception habit is what hid the v19 ordering bug for a
       release: the statement failed on every pre-v18 install and said nothing.
       Soft is for an ALTER adding a column an older path already added — a
       real outcome — and this pins which statements are allowed to be that. */
    @Test
    fun `no upgrade path fails a statement it did not expect to`() {
        for (v in versions) {
            open().use { c ->
                at(v, c)
                val j = Jdbc(c)
                try {
                    Schema.upgrade(j, v)
                } catch (e: Exception) {
                    throw AssertionError("upgrading from v$v threw: ${e.message}")
                }
                /* the only failures tolerated are duplicate-column ALTERs */
                for (sql in j.softFailures) {
                    assertTrue(
                        "upgrading from v$v: \"$sql\" failed, and it is not an ALTER whose " +
                            "column an earlier path already added",
                        sql.startsWith("ALTER TABLE"),
                    )
                }
            }
        }
    }

    /* The glossary is bought from the API a name at a time and cannot be
       rebuilt from anything on disk. Nothing in an upgrade may drop it. */
    @Test
    fun `an upgrade never drops the translated-name glossary`() {
        for (v in versions) {
            open().use { c ->
                at(v, c)
                c.createStatement().use {
                    it.execute("INSERT INTO names(folder,slug,vi,en) VALUES('f','s','Thần Y','Divine Doctor')")
                }
                Schema.upgrade(Jdbc(c), v)
                c.createStatement().use { s ->
                    s.executeQuery("SELECT en FROM names WHERE vi='Thần Y'").use { r ->
                        assertTrue("upgrading from v$v lost the glossary", r.next())
                        assertEquals("upgrading from v$v", "Divine Doctor", r.getString(1))
                    }
                }
            }
        }
    }

    /* A chapter's recorded page is its identity — the one thing about a file
       that cannot be reconstructed from disk. It arrives in v14, so every
       upgrade from v14 on has to carry it through. */
    @Test
    fun `an upgrade never drops a chapter's recorded page`() {
        for (v in 14..(Schema.VERSION - 1)) {
            open().use { c ->
                at(v, c)
                c.createStatement().use {
                    it.execute(
                        "INSERT INTO chapters(folder,slug,filename,uri,url) " +
                            "VALUES('f','s','Chapter 1.txt','content://x','https://site/c1/')",
                    )
                }
                Schema.upgrade(Jdbc(c), v)
                c.createStatement().use { s ->
                    s.executeQuery("SELECT url FROM chapters WHERE filename='Chapter 1.txt'").use { r ->
                        assertTrue("upgrading from v$v lost the chapter row", r.next())
                        assertEquals("upgrading from v$v", "https://site/c1/", r.getString(1))
                    }
                }
            }
        }
    }

    /* THE SHIPPED BUG, pinned. v18 creates folder_owner and seeds it from the
       translated-title cache; v19 adds dir_name and seeds THAT from
       folder_owner. The blocks run in written order, not version order, so
       putting v19 first made its seed read a table that did not exist yet —
       it threw, the throw was swallowed, and every pre-v18 library upgraded
       with an empty dir_name for every novel. Reorder the two blocks in
       Schema and this fails. */
    @Test
    fun `a pre-v18 library upgrades with its folders recorded`() {
        for (v in 4..17) {
            open().use { c ->
                at(v, c)
                c.createStatement().use {
                    it.execute("INSERT INTO titles(folder,slug,english) VALUES('f','than-y','Divine Doctor')")
                }
                /* v5 is where `novels` arrives; before that there is no row to
                   seed and the column is moot */
                if (v >= 5) {
                    c.createStatement().use {
                        it.execute(
                            "INSERT INTO novels(folder,slug,url,title,started) " +
                                "VALUES('f','than-y','https://site/than-y/','Thần Y',1)",
                        )
                    }
                }
                Schema.upgrade(Jdbc(c), v)
                c.createStatement().use { s ->
                    s.executeQuery("SELECT name FROM folder_owner WHERE slug='than-y'").use { r ->
                        assertTrue("upgrading from v$v did not seed folder_owner", r.next())
                        assertEquals("Divine Doctor", r.getString(1))
                    }
                }
                if (v >= 5) {
                    c.createStatement().use { s ->
                        s.executeQuery("SELECT dir_name FROM novels WHERE slug='than-y'").use { r ->
                            assertTrue(r.next())
                            assertEquals(
                                "upgrading from v$v left dir_name empty — the seed ran before " +
                                    "the table it reads existed",
                                "Divine Doctor",
                                r.getString(1),
                            )
                        }
                    }
                }
            }
        }
    }

    /* v14 redefined what a filename MEANS — "Chapter N" stopped being the
       number the site printed and became the Nth entry in its listing. Both
       of these index by filename, so every row written before it names a
       different chapter than it meant to, and the reader sorts by exactly
       these. Carrying them across would silently reorder a library. */
    @Test
    fun `the caches keyed by filename are dropped across the v14 renumber`() {
        for (v in 12..13) {
            open().use { c ->
                at(v, c)
                c.createStatement().use {
                    it.execute("INSERT INTO chapter_order(folder,slug,filename,ord) VALUES('f','s','Chapter 1.txt',0)")
                    it.execute("INSERT INTO chlist(folder,slug,pos,name,src,tr) VALUES('f','s',0,'Chapter 1.txt','u','')")
                }
                Schema.upgrade(Jdbc(c), v)
                for (t in listOf("chapter_order", "chlist")) {
                    c.createStatement().use { s ->
                        s.executeQuery("SELECT COUNT(*) FROM $t").use { r ->
                            r.next()
                            assertEquals("upgrading from v$v kept a stale $t", 0, r.getInt(1))
                        }
                    }
                }
            }
        }
    }

    /* ...but a v15 upgrade must NOT drop them: nothing about what a filename
       means changed there, and re-walking a 7k-file folder is seconds the
       cache exists to avoid. This is the other side of the test above — a
       migration that purged everything unconditionally would pass that one. */
    @Test
    fun `an upgrade past the renumber keeps the caches it has no reason to drop`() {
        open().use { c ->
            at(15, c)
            c.createStatement().use {
                it.execute("INSERT INTO chapter_order(folder,slug,filename,ord) VALUES('f','s','Chapter 1.txt',0)")
            }
            Schema.upgrade(Jdbc(c), 15)
            c.createStatement().use { s ->
                s.executeQuery("SELECT COUNT(*) FROM chapter_order").use { r ->
                    r.next()
                    assertEquals("a v15 upgrade purged an order it had no reason to", 1, r.getInt(1))
                }
            }
        }
    }

    /* v15's hashes covered the heading, so the same chapter saved under two
       numbering schemes never matched itself. They mean something else now;
       comparing across the two meanings is worse than having none. */
    @Test
    fun `hashes from before the body-only change are cleared`() {
        open().use { c ->
            at(15, c)
            c.createStatement().use {
                it.execute(
                    "INSERT INTO chapters(folder,slug,filename,uri,hash) " +
                        "VALUES('f','s','Chapter 1.txt','content://x','deadbeef')",
                )
            }
            Schema.upgrade(Jdbc(c), 15)
            c.createStatement().use { s ->
                s.executeQuery("SELECT hash FROM chapters WHERE filename='Chapter 1.txt'").use { r ->
                    r.next()
                    assertEquals("", r.getString(1))
                }
            }
        }
    }

    /* The per-novel settings arrive in v20 and every one of them defaults to
       "what the app did before": auto-download off, translation following the
       app-wide switch, and no resume point.

       The middle one is the one worth pinning. `translate` is THREE-state —
       -1 follow, 0 never, 1 always — and a column that defaulted to 0 would
       read as "never translate this novel", silently switching translation
       off for an entire existing library on upgrade. */
    @Test
    fun `an existing novel upgrades with its settings unset`() {
        for (v in 5..19) {
            open().use { c ->
                at(v, c)
                c.createStatement().use {
                    it.execute(
                        "INSERT INTO novels(folder,slug,url,title,started) " +
                            "VALUES('f','than-y','https://site/than-y/','Thần Y',1)",
                    )
                }
                Schema.upgrade(Jdbc(c), v)
                c.createStatement().use { s ->
                    s.executeQuery(
                        "SELECT auto_dl, translate, resume_page, resume_url, resume_before " +
                            "FROM novels WHERE slug='than-y'",
                    ).use { r ->
                        assertTrue("upgrading from v$v lost the novel", r.next())
                        assertEquals("upgrading from v$v: auto-download", 0, r.getInt(1))
                        assertEquals(
                            "upgrading from v$v: translation must FOLLOW the app-wide " +
                                "switch, not be turned off",
                            -1, r.getInt(2),
                        )
                        assertEquals("upgrading from v$v: resume page", 0, r.getInt(3))
                        assertEquals("upgrading from v$v: resume url", "", r.getString(4))
                        assertEquals("upgrading from v$v: resume prefix", 0, r.getInt(5))
                    }
                }
            }
        }
    }

    /* Novel-info columns arrive empty: they are measurements of a page, and
       an upgrade has no page to measure. A non-empty default would invent
       metadata the site never printed. */
    @Test
    fun `an existing novel upgrades with blank novel-info fields`() {
        for (v in 5..20) {
            open().use { c ->
                at(v, c)
                c.createStatement().use {
                    it.execute(
                        "INSERT INTO novels(folder,slug,url,title,started) " +
                            "VALUES('f','than-y','https://site/than-y/','Thần Y',1)",
                    )
                }
                Schema.upgrade(Jdbc(c), v)
                c.createStatement().use { s ->
                    s.executeQuery(
                        "SELECT alt_names, genres, source, description, status_label " +
                            "FROM novels WHERE slug='than-y'",
                    ).use { r ->
                        assertTrue("upgrading from v$v lost the novel", r.next())
                        assertEquals("upgrading from v$v: alt_names", "", r.getString(1))
                        assertEquals("upgrading from v$v: genres", "", r.getString(2))
                        assertEquals("upgrading from v$v: source", "", r.getString(3))
                        assertEquals("upgrading from v$v: description", "", r.getString(4))
                        assertEquals("upgrading from v$v: status_label", "", r.getString(5))
                    }
                }
            }
        }
    }

    /* Per-novel on-disk size arrives unknown: it is a measurement of the
       folder, and an upgrade has no folder to measure. A default of 0 would
       look like an empty library until something remeasured. */
    @Test
    fun `an existing novel upgrades with its stored size unknown`() {
        for (v in 5..21) {
            open().use { c ->
                at(v, c)
                c.createStatement().use {
                    it.execute(
                        "INSERT INTO novels(folder,slug,url,title,started) " +
                            "VALUES('f','than-y','https://site/than-y/','Thần Y',1)",
                    )
                }
                Schema.upgrade(Jdbc(c), v)
                c.createStatement().use { s ->
                    s.executeQuery(
                        "SELECT disk_bytes, disk_stamp_dir, disk_stamp_tr FROM novels WHERE slug='than-y'",
                    ).use { r ->
                        assertTrue("upgrading from v$v lost the novel", r.next())
                        assertEquals("upgrading from v$v: disk_bytes", -1L, r.getLong(1))
                        assertEquals("upgrading from v$v: disk_stamp_dir", "", r.getString(2))
                        assertEquals("upgrading from v$v: disk_stamp_tr", "", r.getString(3))
                    }
                }
            }
        }
    }

    /* Per-novel TTS language arrives unset: it is a choice, and an upgrade
       has no choice to copy. A default of "en" would pin every existing
       Vietnamese novel to an English voice on the first open after upgrade. */
    @Test
    fun `an existing novel upgrades with no TTS language override`() {
        for (v in 5..22) {
            open().use { c ->
                at(v, c)
                c.createStatement().use {
                    it.execute(
                        "INSERT INTO novels(folder,slug,url,title,started) " +
                            "VALUES('f','than-y','https://site/than-y/','Thần Y',1)",
                    )
                }
                Schema.upgrade(Jdbc(c), v)
                c.createStatement().use { s ->
                    s.executeQuery("SELECT tts_lang FROM novels WHERE slug='than-y'").use { r ->
                        assertTrue("upgrading from v$v lost the novel", r.next())
                        assertEquals("upgrading from v$v: tts_lang must stay Auto", "", r.getString(1))
                    }
                }
            }
        }
    }

    /* Anything older than v4 is rebuilt rather than migrated, and that is a
       deliberate data loss — it is all cache and index. It has to actually
       leave a working current schema behind, though. */
    @Test
    fun `a pre-v4 database is rebuilt into the current shape`() {
        open().use { c ->
            c.createStatement().use {
                it.execute("CREATE TABLE chapters (something TEXT)")
                it.execute("CREATE TABLE novels (other TEXT)")
            }
            Schema.upgrade(Jdbc(c), 3)
            assertEquals(fresh(), shape(c))
        }
    }
}
