package me.piyaphat.kei.data.impl.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey val id: Long, val title: String, val author: String, val tags: String,
    val coverUrl: String?, val chapterCount: Int, val pageCount: Int, val updated: Double,
    val progressChapterId: Long?, val progressPage: Int?, val progressPercent: Double?
)

@Entity(tableName = "chapters", indices = [Index("seriesId")])
data class ChapterEntity(
    @PrimaryKey val id: Long, val seriesId: Long, val number: Double, val title: String,
    val pageCount: Int, val presentCount: Int, val processedCount: Int, val translatedCount: Int,
    val state: String
)

@Entity(tableName = "continue_items")
data class ContinueEntity(
    @PrimaryKey val seriesId: Long, val seriesTitle: String, val coverUrl: String?,
    val chapterId: Long, val chapterTitle: String, val chapterNumber: Double,
    val page: Int, val percent: Double, val ts: Double
)

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey val chapterId: Long, val seriesId: Long, val seriesTitle: String,
    val chapterNumber: Double, val chapterTitle: String, val pageCount: Int,
    val presentCount: Int, val processedCount: Int, val translatedCount: Int, val state: String,
    val failed: String, val currentPage: Int?, val currentStage: String?
)

@Dao
interface SeriesDao {
    @Query("SELECT * FROM series ORDER BY updated DESC") fun observe(): Flow<List<SeriesEntity>>
    @Query("SELECT * FROM series") suspend fun all(): List<SeriesEntity>
    @Query("SELECT * FROM series WHERE id = :id") fun observe(id: Long): Flow<SeriesEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(items: List<SeriesEntity>)
    @Query("DELETE FROM series WHERE id NOT IN (:ids)") suspend fun deleteMissing(ids: List<Long>)
    @Query("DELETE FROM series") suspend fun clear()
    @Query("DELETE FROM series WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE seriesId = :id ORDER BY number") fun observe(id: Long): Flow<List<ChapterEntity>>
    @Query("SELECT * FROM chapters WHERE id = :id") suspend fun get(id: Long): ChapterEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(items: List<ChapterEntity>)
    @Query("DELETE FROM chapters WHERE seriesId = :id") suspend fun deleteSeries(id: Long)
    @Query("DELETE FROM chapters") suspend fun deleteAll()
    @Query("DELETE FROM chapters WHERE seriesId NOT IN (:ids)") suspend fun deleteMissing(ids: List<Long>)
    @Query("DELETE FROM chapters WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface ContinueDao {
    @Query("SELECT * FROM continue_items ORDER BY ts DESC") fun observe(): Flow<List<ContinueEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(items: List<ContinueEntity>)
    @Query("SELECT * FROM continue_items") suspend fun all(): List<ContinueEntity>
    @Query("DELETE FROM continue_items") suspend fun clear()
    @Query("DELETE FROM continue_items WHERE seriesId = :id") suspend fun deleteSeries(id: Long)
}

@Dao
interface JobDao {
    @Query("SELECT * FROM jobs WHERE state != 'idle'") fun observe(): Flow<List<JobEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(items: List<JobEntity>)
    @Query("SELECT * FROM jobs") suspend fun all(): List<JobEntity>
    @Query("DELETE FROM jobs") suspend fun clear()
    @Query("DELETE FROM jobs WHERE seriesId = :id") suspend fun deleteSeries(id: Long)
}

@Database(entities = [SeriesEntity::class, ChapterEntity::class, ContinueEntity::class, JobEntity::class,
    UploadTaskEntity::class], version = 2, exportSchema = false)
abstract class KeiDatabase : RoomDatabase() {
    abstract fun series(): SeriesDao
    abstract fun chapters(): ChapterDao
    abstract fun continueItems(): ContinueDao
    abstract fun jobs(): JobDao
    abstract fun uploadTasks(): UploadTaskDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `upload_tasks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `targetSeriesId` INTEGER,
                        `seriesTitle` TEXT NOT NULL,
                        `coverPageIndex` INTEGER NOT NULL,
                        `chapters` TEXT NOT NULL,
                        `permissionUris` TEXT NOT NULL,
                        `skipped` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `done` INTEGER NOT NULL,
                        `total` INTEGER NOT NULL,
                        `seriesId` INTEGER,
                        `chapterIds` TEXT NOT NULL,
                        `error` TEXT
                    )"""
                )
            }
        }
    }
}
