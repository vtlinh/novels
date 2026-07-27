package dev.vtlinh.noveldownloader

/* Every supported site owns its pages, and its manifest lives with them:
   src/sites/<site>/test/resources/pages/<site>/. Nothing about one site's
   corpus sits in a file another site also has to be edited into — adding a
   site is a new directory, and removing one is a deleted directory.

   The cross-site sweeps (RealPageTest, RealChapterTest) still want the whole
   corpus at once, so this reads each site's file and concatenates them. A
   site whose manifest is missing is a failure rather than an empty list: the
   sweeps assert over what they load, so a corpus that silently did not load
   would pass every one of them. */
object Manifests {

    /* One captured page, out of its own archive.

       This was written out three times — in SiteContract, in RealPageTest and
       in RealChapterTest — identically apart from the wording of the failure
       and one guard: only RealPageTest's copy checked that the archive's first
       entry is a file rather than a directory. That is what three copies do,
       and the guard is kept here, so every reader of the corpus now gets it.

       Each page is its own zip: whole third-party pages, so keeping them
       compressed keeps them out of repository search and off every clone's
       disk at a fifth of the size — and one archive per page means the one
       being debugged can be extracted on its own. */
    fun page(path: String): String {
        val ins = javaClass.getResourceAsStream("/pages/$path.zip")
            ?: throw AssertionError("missing fixture: pages/$path.zip")
        ins.use { raw ->
            java.util.zip.ZipInputStream(raw).use { zip ->
                val entry = zip.nextEntry
                    ?: throw AssertionError("empty archive: pages/$path.zip")
                if (entry.isDirectory) {
                    throw AssertionError("expected one page in pages/$path.zip")
                }
                return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
    }

    fun rows(name: String): Sequence<String> {
        val all = Sites.all.flatMap { site ->
            val path = "/pages/${site.name}/$name"
            val text = javaClass.getResourceAsStream(path)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: throw AssertionError(
                "missing $path — every site in Sites.all owes one, captured before " +
                    "its adapter was written (see CLAUDE.md)",
            )
            text.lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("site\t") }
                .toList()
        }
        return all.asSequence()
    }
}
