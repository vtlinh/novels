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
