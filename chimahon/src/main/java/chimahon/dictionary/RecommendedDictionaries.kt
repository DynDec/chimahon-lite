package chimahon.dictionary

data class RecommendedDictionary(
    val id: String,
    val name: String,
    val languageCode: String,
    val kind: RecommendedDictionaryKind,
    val indexUrl: String = "",
    val downloadUrl: String = "",
)

enum class RecommendedDictionaryKind { Term, Names, Frequency, Encyclopedia }

// Catalog adapted from Hoshi Reader Android's DictionaryModels.kt.
val recommendedDictionaries = listOf(
    RecommendedDictionary(
        "jmdict",
        "JMdict",
        "ja",
        RecommendedDictionaryKind.Term,
        indexUrl = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMdict_english_without_proper_names.json",
    ),
    RecommendedDictionary(
        "jmnedict",
        "JMnedict",
        "ja",
        RecommendedDictionaryKind.Names,
        indexUrl = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMnedict.json",
    ),
    RecommendedDictionary(
        "jiten",
        "Jiten",
        "ja",
        RecommendedDictionaryKind.Frequency,
        indexUrl = "https://api.jiten.moe/api/frequency-list/index",
    ),
    RecommendedDictionary(
        "jitendex",
        "Jitendex",
        "ja",
        RecommendedDictionaryKind.Term,
        indexUrl = "https://jitendex.org/static/yomitan.json",
    ),
    RecommendedDictionary(
        "pixiv-light",
        "Pixiv Light",
        "ja",
        RecommendedDictionaryKind.Encyclopedia,
        indexUrl = "https://github.com/MarvNC/pixiv-yomitan/releases/latest/download/pixiv_light_index.json",
    ),
)

fun recommendedDictionariesForLanguage(languageCode: String): List<RecommendedDictionary> {
    val language = languageCode.trim().lowercase().substringBefore('-').substringBefore('_')
    // Chimahon's default profile has no language; let it access the whole catalog.
    return recommendedDictionaries.filter { language.isEmpty() || it.languageCode == language }
}
