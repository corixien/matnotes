package com.matnotes.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** A spelling language: [code] names the bundled Hunspell dictionary (assets/dict/<code>.aff/.dic), [tag] is its BCP 47 tag. */
data class SpellLang(val code: String, val tag: String) {
    /** Name in the UI language, e.g. "Deutsch (Österreich)". */
    fun displayName(): String = Locale.forLanguageTag(tag).getDisplayName(Locale.getDefault())
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

data class SpellProblem(val word: String, val count: Int, val suggestions: List<String>)

/** Hunspell-based spell check of the document text, with language detection. All functions run off the main thread. */
object Spelling {
    val languages: List<SpellLang> = listOf(
        "en:en-US", "en-gb:en-GB", "en-au:en-AU", "en-ca:en-CA", "de:de-DE", "de-at:de-AT", "de-ch:de-CH", "fr:fr-FR",
        "es:es-ES", "es-mx:es-MX", "es-ar:es-AR", "pt:pt-BR", "pt-pt:pt-PT", "it:it-IT", "nl:nl-NL", "sv:sv-SE", "da:da-DK",
        "nb:nb-NO", "nn:nn-NO", "pl:pl-PL", "cs:cs-CZ", "sk:sk-SK", "sl:sl-SI", "hr:hr-HR", "sr-latn:sr-Latn", "sr:sr-Cyrl",
        "hu:hu-HU", "ro:ro-RO", "ca:ca-ES", "gl:gl-ES", "eu:eu-ES", "ga:ga-IE", "cy:cy-GB", "br:br-FR", "is:is-IS",
        "lt:lt-LT", "lv:lv-LV", "et:et-EE", "tr:tr-TR", "la:la", "lb:lb-LU", "vi:vi-VN", "ru:ru-RU", "uk:uk-UA", "bg:bg-BG",
        "mk:mk-MK", "el:el-GR",
    ).map { val (c, t) = it.split(":"); SpellLang(c, t) }

    private val byCode = languages.associateBy { it.code }

    /** Language for a BCP 47 tag ("de-DE" -> German dictionary); exact tag first, then the first with the same language. */
    fun forTag(tag: String?): SpellLang? {
        if (tag.isNullOrBlank()) return null
        val t = tag.replace('_', '-')
        languages.firstOrNull { it.tag.equals(t, true) }?.let { return it }
        val lang = t.substringBefore('-').lowercase()
        // plain language first: "de" before "de-AT"
        return languages.firstOrNull { it.tag.substringBefore('-').equals(lang, true) }
    }

    // ---- dictionaries ----

    private var openCode: String? = null
    private var open: Hunspell? = null
    private val lock = Any()

    private fun userWordsFile(ctx: Context) = File(ctx.filesDir, "dict/user-words.txt")

    private fun userWords(ctx: Context): List<String> =
        runCatching { userWordsFile(ctx).readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    /** Copies the dictionary out of the APK on first use (Hunspell needs real files) and opens it. */
    private fun dictionary(ctx: Context, lang: SpellLang): Hunspell? = synchronized(lock) {
        if (openCode == lang.code && open != null) return open
        open?.close(); open = null; openCode = null
        val dir = File(ctx.filesDir, "dict").apply { mkdirs() }
        // dictionaries from an older app version are replaced
        val marker = File(dir, ".v${BuildConfig.VERSION_CODE}")
        if (!marker.exists()) {
            dir.listFiles()?.filter { it.name != "user-words.txt" }?.forEach { it.delete() }
            marker.writeText("ok")
        }
        val files = listOf("aff", "dic").map { ext ->
            val out = File(dir, "${lang.code}.$ext")
            if (!out.exists()) {
                val tmp = File(dir, "${lang.code}.$ext.tmp")
                ctx.assets.open("dict/${lang.code}.$ext").use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                tmp.renameTo(out)
            }
            out
        }
        val h = Hunspell.open(files[0].absolutePath, files[1].absolutePath) ?: return null
        userWords(ctx).forEach { h.add(it) }
        open = h; openCode = lang.code
        h
    }

    fun addUserWord(ctx: Context, word: String) {
        synchronized(lock) { open?.add(word) }
        runCatching { userWordsFile(ctx).also { it.parentFile?.mkdirs() }.appendText(word + "\n") }
    }

    // ---- text ----

    private val urlLike = Regex("""(https?://|www\.)\S+|\S+@\S+\.\S+""")
    private val token = Regex("""\p{L}[\p{L}\p{M}]*(?:['’\-]\p{L}[\p{L}\p{M}]*)*""")

    /** Distinct words of [text] with their counts, in order of first appearance; acronyms and single letters are left out. */
    fun words(text: String): LinkedHashMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (m in token.findAll(urlLike.replace(text, " "))) {
            val w = m.value
            if (w.length < 2) continue
            if (w.length >= 2 && w.all { !it.isLowerCase() }) continue // ALL CAPS: acronym
            out[w] = (out[w] ?: 0) + 1
        }
        return out
    }

