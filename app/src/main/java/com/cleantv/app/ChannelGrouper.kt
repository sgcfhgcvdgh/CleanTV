package com.cleantv.app

/**
 * Turns the provider's flat list of streams ("Sky Sports Main Event FHD",
 * "Sky Sports Main Event HD", "UK: Sky Sports Main Event UHD" ...) into one
 * channel per real channel, each holding its quality variants.
 *
 * Pure Kotlin (no Android imports) so it can be unit tested anywhere.
 */

data class Category(val id: String, val name: String)

data class Stream(
    val id: Int,
    val name: String,
    val categoryId: String,
    val icon: String?,
    val order: Int,
)

enum class Quality(val rank: Int, val label: String) {
    SD(10, "SD"),
    STANDARD(15, "Standard"),
    HD(20, "HD"),
    FHD(30, "FHD"),
    UHD(40, "4K / UHD");

    companion object {
        fun fromPref(value: String?): Quality = entries.firstOrNull { it.name == value } ?: HD
    }
}

data class Variant(
    val stream: Stream,
    val quality: Quality,
    val tags: List<String>,
    var label: String = "",
)

data class ChannelGroup(
    val key: String,
    val name: String,
    val variants: List<Variant>,
    val categoryKeys: Set<String>,
    val icon: String?,
    val order: Int,
) {
    /** Quality badges for the channel tile, best first, e.g. "UHD · FHD · HD". */
    val badges: String
        get() = variants.map { it.quality }.distinct().joinToString(" · ") {
            if (it == Quality.UHD) "UHD" else it.label
        }
}

data class MergedCategory(val key: String, val name: String, val order: Int)

data class GroupedLibrary(
    val categories: List<MergedCategory>,
    val channels: List<ChannelGroup>,
)

object ChannelGrouper {

    private val SUPERSCRIPTS = mapOf(
        'ᴬ' to 'A', 'ᴮ' to 'B', 'ᶜ' to 'C', 'ᴰ' to 'D', 'ᴱ' to 'E', 'ᶠ' to 'F', 'ᴳ' to 'G',
        'ᴴ' to 'H', 'ᴵ' to 'I', 'ᴶ' to 'J', 'ᴷ' to 'K', 'ᴸ' to 'L', 'ᴹ' to 'M', 'ᴺ' to 'N',
        'ᴼ' to 'O', 'ᴾ' to 'P', 'ᴿ' to 'R', 'ˢ' to 'S', 'ᵀ' to 'T', 'ᵁ' to 'U', 'ⱽ' to 'V',
        'ᵛ' to 'V', 'ᵂ' to 'W', 'ˣ' to 'X', '⁰' to '0', '¹' to '1', '²' to '2', '³' to '3',
        '⁴' to '4', '⁵' to '5', '⁶' to '6', '⁷' to '7', '⁸' to '8', '⁹' to '9', '⁺' to '+',
    )

