package com.cleantv.app

/** What a title is. */
enum class Kind { MOVIE, SHOW }

/**
 * One movie or one TV show, after duplicates are merged. The home screen, details screen,
 * search and recommendations all work with these.
 */
data class Title(
    val kind: Kind,
    /** "m:..." for movies, "s:..." for shows. */
    val key: String,
    /** The merged show key (shows) or merged movie key (movies) from SeriesGrouper. */
    val ref: String,
    val name: String,
    val year: String?,
    val poster: String?,
    val backdrop: String?,
    val rating: Double?,
    val genres: List<String>,
    val plot: String?,
    val tmdb: String?,
    val categoryKeys: Set<String>,
    val added: Long,
    /** Movie stream ids (best first) or series ids. */
    val ids: List<Int>,
    val order: Int,
) {
    val label: String get() = if (year != null) "$name ($year)" else name
}

object Catalog {
    private val YEAR_SUFFIX = Regex("\\s*\\(((?:19|20)\\d{2})\\)\\s*$")

    @Volatile var movies: List<Title> = emptyList(); private set
    @Volatile var movieCategories: List<MergedCategory> = emptyList(); private set
    @Volatile var shows: List<Title> = emptyList(); private set
    @Volatile var showCategories: List<MergedCategory> = emptyList(); private set
    @Volatile var moviesLoaded = false; private set
    @Volatile var showsLoaded = false; private set

    private val byKey = HashMap<String, Title>()
    private val movieMeta = HashMap<Int, RawMeta>()
    private val showMeta = HashMap<Int, RawMeta>()

    @Synchronized fun title(key: String): Title? = byKey[key]
    @Synchronized fun movieExt(streamId: Int): String = movieMeta[streamId]?.ext ?: "mp4"

    val all: List<Title> get() = movies + shows

    /** Lists sorted once when the library loads, so building rows doesn't scan everything each time. */
    class Index(
        val byCategory: Map<String, List<Title>>,
        val newestMovies: List<Title>, val newestShows: List<Title>,
        val topMovies: List<Title>, val topShows: List<Title>,
        /** Genre -> titles, best rated first. */
        val byGenre: Map<String, List<Title>>,
        /** Bumped on every reload, so cached rows know they're out of date. */
        val version: Int,
    )

    @Volatile var index = Index(emptyMap(), emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(), 0); private set

    private fun reindex() {
        fun newest(list: List<Title>) = list.filter { it.added > 0 }.sortedByDescending { it.added }.take(40)
            .ifEmpty { list.sortedBy { it.order }.take(40) }
        fun top(list: List<Title>) = list.filter { (it.rating ?: 0.0) >= 7.3 }.sortedByDescending { it.rating }.take(40)
        val byCat = HashMap<String, MutableList<Title>>()
        val byGenre = HashMap<String, MutableList<Title>>()
        for (t in movies + shows) {
            for (c in t.categoryKeys) byCat.getOrPut((if (t.kind == Kind.MOVIE) "m|" else "s|") + c) { ArrayList() } += t
            for (g in t.genres) byGenre.getOrPut(g) { ArrayList() } += t
        }
        index = Index(
            byCat, newest(movies), newest(shows), top(movies), top(shows),
            byGenre.mapValues { (_, v) -> v.sortedByDescending { it.rating ?: 0.0 } },
            index.version + 1,
        )
    }

    fun inCategory(kind: Kind, key: String): List<Title> = index.byCategory[(if (kind == Kind.MOVIE) "m|" else "s|") + key] ?: emptyList()

    @Synchronized
    fun clear() {
        byKey.clear(); movieMeta.clear(); showMeta.clear()
        movies = emptyList(); shows = emptyList()
        movieCategories = emptyList(); showCategories = emptyList()
        moviesLoaded = false; showsLoaded = false
    }

    fun splitYear(display: String): Pair<String, String?> {
        val m = YEAR_SUFFIX.find(display) ?: return display to null
        return display.substring(0, m.range.first).trim() to m.groupValues[1]
    }

