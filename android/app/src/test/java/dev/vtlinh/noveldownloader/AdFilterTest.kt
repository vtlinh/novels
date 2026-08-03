package dev.vtlinh.noveldownloader

import dev.vtlinh.noveldownloader.sites.Freewebnovel
import dev.vtlinh.noveldownloader.sites.Novelfull
import dev.vtlinh.noveldownloader.sites.Readnovel
import dev.vtlinh.noveldownloader.sites.Truyenfull
import dev.vtlinh.noveldownloader.sites.Truyenfullmoi
import org.jsoup.Jsoup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/* The ad-line filter, judged against the real pages it runs on.

   Three of its patterns used to be bare everyday phrases — "mở khóa"
   (unlock), "vui lòng" (please), "please report" — and a line of PROSE
   containing one was deleted from the saved chapter. The file on disk is the
   only copy, so the loss was permanent and invisible: nothing re-fetches a
   file that exists, TTS never read the sentence, and the paid translation
   was bought for the mutilated text. Every case here is a sentence that was
   actually being deleted from a captured page, or a furniture line the
   filter exists to remove — the pages are the measurement, not an
   imagination of one. */
class AdFilterTest {

    private fun body(site: Site, path: String): String =
        Extractor.parseChapter(Jsoup.parse(Manifests.page(path)), "x", 1, site)

    /* "…có thể làm Vương phi vui lòng…" — a consort being pleased, not a
       please-do-something notice. 197 characters, so the length cut alone
       does not save it. */
    @Test
    fun `vui long inside a sentence is prose, not furniture`() {
        assertTrue(
            body(Truyenfull, "truyenfull/chapters/tuong-cong-nha-ty-ty-sang-ruc-nhu-sao-tr__chuong-26.html")
                .contains("làm Vương phi vui lòng"),
        )
    }

    /* unlocking a phone screen, and a password unlocking a door — not a
       pay-to-unlock-this-chapter notice */
    @Test
    fun `mo khoa inside a sentence is prose, not furniture`() {
        assertTrue(
            body(Truyenfullmoi, "truyenfullmoi/chapters/hoa-hong-trao-ken-ken__chuong-14.html")
                .contains("mở khóa màn hình"),
        )
        assertTrue(
            body(Truyenfullmoi, "truyenfullmoi/chapters/gioi-tu-tien-nay-ky-la-qua__chuong-27.html")
                .contains("dùng mật khẩu để mở khóa"),
        )
    }

    /* an in-story bulletin-board post; "please report" with no "it" */
    @Test
    fun `please report inside the story is prose, not furniture`() {
        assertTrue(
            body(Freewebnovel, "freewebnovel/chapters/surviving-in-a-school-of-ghost-stories__chapter-1.html")
                .contains("please report to the student council"),
        )
    }

    /* ...and the other side, so a fix cannot pass by filtering nothing:
       the sites' real furniture still has to die. */
    @Test
    fun `the error-report line is still removed`() {
        assertFalse(
            body(Novelfull, "novelfull/chapters/the-kings-avatar__chapter-1-the-banished-battle-god.html")
                .contains("find any errors"),
        )
    }

    @Test
    fun `the royal road watermark is still removed`() {
        assertFalse(
            body(
                Readnovel,
                "readnovel/chapters/novel3039-my-big-goblin-space-program-isekai-faction-building-" +
                    "reincarnation-goblins-__chapter18-chapter-19-compound-penalty.html",
            ).contains("Royal Road"),
        )
    }
}
