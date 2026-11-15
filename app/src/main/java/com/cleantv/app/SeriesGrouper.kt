package com.cleantv.app

/**
 * TV Shows clean-up (pure Kotlin, no Android imports).
 *
 * 1. Shows: the same show listed more than once ("Breaking Bad", "EN - Breaking Bad 4K",
 *    "Breaking Bad [MULTI-SUB]") becomes one show. Different years stay separate,
 *    e.g. "Doctor Who (1963)" and "Doctor Who (2005)".
 * 2. Episodes: every copy of S01E01 inside a show (or across merged shows) becomes one
 *    episode with several sources to choose from.
 */

data class SeriesItem(
    val id: Int,
    val name: String,
    val categoryId: String,
    val cover: String?,
    val order: Int,
    /** TMDB id from the provider, when it sends one. Best way to tell shows apart. */
    val tmdb: String? = null,
)

data class Show(
    val key: String,
    val name: String,
    val seriesIds: List<Int>,
    val categoryKeys: Set<String>,
    val cover: String?,
    val order: Int,
)

data class ShowLibrary(
    val categories: List<MergedCategory>,
    val shows: List<Show>,
)

data class EpisodeSource(
    val id: Int,
    val ext: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val height: Int?,
    val durationSecs: Int?,
    val order: Int,
)

data class Episode(
    val season: Int,
    val episode: Int,
    val title: String,
    val sources: List<EpisodeSource>,
    val labels: List<String>,
) {
    val key: String get() = "$season:$episode:$title"
}

object SeriesGrouper {

    private val YEAR = Regex("\\(\\s*((?:19|20)\\d{2})\\s*\\)")
    // Square-bracket tags like [MULTI-SUB] are dropped, but country tags like [UK] are kept.
    private val SQUARE_TAGS = Regex("\\[(?!\\s*[A-Z]{2,3}\\s*\\])[^\\]]*\\]")
    private val ANY_SQUARE = Regex("\\[[^\\]]*\\]")
    private val SXE = Regex("(?i)\\bS(\\d{1,4})\\s*[ .\\-]?\\s*E(\\d{1,4})\\b")
    private val NXN = Regex("\\b(\\d{1,2})x(\\d{1,3})\\b")
    private val JUNK = Regex("^[\\s\\-|:•*_.,/]+|[\\s\\-|:•*_.,/]+$")

    data class ParsedTitle(val prefix: String, val title: String, val year: String?)

    fun parseShowName(name: String, acceptedPrefixes: Set<String>): ParsedTitle {
        val (prefix, rest) = ChannelGrouper.splitPrefix(name, acceptedPrefixes)
        var s = rest.replace(SQUARE_TAGS, " ")
        val year = YEAR.find(s)?.groupValues?.get(1)
        s = s.replace(YEAR, " ")
        s = ChannelGrouper.stripTags(s)
        if (s.isEmpty()) s = name.trim()
        return ParsedTitle(prefix, s, year)
    }

    fun showKey(p: ParsedTitle): String =
        p.prefix.uppercase() + "|" + p.title.lowercase().filter { it.isLetterOrDigit() || it == '+' || it == '&' } +
            "|" + (p.year ?: "")

    fun group(categories: List<Category>, series: List<SeriesItem>, minPrefixCount: Int = 5): ShowLibrary {
        val prefixCounts = HashMap<String, Int>()
        for (s in series) ChannelGrouper.prefixCandidate(s.name)?.let { prefixCounts[it] = (prefixCounts[it] ?: 0) + 1 }
        val accepted = prefixCounts.filter { it.value >= minPrefixCount }.keys

        val catKeyById = HashMap<String, String>()
        val merged = LinkedHashMap<String, MergedCategory>()
        categories.forEachIndexed { i, c ->
            val k = ChannelGrouper.categoryKey(c.name)
            catKeyById[c.id] = k
            if (k !in merged) merged[k] = MergedCategory(k, ChannelGrouper.cleanCategoryName(c.name), i)
        }

        class B(val key: String, val name: String, val order: Int) {
            val ids = ArrayList<Int>()
            val cats = LinkedHashSet<String>()
            var cover: String? = null
        }

        val parsed = series.sortedBy { it.order }.map { it to parseShowName(it.name, accepted) }

        // Same TMDB id = same show; different TMDB ids = different shows, even with the same name.
        fun tmdbOf(s: SeriesItem) = s.tmdb?.trim()?.takeIf { it.isNotEmpty() && it != "0" && it != "null" }
        val tmdbsByName = HashMap<String, MutableSet<String>>()
        for ((s, p) in parsed) tmdbOf(s)?.let { tmdbsByName.getOrPut(showKey(p)) { HashSet() } += it }

        val groups = LinkedHashMap<String, B>()
        for ((s, p) in parsed) {
            val nameKey = showKey(p)
            val tmdb = tmdbOf(s) ?: tmdbsByName[nameKey]?.singleOrNull()
            val key = if (tmdb != null) "tmdb:$tmdb" else nameKey
            val display = if (p.year != null) "${p.title} (${p.year})" else p.title
            val b = groups.getOrPut(key) { B(key, display, s.order) }
            if (s.id !in b.ids) b.ids += s.id
            catKeyById[s.categoryId]?.let { b.cats += it }
            if (b.cover.isNullOrBlank() && !s.cover.isNullOrBlank()) b.cover = s.cover
        }
        return ShowLibrary(
            merged.values.toList(),
            groups.values.map { Show(it.key, it.name, it.ids, it.cats, it.cover, it.order) },
        )
    }

