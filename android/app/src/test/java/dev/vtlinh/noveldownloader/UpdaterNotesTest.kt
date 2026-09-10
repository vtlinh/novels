package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UpdaterNotesTest {

    @Test
    fun `update notes are this version from the downloaded APK`() {
        val tsv = "1.37.21\tShow this version's notes\n1.37.20\tOlder row\n"
        val zip = File.createTempFile("update", ".apk")
        try {
            ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("assets/changelog.tsv"))
                out.write(tsv.toByteArray())
                out.closeEntry()
            }
            assertEquals(
                "• Show this version's notes",
                Updater.notesFromApk(zip, "1.37.21"),
            )
            assertEquals("", Updater.notesFromApk(zip, "9.9.9"))
        } finally {
            zip.delete()
        }
    }
}
