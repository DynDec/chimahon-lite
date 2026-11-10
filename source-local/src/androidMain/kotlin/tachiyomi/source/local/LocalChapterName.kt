package tachiyomi.source.local

/** Tidies the name a local chapter is shown by. */
object LocalChapterName {

    /** Publisher notes such as 【イラスト特典付】 or 【期間限定】, brackets included. */
    private val bracketedNote = Regex("""【[^】]*】""")
    private val spaces = Regex("""\s{2,}""")

    /** [name] without its bracketed notes, or [name] itself when nothing else is left. */
    fun clean(name: String): String {
        if ('【' !in name) return name.trim()
        return name.replace(bracketedNote, " ")
            .replace(spaces, " ")
            .trim()
            .ifEmpty { name.trim() }
    }
}
