package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/* Packaging concatenates every unreleased PR summary under the versionName
   stamped on the APK. Previous versions keep the rows already sealed to
   them. The About page renders that, so a row with no version is a claim
   that this build is the first to ship it. */
class ReleaseNotesTest {

    @Test
    fun `blank versions concatenate under the current version`() {
        val tsv = """
            ${'\t'}Newer fix
            ${'\t'}Other fix
            1.33.21${'\t'}Split the novel page into tabs
        """.trimIndent()
        assertEquals(
            "1.33.22\n• Newer fix\n• Other fix\n\n1.33.21\n• Split the novel page into tabs",
            ReleaseNotes.render(tsv, "1.33.22"),
        )
    }

    @Test
    fun `the current version stays first even when its rows are not`() {
        val tsv = """
            1.33.20${'\t'}Stars
            ${'\t'}Notes
            1.33.19${'\t'}Sort
        """.trimIndent()
        val versions = ReleaseNotes.sections(tsv, "1.33.21").map { it.version }
        assertEquals(listOf("1.33.21", "1.33.20", "1.33.19"), versions)
    }

    @Test
    fun `newer version numbers sort above older ones`() {
        val tsv = """
            0.1${'\t'}First app
            1.30.10${'\t'}Mid
            1.1${'\t'}Manual
            1.33.1${'\t'}Recent
        """.trimIndent()
        val versions = ReleaseNotes.sections(tsv, "dev").map { it.version }
        assertEquals(listOf("1.33.1", "1.30.10", "1.1", "0.1"), versions)
    }

    @Test
    fun `comments empty lines and rows without a summary are ignored`() {
        val tsv = """
            # heading
            ${'\t'}
            1.33.21${'\t'}Kept
            not-a-row
        """.trimIndent()
        val entries = ReleaseNotes.parse(tsv)
        assertEquals(1, entries.size)
        assertEquals("1.33.21", entries[0].version)
        assertEquals("Kept", entries[0].summary)
    }

    @Test
    fun `patch 10 is newer than patch 9`() {
        assertTrue(ReleaseNotes.versionCmp("1.33.10", "1.33.9") > 0)
        assertTrue(ReleaseNotes.versionCmp("1.30.1", "1.1") > 0)
        assertTrue(ReleaseNotes.versionCmp("1.1", "0.1") > 0)
    }

    @Test
    fun `rows of the same version keep file order`() {
        val tsv = "1.30.84\tFirst\n1.30.84\tSecond\n"
        assertEquals(
            listOf("First", "Second"),
            ReleaseNotes.sections(tsv, "1.33.22").single { it.version == "1.30.84" }.summaries,
        )
    }

    @Test
    fun `a blank version column parses as unreleased`() {
        val entries = ReleaseNotes.parse("\tThis build")
        assertEquals(1, entries.size)
        assertNull(entries[0].version)
        assertEquals("This build", entries[0].summary)
    }

    /* The packaged file is what a device will see. A row that does not parse
       is a note that never appears; the unreleased row is this PR. */
    @Test
    fun `the packaged changelog parses and has this PR unreleased`() {
        val file = File("../changelog/notes.tsv")
        assertTrue("android/changelog/notes.tsv is the source the APK concatenates", file.isFile)
        val entries = ReleaseNotes.parse(file.readText())
        assertTrue("backfill is missing", entries.size > 100)
        assertTrue(
            "this PR's summary must be unreleased so package-time concat picks it up",
            entries.any { it.version == null && it.summary.contains("lost download folder") },
        )
        assertTrue(
            "1.33.25 shipped total novel storage on Settings",
            entries.any { it.version == "1.33.25" && it.summary.contains("novel storage") },
        )
        assertTrue(
            "1.33.24 shipped the Version dialog notes",
            entries.any { it.version == "1.33.24" && it.summary.contains("dialog from Version") },
        )
        assertTrue(
            "1.33.23 shipped the About-page notes",
            entries.any { it.version == "1.33.23" && it.summary.contains("Release notes") },
        )
        assertTrue(
            "1.33.21 shipped Split novel info and chapters into tabs",
            entries.any { it.version == "1.33.21" && it.summary.contains("tabs") },
        )
        val sealed = entries.mapNotNull { it.version }.toSet()
        assertTrue("0.1 era is missing", "0.1" in sealed)
        assertTrue("1.0 era is missing", "1.0" in sealed)
        assertTrue("1.1 era is missing", "1.1" in sealed)
    }
}