    // Quality words as standalone tokens: "HD", "(FHD)", "[4K]", "1080p", "ᴴᴰ" (after conversion).
    private val QUALITY = Regex(
        "(?<![\\p{L}\\p{N}])(UHD|4K|2160P?|FHD|1080[PI]?|HD|720P?|SD|576P?|480P?)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )

    // Other variant markers that shouldn't split a channel into separate tiles.
    private val EXTRAS = Regex(
        "(?<![\\p{L}\\p{N}])(HEVC|H\\.?265|H\\.?264|HDR|\\d{2,3}\\s?FPS|RAW|BACKUP|ALT|MULTI|VIP)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )

    // Country / provider prefixes: "UK:", "UK |", "|UK|", "[UK]", "UK -", "UK ➤", "UK-ENG:".
    private val PREFIX = Regex(
        "^\\s*[\\[(|]?\\s*([A-Z]{2,4}(?:-[A-Z]{2,4})?)\\s*(?:[\\])|:➤►»•]|\\s-\\s?|-\\s)\\s*\\|?\\s*"
    )

    // Prefix glued to a quality: "UKFHD Premier Sports", "UK HD | Premier Sports", "UK FHD Premier".
    private val GLUED_PREFIX = Regex(
        "^\\s*[\\[(|]?\\s*([A-Z]{2,3}?)\\s?(UHD|FHD|HD|SD)(?![\\p{L}\\p{N}])\\s*[\\])|:\\-]?\\s*"
    )
    private val UK_VERSION = Regex("\\(?\\s*\\bUK\\s+VERSION\\b\\s*\\)?", RegexOption.IGNORE_CASE)
    private val IRISH = Regex(
        "[\\s(\\[]*\\b(IRISH\\s+VERSION|IRISH|IRE|IRELAND)\\b[\\s)\\]]*$",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING_COUNTRY = Regex("\\s+([A-Z]{2,3})$")
    private val SPORT_WORD = Regex("\\bsport\\b")

    private val EMPTY_BRACKETS = Regex("[(\\[{]\\s*[)\\]}]")
    private val EDGE_JUNK = Regex("^[\\s\\-|:•*_.,/]+|[\\s\\-|:•*_,/]+$|(?<![A-Z])\\.+$")
    private val SPACES = Regex("\\s{2,}")

    /** Converts superscript letters (ᴴᴰ) to normal letters, as their own word. */
    fun plainText(s: String): String {
        val sb = StringBuilder()
        var inSup = false
        for (c in s) {
            val mapped = SUPERSCRIPTS[c]
            if (mapped != null) {
                if (!inSup) sb.append(' ')
                sb.append(mapped)
                inSup = true
            } else {
                if (inSup) sb.append(' ')
                sb.append(c)
                inSup = false
            }
        }
        return sb.toString()
    }

    data class Parsed(val prefix: String, val base: String, val quality: Quality, val tags: List<String>)

    private fun qualityOf(token: String): Quality = when (token.uppercase()) {
        "UHD", "4K", "2160", "2160P" -> Quality.UHD
        "FHD", "1080", "1080P", "1080I" -> Quality.FHD
        "HD", "720", "720P" -> Quality.HD
        else -> Quality.SD
    }

    private fun tagLabel(token: String): String {
        val t = token.uppercase().replace(" ", "")
        return when {
            t == "HEVC" || (t.startsWith("H") && t.endsWith("265")) -> "HEVC"
            t.startsWith("H") && t.endsWith("264") -> "H264"
            t == "BACKUP" -> "Backup"
            t == "ALT" -> "Alt"
            t == "MULTI" -> "Multi"
            t == "VIP" -> "VIP"
            t == "RAW" -> "Raw"
            t == "HDR" -> "HDR"
            else -> t.lowercase() // 50fps / 60fps
        }
    }

    /** Candidate prefix (e.g. "UK") of a name, or null. */
    fun prefixCandidate(name: String): String? {
        val m = PREFIX.find(plainText(name)) ?: return null
        val rest = plainText(name).substring(m.range.last + 1)
        return if (rest.isBlank()) null else m.groupValues[1]
    }

    fun parse(name: String, acceptedPrefixes: Set<String>, categoryPrefix: String? = null): Parsed {
        var s = plainText(name)
        var prefix = ""
        var best: Quality? = null
        val m = PREFIX.find(s)
        if (m != null && m.groupValues[1] in acceptedPrefixes && s.substring(m.range.last + 1).isNotBlank()) {
            prefix = m.groupValues[1]
            s = s.substring(m.range.last + 1)
        } else {
            val g = GLUED_PREFIX.find(s)
            if (g != null && g.groupValues[1] in acceptedPrefixes && s.substring(g.range.last + 1).isNotBlank()) {
                prefix = g.groupValues[1]
                best = qualityOf(g.groupValues[2])
                s = s.substring(g.range.last + 1)
            }
        }
        QUALITY.findAll(s).forEach { q ->
            val ql = qualityOf(q.groupValues[1])
            if (best == null || ql.rank > best!!.rank) best = ql
        }
        val tags = EXTRAS.findAll(s).map { tagLabel(it.groupValues[1]) }.distinct().toList()
        var base = s.replace(QUALITY, " ").replace(EXTRAS, " ").replace(UK_VERSION, " ")
        base = base.replace(EMPTY_BRACKETS, " ")
        base = SPACES.replace(base, " ")
        base = EDGE_JUNK.replace(base, "").trim()
        // "( Irish Version )", "(IRE)", "Ireland" all mean the same feed.
        if (IRISH.containsMatchIn(base)) {
            val stripped = IRISH.replace(base, "").trim()
            if (stripped.isNotEmpty()) base = "$stripped (Ireland)"
        }
        // Country at the end instead of the start: "Sky Atlantic FHD UK".
        if (prefix.isEmpty()) {
            TRAILING_COUNTRY.find(base)?.let { t ->
                val rest = base.substring(0, t.range.first)
                if (t.groupValues[1] in acceptedPrefixes && rest.isNotBlank()) {
                    prefix = t.groupValues[1]
                    base = EDGE_JUNK.replace(rest, "").trim()
                }
            }
        }
        // No country in the name at all: use the category's ("UK | SPORTS").
        if (prefix.isEmpty() && categoryPrefix != null) prefix = categoryPrefix
        if (base.isEmpty()) base = EDGE_JUNK.replace(s, "").trim().ifEmpty { name.trim() }
        return Parsed(prefix, base, best ?: Quality.STANDARD, tags)
    }

    /** Removes quality / codec tags and tidies spacing: "Breaking Bad 4K HEVC" -> "Breaking Bad". */
    fun stripTags(text: String): String {
        var b = plainText(text).replace(QUALITY, " ").replace(EXTRAS, " ").replace(EMPTY_BRACKETS, " ")
        b = SPACES.replace(b, " ")
        return EDGE_JUNK.replace(b, "").trim()
    }

    /** Splits "UK: Something" into ("UK", "Something") when UK is a known prefix. */
    fun splitPrefix(name: String, acceptedPrefixes: Set<String>): Pair<String, String> {
        val s = plainText(name)
        val m = PREFIX.find(s) ?: return "" to s
        val rest = s.substring(m.range.last + 1)
        return if (m.groupValues[1] in acceptedPrefixes && rest.isNotBlank()) m.groupValues[1] to rest else "" to s
    }

    fun keyOf(prefix: String, base: String): String =
        prefix.uppercase() + "|" + base.lowercase().replace(SPORT_WORD, "sports").filter { it.isLetterOrDigit() || it == '+' || it == '&' }

    /** Category names get the same treatment so "UK SPORTS FHD" and "UK SPORTS HD" merge. */
    fun cleanCategoryName(name: String): String {
        val s = plainText(name)
        var c = s.replace(QUALITY, " ").replace(EXTRAS, " ").replace(EMPTY_BRACKETS, " ")
        c = SPACES.replace(c, " ")
        c = EDGE_JUNK.replace(c, "").trim()
        return c.ifEmpty { name.trim() }
    }

    fun categoryKey(name: String): String =
        cleanCategoryName(name).lowercase().filter { it.isLetterOrDigit() || it == '+' || it == '&' }

    fun group(categories: List<Category>, streams: List<Stream>, minPrefixCount: Int = 5): GroupedLibrary {
        // Only treat something as a prefix if lots of channels use it, so "ITV - Live" stays intact.
        val prefixCounts = HashMap<String, Int>()
        for (st in streams) prefixCandidate(st.name)?.let { prefixCounts[it] = (prefixCounts[it] ?: 0) + 1 }
        val accepted = prefixCounts.filter { it.value >= minPrefixCount }.keys

        val catPrefix = HashMap<String, String>()
        for (c in categories) prefixCandidate(c.name)?.takeIf { it in accepted }?.let { catPrefix[c.id] = it }

        // Merge categories by cleaned name.
        val catKeyById = HashMap<String, String>()
        val merged = LinkedHashMap<String, MergedCategory>()
        categories.forEachIndexed { i, c ->
            val k = categoryKey(c.name)
            catKeyById[c.id] = k
            if (k !in merged) merged[k] = MergedCategory(k, cleanCategoryName(c.name), i)
        }

        class Builder(val key: String, val name: String, val order: Int) {
            val variants = ArrayList<Variant>()
            val cats = LinkedHashSet<String>()
            var icon: String? = null
        }

        val groups = LinkedHashMap<String, Builder>()
        for (st in streams.sortedBy { it.order }) {
            val p = parse(st.name, accepted, catPrefix[st.categoryId])
            val key = keyOf(p.prefix, p.base)
            val b = groups.getOrPut(key) { Builder(key, p.base, st.order) }
            b.variants += Variant(st, p.quality, p.tags)
            catKeyById[st.categoryId]?.let { b.cats += it }
            if (b.icon.isNullOrBlank() && !st.icon.isNullOrBlank()) b.icon = st.icon
        }

        val channels = groups.values.map { b ->
            val sorted = b.variants.sortedWith(
                compareByDescending<Variant> { it.quality.rank }
                    .thenBy { it.tags.size }
                    .thenBy { it.stream.order }
            )
            // Human labels, numbering duplicates: "HD", "HD (2)".
            val seen = HashMap<String, Int>()
            for (v in sorted) {
                val base = (listOf(v.quality.label) + v.tags).joinToString(" ")
                val n = (seen[base] ?: 0) + 1
                seen[base] = n
                v.label = if (n == 1) base else "$base ($n)"
            }
            ChannelGroup(b.key, b.name, sorted, b.cats, b.icon, b.order)
        }
        return GroupedLibrary(merged.values.toList(), channels)
    }

    /** Order in which to try qualities, given the user's preferred default. */
    fun fallbackOrder(preferred: Quality): List<Quality> = when (preferred) {
        Quality.SD -> listOf(Quality.SD, Quality.STANDARD, Quality.HD, Quality.FHD, Quality.UHD)
        Quality.STANDARD, Quality.HD -> listOf(Quality.HD, Quality.STANDARD, Quality.FHD, Quality.SD, Quality.UHD)
        Quality.FHD -> listOf(Quality.FHD, Quality.HD, Quality.STANDARD, Quality.UHD, Quality.SD)
        Quality.UHD -> listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.STANDARD, Quality.SD)
    }

    /** Variants in the order they should be tried: first one is the default. */
    fun playOrder(group: ChannelGroup, preferred: Quality): List<Variant> {
        val order = fallbackOrder(preferred)
        return group.variants.sortedWith(
            compareBy<Variant> { order.indexOf(it.quality) }
                .thenBy { it.tags.size }
                .thenBy { it.stream.order }
        )
    }
}

/**
 * Your own channel order within a category (pure Kotlin, unit tested): channels you've arranged
 * come first in your order, channels new since then go at the bottom in the provider's order,
 * and channels the provider has removed simply drop out.
 */
object ChannelOrder {
    fun <T> apply(list: List<T>, saved: List<String>, keyOf: (T) -> String): List<T> {
        if (saved.isEmpty()) return list
        val rank = HashMap<String, Int>(saved.size * 2)
        saved.forEachIndexed { i, k -> if (k !in rank) rank[k] = i }
        val (known, fresh) = list.partition { keyOf(it) in rank }
        return known.sortedBy { rank[keyOf(it)] } + fresh
    }
}