    private fun accepted(h: Hunspell, w: String): Boolean = h.spell(w.replace('’', '\'')) || h.spell(w.trim('-', '\''))

    /** Misspelled words of [text] in [lang], each with up to six suggestions. */
    suspend fun check(ctx: Context, lang: SpellLang, text: String): List<SpellProblem>? = withContext(Dispatchers.Default) {
        val h = runCatching { dictionary(ctx, lang) }.getOrNull() ?: return@withContext null
        words(text).mapNotNull { (w, n) ->
            if (accepted(h, w)) null else SpellProblem(w, n, h.suggest(w.replace('’', '\'')).take(6))
        }
    }

    // ---- language detection ----

    private fun set(s: String) = s.split(' ').filter { it.isNotEmpty() }.toSet()

    /** High-frequency function words per language, used to find the likely candidates among the Latin-script languages. */
    private val stopwords: Map<String, Set<String>> = mapOf(
        "en" to set("the of and to in is that it for was on with as at by this be are from or an have not but they his her you we which will would there their what all been one has had were can if more when who"),
        "de" to set("der die das und ist nicht ein eine zu den mit von sich auf für dem des im auch es als wie aber oder wird sind bei nach noch nur wenn war dass ich sie er wir ihr haben hat kann werden über um aus so"),
        "fr" to set("le la les de des du un une et est que qui dans pour pas sur au aux avec ce cette il elle nous vous ils sont être avoir plus par mais ou où comme se ne"),
        "es" to set("el la los las de del un una y es que en por con para no se su al lo como más pero sus le ya o este sí porque esta entre cuando muy sin sobre también"),
        "pt" to set("o a os as de do da dos das um uma e é que em por com para não se na no nas nos mais como mas ao à foi ser tem seu sua ou quando muito já também"),
        "it" to set("il lo la i gli le di del della dei un una e è che in per con non si su da come più ma al alla sono anche se questo questa ha hanno essere"),
        "nl" to set("de het een en van in is dat op te voor met zijn niet aan er ook als maar om bij of nog wordt dit door naar uit worden heeft hij zij we je ik"),
        "sv" to set("och i att det som en på är av för med den till inte har de om ett var jag men vi så kan från ska eller"),
        "da" to set("og i at det er en den til på af for med ikke har de om et var jeg men vi så kan fra skal eller som mig dig hvad meget noget"),
        "nb" to set("og i at det er en til på av for med ikke har de om et var jeg men vi så kan fra skal eller som meg deg hva veldig noe"),
        "nn" to set("og i at det er ein eit til på av for med ikkje har dei om var eg men vi så kan frå skal eller som kva mykje noko"),
        "pl" to set("i w nie na to się z że do jest jak ale po co tak za od przez dla czy tylko już ma są był być może przy też"),
        "cs" to set("a v se na je že to s z do o i pro ale jako by jsou byl být který tak také už ho jeho která"),
        "sk" to set("a v sa na je že to s z do o i pre ale ako by sú bol byť ktorý tak aj už jeho ktorá"),
        "sl" to set("in v je na se da za ki so pa po ne tudi kot od ali bi to več bil bo iz"),
        "hr" to set("i u je na se da za su s od koji koja koje ali kao što ne iz to će bio biti ili ovo smo"),
        "hu" to set("a az és hogy nem egy is meg de van volt még már csak mint ez azt ha vagy akkor el ki fel be"),
        "ro" to set("și în de la a cu pe un o nu să care este din pentru mai ca sau dar au fi se sunt"),
        "ca" to set("i el la de que en un una és per amb no els les del al com més però va són això"),
        "gl" to set("e o a de que en un unha é para con non se os as do da dos das ao como máis pero"),
        "eu" to set("eta da ez bat du zen izan dira baina ere hau hori egin zuen"),
        "ga" to set("agus an ar na is le sa go bhí ag ach nach tá sé sí mé tú"),
        "cy" to set("a y yr yn ac i o ar mae wedi am ei ond gan fel nid"),
        "is" to set("og að er í á ekki það sem var með hann hún við þetta fyrir en til"),
        "lt" to set("ir kad yra tai su iš bet ne kaip jis ji buvo į ar tik"),
        "lv" to set("un ka ir tas no par uz ar bet nav kā viņš viņa bija arī"),
        "et" to set("ja on ei see et oli kui ta aga mis ka siis oma või"),
        "tr" to set("ve bir bu için ile da de çok ama olarak gibi daha ne var kadar ben sen o değil"),
        "la" to set("et in est non ad cum ut quod sed qui quae ab ex de si per"),
        "lb" to set("an der den ass dat net mat vun fir ech och als mir sinn huet"),
        "vi" to set("và của là có không được trong một cho với những các này để khi đã"),
    )

