package me.piyaphat.kei.data.impl.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "upload_tasks")
data class UploadTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetSeriesId: Long?,
    val seriesTitle: String,
    val coverPageIndex: Int,
    val chapters: String,
    val permissionUris: String,
    val skipped: String,
    val state: String,
    val done: Int,
    val total: Int,
    val seriesId: Long?,
    val chapterIds: String,
    val error: String?
)

@Dao
interface UploadTaskDao {
    @Query("SELECT * FROM upload_tasks ORDER BY id DESC")
    fun observe(): Flow<List<UploadTaskEntity>>

    @Query("SELECT * FROM upload_tasks WHERE id = :id")
    suspend fun get(id: Long): UploadTaskEntity?

    @Query("SELECT * FROM upload_tasks")
    suspend fun all(): List<UploadTaskEntity>

    @Query("DELETE FROM upload_tasks WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: UploadTaskEntity): Long

    @Update
    suspend fun update(task: UploadTaskEntity)
}