    /** Season/episode from the API fields, falling back to "S01 E02" / "1x02" in the title. */
    fun seasonEpisode(apiSeason: Int, apiEpisode: Int, title: String): Pair<Int, Int> {
        SXE.find(title)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        if (apiEpisode > 0) return apiSeason to apiEpisode
        NXN.find(title)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        return apiSeason to 0
    }

    /** "Marvel's Agents of S.H.I.E.L.D. S01 E01 - Pilot" -> "Pilot"; "" when there's no real title. */
    fun episodeTitle(raw: String, showName: String): String {
        var t = raw
        val base = showName.replace(YEAR, "").trim()
        if (base.isNotEmpty() && t.startsWith(base, ignoreCase = true)) t = t.substring(base.length)
        t = t.replace(YEAR, " ").replace(SXE, " ").replace(NXN, " ").replace(ANY_SQUARE, " ")
        t = ChannelGrouper.stripTags(t)
        t = JUNK.replace(t, "").trim()
        if (Regex("(?i)^(episode|ep|e)\\s*\\d+$").matches(t)) t = ""
        return t
    }

    private fun resolution(h: Int?): String? = when {
        h == null || h <= 0 -> null
        h >= 1800 -> "4K"
        h >= 900 -> "1080p"
        h >= 650 -> "720p"
        else -> "SD"
    }

    /** Merges all copies of each episode. Result is sorted by season then episode. */
    fun mergeEpisodes(sources: List<EpisodeSource>, showName: String): List<Episode> {
        val byKey = LinkedHashMap<String, MutableList<EpisodeSource>>()
        for (s in sources.distinctBy { it.id }.sortedBy { it.order }) {
            val key = if (s.episode > 0) "${s.season}:${s.episode}"
            else "${s.season}:t:" + episodeTitle(s.title, showName).lowercase().filter { it.isLetterOrDigit() }
                .ifEmpty { "id${s.id}" }
            byKey.getOrPut(key) { ArrayList() } += s
        }
        return byKey.values.map { copies ->
            // Best copy first: highest resolution, then provider order.
            val sorted = copies.sortedWith(compareByDescending<EpisodeSource> { it.height ?: 0 }.thenBy { it.order })
            val first = sorted.first()
            val title = sorted.map { episodeTitle(it.title, showName) }.firstOrNull { it.isNotEmpty() } ?: ""
            val labels = sorted.mapIndexed { i, s ->
                val parts = listOfNotNull(
                    resolution(s.height),
                    s.ext.uppercase().ifBlank { null },
                    s.durationSecs?.takeIf { it > 0 }?.let { "${(it + 30) / 60} min" },
                )
                "Copy ${i + 1}" + if (parts.isNotEmpty()) "  ·  " + parts.joinToString(" · ") else ""
            }
            Episode(first.season, first.episode, title, sorted, labels)
        }.sortedWith(compareBy<Episode> { if (it.season <= 0) Int.MAX_VALUE else it.season }.thenBy { it.episode })
    }

    /** Which copy to try first for the chosen default quality. Unknown resolutions keep provider order. */
    fun playOrder(ep: Episode, preferred: Quality): List<Int> {
        val target = when (preferred) {
            Quality.SD -> 480
            Quality.STANDARD, Quality.HD -> 720
            Quality.FHD -> 1080
            Quality.UHD -> 2160
        }
        return ep.sources.indices.sortedWith(
            compareBy<Int> { ep.sources[it].height?.let { h -> kotlin.math.abs(h - target) } ?: 100_000 }
                .thenByDescending { ep.sources[it].height ?: 0 }
                .thenBy { it }
        )
    }
}
