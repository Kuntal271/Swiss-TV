package com.swissdark.launcher.data

import com.swissdark.launcher.core.perf.Sections
import com.swissdark.launcher.core.perf.traced
import com.swissdark.launcher.domain.AppEntry
import com.swissdark.launcher.domain.SearchHit
import java.text.Normalizer

/**
 * Sorted array of normalised labels: app names, recent searches and cached TMDB titles.
 * Binary prefix lookup plus word-start match ("kids" finds "YouTube Kids"). Immutable;
 * rebuilt off the main thread and swapped in whole.
 */
class LocalIndex private constructor(
    private val keys: Array<String>,
    private val target: IntArray,
    private val hits: Array<SearchHit>,
) {
    val size: Int get() = hits.size

    fun lookup(query: String, limit: Int = 24): List<SearchHit> = traced(Sections.SEARCH_LOCAL) {
        val q = normalize(query)
        if (q.isEmpty()) return@traced emptyList()
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < q) lo = mid + 1 else hi = mid
        }
        val seen = BooleanArray(hits.size)
        val apps = ArrayList<SearchHit>()
        val others = ArrayList<SearchHit>()
        var i = lo
        while (i < keys.size && keys[i].startsWith(q) && apps.size + others.size < limit) {
            val t = target[i]
            if (!seen[t]) {
                seen[t] = true
                val h = hits[t]
                if (h is SearchHit.App) apps.add(h) else others.add(h)
            }
            i++
        }
        apps.sortBy { (it as SearchHit.App).app.label.length }
        apps + others
    }

    companion object {
        val EMPTY = LocalIndex(emptyArray(), IntArray(0), emptyArray())

        fun normalize(s: String): String {
            val d = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            val sb = StringBuilder(d.length)
            var space = false
            for (c in d) {
                when {
                    Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> {}
                    c.isLetterOrDigit() -> { sb.append(c); space = false }
                    !space && sb.isNotEmpty() -> { sb.append(' '); space = true }
                }
            }
            return sb.toString().trimEnd()
        }

        fun build(apps: List<AppEntry>, recent: List<SearchHit.Recent>, titles: List<SearchHit.Title>): LocalIndex {
            val all = ArrayList<SearchHit>(apps.size + recent.size + titles.size)
            apps.mapTo(all) { SearchHit.App(it) }
            all.addAll(recent)
            all.addAll(titles)
            val pairs = ArrayList<Pair<String, Int>>(all.size * 2)
            for ((i, h) in all.withIndex()) {
                val label = when (h) {
                    is SearchHit.App -> h.app.label
                    is SearchHit.Recent -> h.query
                    is SearchHit.Title -> h.ref.name
                    is SearchHit.PersonHit -> h.person.name
                }
                val n = normalize(label)
                if (n.isEmpty()) continue
                pairs.add(n to i)
                // Word starts: every suffix that begins after a space.
                var k = n.indexOf(' ')
                while (k >= 0) {
                    pairs.add(n.substring(k + 1) to i)
                    k = n.indexOf(' ', k + 1)
                }
            }
            pairs.sortBy { it.first }
            return LocalIndex(
                Array(pairs.size) { pairs[it].first },
                IntArray(pairs.size) { pairs[it].second },
                all.toTypedArray(),
            )
        }
    }
}
