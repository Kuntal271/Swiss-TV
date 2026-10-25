package com.swissdark.launcher.data

import android.content.Context
import com.swissdark.launcher.core.perf.Sections
import com.swissdark.launcher.core.perf.traced
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Launch score per app per hour of the week (168 buckets). Each launch adds 1 to its bucket;
 * once a day every bucket is multiplied by 0.97, so old habits fade in about a month.
 *
 * score(a, t) = 0.6 H(h) + 0.25 · ½(H(h−1) + H(h+1)) + 0.15 R
 * R = 1 if used in the last 2 hours, falling linearly to 0 over a day.
 */
class HabitStore(context: Context) {
    private val file = File(context.filesDir, "habits.bin")
    private val index = HashMap<String, Int>()
    private val names = ArrayList<String>()
    private var buckets = Array(0) { FloatArray(HOURS) }
    private val lastUsed = HashMap<String, Long>()
    private var lastDecayDay = 0L
    var seeded = false
        private set

    init {
        load()
    }

    @Synchronized
    fun record(pkg: String, timeMillis: Long) {
        val i = slot(pkg)
        buckets[i][hourOfWeek(timeMillis)] += 1f
        val prev = lastUsed[pkg] ?: 0L
        if (timeMillis > prev) lastUsed[pkg] = timeMillis
    }

    @Synchronized
    fun lastUsed(pkg: String): Long = lastUsed[pkg] ?: 0L

    /** Applies the daily 0.97 decay for every day since the last one. */
    @Synchronized
    fun decay(nowMillis: Long) {
        val day = nowMillis / DAY_MS
        if (lastDecayDay == 0L) {
            lastDecayDay = day; return
        }
        val days = (day - lastDecayDay).toInt()
        if (days <= 0) return
        val f = Math.pow(0.97, days.toDouble()).toFloat()
        for (b in buckets) for (h in 0 until HOURS) b[h] *= f
        lastDecayDay = day
    }

    @Synchronized
    fun score(pkg: String, nowMillis: Long): Float {
        val i = index[pkg] ?: return recency(pkg, nowMillis) * 0.15f
        val b = buckets[i]
        val h = hourOfWeek(nowMillis)
        val prev = b[(h + HOURS - 1) % HOURS]
        val next = b[(h + 1) % HOURS]
        return 0.6f * b[h] + 0.25f * 0.5f * (prev + next) + 0.15f * recency(pkg, nowMillis)
    }

    /** Ranks packages for "now": pinned first (in pinned order), then by score. */
    fun rank(pkgs: List<String>, pinned: List<String>, nowMillis: Long): List<String> = traced(Sections.HABIT_RANK) {
        val pinnedSet = pinned.toHashSet()
        val head = pinned.filter { it in pkgs }
        val rest = pkgs.filter { it !in pinnedSet }.sortedByDescending { score(it, nowMillis) }
        head + rest
    }

    private fun recency(pkg: String, now: Long): Float {
        val t = lastUsed[pkg] ?: return 0f
        val age = now - t
        return when {
            age <= 2 * HOUR_MS -> 1f
            age >= DAY_MS -> 0f
            else -> 1f - (age - 2 * HOUR_MS).toFloat() / (DAY_MS - 2 * HOUR_MS)
        }
    }

    /** First run: replay the last 30 days of usage so habits are useful from day one. */
    @Synchronized
    fun seed(launches: List<Pair<String, Long>>) {
        val now = System.currentTimeMillis()
        for ((pkg, t) in launches) {
            val ageDays = ((now - t) / DAY_MS).toInt()
            val i = slot(pkg)
            buckets[i][hourOfWeek(t)] += Math.pow(0.97, ageDays.toDouble()).toFloat()
            if (t > (lastUsed[pkg] ?: 0L)) lastUsed[pkg] = t
        }
        lastDecayDay = now / DAY_MS
        seeded = true
    }

    private fun slot(pkg: String): Int = index.getOrPut(pkg) {
        names.add(pkg)
        buckets = buckets.copyOf(names.size).let { arr -> Array(arr.size) { arr[it] ?: FloatArray(HOURS) } }
        names.size - 1
    }

    @Synchronized
    fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(VERSION)
            out.writeLong(lastDecayDay)
            out.writeBoolean(seeded)
            out.writeInt(names.size)
            for ((i, n) in names.withIndex()) {
                out.writeUTF(n)
                out.writeLong(lastUsed[n] ?: 0L)
                for (v in buckets[i]) out.writeFloat(v)
            }
        }
        tmp.renameTo(file)
    }

    private fun load() {
        if (!file.exists()) return
        try {
            DataInputStream(file.inputStream().buffered()).use { inp ->
                if (inp.readInt() != VERSION) return
                lastDecayDay = inp.readLong()
                seeded = inp.readBoolean()
                val n = inp.readInt()
                val b = ArrayList<FloatArray>(n)
                repeat(n) { i ->
                    val name = inp.readUTF()
                    names.add(name); index[name] = i
                    val lu = inp.readLong(); if (lu > 0) lastUsed[name] = lu
                    b.add(FloatArray(HOURS) { inp.readFloat() })
                }
                buckets = b.toTypedArray()
            }
        } catch (_: Exception) {
            names.clear(); index.clear(); buckets = Array(0) { FloatArray(HOURS) }
        }
    }

    companion object {
        const val HOURS = 168
        private const val VERSION = 1
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L

        fun hourOfWeek(millis: Long): Int {
            val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
            return (t.dayOfWeek.value - 1) * 24 + t.hour
        }
    }
}