    /** Dictionary candidates for the same detected language group (the stopword lists cannot tell these apart). */
    private val groupOf = mapOf("hr" to listOf("hr", "sr-latn"), "pt" to listOf("pt", "pt-pt"), "en" to listOf("en", "en-gb"))

    private fun scriptGuess(letters: String): List<String>? {
        var cyr = 0; var grk = 0; var total = 0
        for (c in letters) {
            if (!c.isLetter()) continue
            total++
            when (Character.UnicodeScript.of(c.code)) {
                Character.UnicodeScript.CYRILLIC -> cyr++
                Character.UnicodeScript.GREEK -> grk++
                else -> {}
            }
        }
        if (total == 0) return null
        if (grk * 2 > total) return listOf("el")
        if (cyr * 2 > total) {
            val l = letters.lowercase()
            return when {
                l.any { it in "іїєґ" } -> listOf("uk")
                l.any { it in "ќѓ" } -> listOf("mk")
                l.any { it in "ђћ" } -> listOf("sr")
                l.count { it == 'ъ' } * 40 > total && l.none { it in "ыэ" } -> listOf("bg", "ru")
                else -> listOf("ru", "uk", "bg")
            }
        }
        return null
    }

    /**
     * Guesses the language of [text]: script and stopwords give a few candidates, the dictionaries then decide by the
     * share of known words. [docTag] (document language reported by LibreOffice) and the device language join the
     * candidates and break ties. Falls back to [docTag], then the device language, then English.
     */
    suspend fun detect(ctx: Context, text: String, docTag: String?): SpellLang = withContext(Dispatchers.Default) {
        val sample = if (text.length > 20000) text.substring(0, 20000) else text
        val toks = token.findAll(urlLike.replace(sample, " ")).map { it.value.lowercase().replace('’', '\'') }.toList()
        val prior = listOfNotNull(forTag(docTag), forTag(Locale.getDefault().toLanguageTag()))
        val fallback = prior.firstOrNull() ?: byCode.getValue("en")
        if (toks.size < 3) return@withContext fallback

        val candidates = LinkedHashSet<String>()
        scriptGuess(sample)?.let { candidates += it }
        if (candidates.isEmpty()) {
            val scores = stopwords.mapValues { (_, sw) -> toks.count { it in sw } }.filterValues { it > 0 }
                .entries.sortedByDescending { it.value }
            scores.take(3).forEach { e -> groupOf[e.key]?.let { candidates += it } ?: run { candidates += e.key } }
        }
        prior.forEach { candidates += it.code }
        if (candidates.isEmpty()) return@withContext fallback

        val distinct = toks.filter { it.length > 1 }.distinct().take(400)
        var best: SpellLang? = null
        var bestScore = -1.0
        for (code in candidates) {
            val lang = byCode[code] ?: continue
            val h = runCatching { dictionary(ctx, lang) }.getOrNull() ?: continue
            if (distinct.isEmpty()) continue
            val known = distinct.count { h.spell(it) }.toDouble() / distinct.size
            val bonus = when { prior.firstOrNull()?.code == code -> 0.02; prior.any { it.code == code } -> 0.01; else -> 0.0 }
            if (known + bonus > bestScore) { bestScore = known + bonus; best = lang }
        }
        // under ~half the words known: the text is not really in any candidate language, trust the document language
        if (best == null || bestScore < 0.4) fallback else best
    }
}