    fun genresOf(raw: String?): List<String> =
        raw.orEmpty().split(',', '/', '&', '|', ';')
            .map { it.trim().replaceFirstChar { c -> c.uppercase() } }
            .filter { it.length in 3..24 }
            .distinct()

    fun loadMovies(api: XtreamApi) {
        val meta = HashMap<Int, RawMeta>()
        val cats = api.vodCategories()
        val items = api.vodStreams(meta)
        val lib = SeriesGrouper.group(cats, items)
        val list = lib.shows.map { s ->
            val (name, yearFromName) = splitYear(s.name)
            val metas = s.seriesIds.mapNotNull { meta[it] }
            Title(
                kind = Kind.MOVIE, key = "m:" + s.key, ref = s.key, name = name,
                year = yearFromName ?: metas.firstNotNullOfOrNull { it.year },
                poster = s.cover, backdrop = metas.firstNotNullOfOrNull { it.backdrop },
                rating = metas.firstNotNullOfOrNull { it.rating },
                genres = genresOf(metas.firstNotNullOfOrNull { it.genre }),
                plot = metas.firstNotNullOfOrNull { it.plot },
                tmdb = s.key.removePrefix("tmdb:").takeIf { s.key.startsWith("tmdb:") }
                    ?: metas.firstNotNullOfOrNull { it.tmdb },
                categoryKeys = s.categoryKeys,
                added = metas.maxOfOrNull { it.added } ?: 0L,
                ids = s.seriesIds, order = s.order,
            )
        }
        synchronized(this) {
            movieMeta.clear(); movieMeta.putAll(meta)
            byKey.keys.removeAll { it.startsWith("m:") }
            list.forEach { byKey[it.key] = it }
            movies = list
            movieCategories = lib.categories
            moviesLoaded = true
            reindex()
        }
    }

    fun loadShows(api: XtreamApi) {
        val meta = HashMap<Int, RawMeta>()
        val lib = SeriesGrouper.group(api.seriesCategories(), api.series(meta))
        Shows.adopt(lib)
        val list = lib.shows.map { s ->
            val (name, yearFromName) = splitYear(s.name)
            val metas = s.seriesIds.mapNotNull { meta[it] }
            Title(
                kind = Kind.SHOW, key = "s:" + s.key, ref = s.key, name = name,
                year = yearFromName ?: metas.firstNotNullOfOrNull { it.year },
                poster = s.cover, backdrop = metas.firstNotNullOfOrNull { it.backdrop },
                rating = metas.firstNotNullOfOrNull { it.rating },
                genres = genresOf(metas.firstNotNullOfOrNull { it.genre }),
                plot = metas.firstNotNullOfOrNull { it.plot },
                tmdb = s.key.removePrefix("tmdb:").takeIf { s.key.startsWith("tmdb:") }
                    ?: metas.firstNotNullOfOrNull { it.tmdb },
                categoryKeys = s.categoryKeys,
                added = metas.maxOfOrNull { it.added } ?: 0L,
                ids = s.seriesIds, order = s.order,
            )
        }
        synchronized(this) {
            showMeta.clear(); showMeta.putAll(meta)
            byKey.keys.removeAll { it.startsWith("s:") }
            list.forEach { byKey[it.key] = it }
            shows = list
            showCategories = lib.categories
            showsLoaded = true
            reindex()
        }
    }

    /** Title matching for outside data: same TMDB id, else same name (and year when both have one). */
    fun normalise(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    fun find(kind: Kind?, tmdb: String?, name: String, year: String?): Title? {
        val pool = when (kind) { Kind.MOVIE -> movies; Kind.SHOW -> shows; null -> all }
        if (!tmdb.isNullOrBlank()) pool.firstOrNull { it.tmdb == tmdb }?.let { return it }
        val n = normalise(name)
        if (n.isEmpty()) return null
        val same = pool.filter { normalise(it.name) == n }
        return same.firstOrNull { year != null && it.year == year } ?: same.firstOrNull { it.year == null || year == null }
    }
}
