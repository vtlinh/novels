package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/* What a Cursor agent reply becomes, and which model id each half picks.

   The HTTP client is not here. A fenced JSON object, a labeled fallback,
   and "Composer must not be the image model" are the claims. */
class ScenesTest {

    @Test
    fun `chapter files become scene names without the txt suffix`() {
        assertEquals("Chapter 12", Scenes.chapterBase("Chapter 12.txt"))
        assertEquals("Chapter 12", Scenes.chapterBase("Chapter 12.txt.gz"))
        assertEquals("Chapter 12.json", Scenes.jsonName("Chapter 12.txt"))
        assertEquals("Chapter 12.png", Scenes.imageName("Chapter 12.txt.gz"))
        assertEquals("Chapter 12.work.json", Scenes.workName("Chapter 12.txt"))
    }

    @Test
    fun `scene files are pictures or json, not chapters`() {
        assertTrue(Scenes.isSceneFile("Chapter 1.json"))
        assertTrue(Scenes.isSceneFile("Chapter 1.png"))
        assertTrue(Scenes.isSceneFile("Chapter 1.work.json"))
        assertFalse(Scenes.isSceneFile("Chapter 1.txt"))
        assertFalse(Scenes.isSceneFile("notes.txt"))
    }

    @Test
    fun `a JSON object in fences is the scene`() {
        val raw = """
            Here you go
            ```json
            {"summary":"She leaves town.","characters":"A tall woman in a red coat.","background":"Rainy dock at night."}
            ```
        """.trimIndent()
        val s = Scenes.parse(raw)!!
        assertEquals("She leaves town.", s.summary)
        assertEquals("A tall woman in a red coat.", s.characters)
        assertEquals("Rainy dock at night.", s.background)
    }

    @Test
    fun `escaped quotes and newlines survive a round trip`() {
        val scene = Scenes.Scene(
            "He said \"wait\".",
            "Hair:\nblack",
            "A \"quiet\" hall",
        )
        val again = Scenes.parse(Scenes.encode(scene))!!
        assertEquals(scene, again)
    }

    @Test
    fun `labeled headings are accepted when the model skips JSON`() {
        val raw = """
            Summary:
            The duel ends.

            Characters:
            A scarred swordsman.

            Background:
            Frozen courtyard.
        """.trimIndent()
        val s = Scenes.parse(raw)!!
        assertEquals("The duel ends.", s.summary)
        assertEquals("A scarred swordsman.", s.characters)
        assertEquals("Frozen courtyard.", s.background)
    }

    @Test
    fun `empty or unrelated text is not a scene`() {
        assertNull(Scenes.parse(""))
        assertNull(Scenes.parse("I updated the README."))
        assertFalse(Scenes.Scene("", "", "").ready())
    }

    @Test
    fun `a work file round-trips the agent ids`() {
        val w = Scenes.Work(Scenes.KIND_IMAGE, "bc-1", "run-2")
        assertEquals(w, Scenes.parseWork(Scenes.encodeWork(w)))
        assertNull(Scenes.parseWork("{}"))
    }

    @Test
    fun `Composer is the summary model and never the image model`() {
        val ids = listOf(
            "claude-4-sonnet-thinking",
            "composer-2",
            "composer-2.5",
            "gemini-3-pro-image-preview",
        )
        assertEquals("composer-2.5", Scenes.pickSummaryModel(ids))
        assertEquals("gemini-3-pro-image-preview", Scenes.pickImageModel(ids))
        assertEquals(Scenes.DEFAULT_SUMMARY_MODEL, Scenes.pickSummaryModel(emptyList()))
        assertEquals(
            "claude-4-sonnet-thinking",
            Scenes.pickImageModel(listOf("composer-2", "claude-4-sonnet-thinking")),
        )
    }

    @Test
    fun `Slack filename is SHA-256 of the chapter bytes and nothing else`() {
        /* SHA-256("abc") — known vector, proves the digest is not salted. */
        val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(abc, Scenes.contentHash("abc"))
        assertEquals("$abc.txt", Scenes.slackFileName("abc"))
        assertEquals(Scenes.contentHash("abc"), Scenes.contentHash("abc"))
        assertTrue(Scenes.contentHash("abc") != Scenes.contentHash("abc\n"))
        assertEquals(64, Scenes.contentHash("any chapter text").length)
        assertFalse(Scenes.slackFileName("abc").contains("scene"))
        assertFalse(Scenes.slackFileName("abc").startsWith("scene-"))
    }

    @Test
    fun `a long chapter is clipped in the middle, not the end`() {
        val text = "A".repeat(1000) + "MID" + "B".repeat(1000)
        val clipped = Scenes.clipChapter(text, max = 400)
        assertTrue(clipped.length <= 400)
        assertTrue(clipped.startsWith("A"))
        assertTrue(clipped.endsWith("B"))
        assertTrue(clipped.contains("middle omitted"))
        assertTrue(Scenes.summaryPrompt("Book", "ch").contains("\"summary\""))
        assertTrue(Scenes.imagePrompt("Book", Scenes.Scene("s", "c", "b")).contains("artifacts/scene.png"))
        assertTrue(Scenes.summaryPrompt("Book", "ch").contains("Do not generate an image"))
    }
}
