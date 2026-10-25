package com.swissdark.launcher.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** TMDB title cache, 30-day expiry. JSON blobs keep the schema small. */
@Entity(tableName = "titles")
data class TitleRow(
    @PrimaryKey val key: String,            // "MOVIE:123"
    val name: String,
    val json: String,                       // TitleDetails as JSON
    val fetchedAt: Long,
)

/** Cached remote search results per query, 24 h. */
@Entity(tableName = "queries")
data class QueryRow(
    @PrimaryKey val query: String,
    val json: String,
    val fetchedAt: Long,
)

/** Small cache of titles seen in search/trending (name + poster) for the local index. */
@Entity(tableName = "seen")
data class SeenRow(
    @PrimaryKey val key: String,
    val name: String,
    val json: String,                       // SearchHit.Title fields
    val seenAt: Long,
)

/** Titles opened from the launcher: the fallback "Continue" row and "Save for later". */
@Entity(tableName = "history")
data class HistoryRow(
    @PrimaryKey val key: String,            // "MOVIE:123"
    val tmdbId: Int,
    val type: String,
    val name: String,
    val servicePackage: String,
    val serviceComponent: String,
    val art: String?,
    val openedAt: Long,
    val savedForLater: Boolean,
)

/** Last 50 searches; FTS4 so "rec" finds "Recent search" by word start. */
@Fts4
@Entity(tableName = "searches")
data class SearchRow(
    val query: String,
    val kind: String,
    val at: Long,
)

@Dao
interface LauncherDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTitle(r: TitleRow)
    @Query("SELECT * FROM titles WHERE `key` = :key") suspend fun title(key: String): TitleRow?
    @Query("DELETE FROM titles WHERE fetchedAt < :before") suspend fun expireTitles(before: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putQuery(r: QueryRow)
    @Query("SELECT * FROM queries WHERE `query` = :q AND fetchedAt > :after") suspend fun query(q: String, after: Long): QueryRow?
    @Query("DELETE FROM queries WHERE fetchedAt < :before") suspend fun expireQueries(before: Long)
    /** Typed search queries (not "~trending/…", "~genre/…" or "~person/…" keys). */
    @Query("DELETE FROM queries WHERE fetchedAt < :before AND substr(`query`, 1, 1) != '~'") suspend fun expireTypedQueries(before: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSeen(r: List<SeenRow>)
    @Query("SELECT * FROM seen ORDER BY seenAt DESC LIMIT 1500") suspend fun seen(): List<SeenRow>
    /** The table otherwise grows with every search; only the newest rows are ever read. */
    @Query("DELETE FROM seen WHERE `key` NOT IN (SELECT `key` FROM seen ORDER BY seenAt DESC LIMIT :keep)") suspend fun trimSeen(keep: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putHistory(r: HistoryRow)
    @Query("SELECT * FROM history ORDER BY openedAt DESC LIMIT :n") suspend fun history(n: Int): List<HistoryRow>
    @Query("DELETE FROM history WHERE `key` = :key") suspend fun deleteHistory(key: String)

    @Insert suspend fun putSearch(r: SearchRow)
    @Query("DELETE FROM searches WHERE `query` = :q") suspend fun deleteSearch(q: String)
    @Query("SELECT `query`, kind, at FROM searches ORDER BY at DESC LIMIT 50") fun searches(): Flow<List<SearchRow>>
    @Query("SELECT `query`, kind, at FROM searches ORDER BY at DESC LIMIT 50") suspend fun searchesNow(): List<SearchRow>
    @Query("DELETE FROM searches WHERE at NOT IN (SELECT at FROM searches ORDER BY at DESC LIMIT 50)") suspend fun trimSearches()
}

@Database(entities = [TitleRow::class, QueryRow::class, SeenRow::class, HistoryRow::class, SearchRow::class], version = 1, exportSchema = false)
abstract class LauncherDb : RoomDatabase() {
    abstract fun dao(): LauncherDao

    companion object {
        fun open(context: Context): LauncherDb =
            Room.databaseBuilder(context, LauncherDb::class.java, "launcher.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
