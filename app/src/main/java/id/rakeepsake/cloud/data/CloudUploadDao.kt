package id.rakeepsake.cloud.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudUploadDao {
    @Query("SELECT * FROM cloud_upload ORDER BY updatedAt DESC")
    fun all(): Flow<List<CloudUpload>>

    @Query("SELECT * FROM cloud_upload ORDER BY updatedAt DESC")
    suspend fun allOnce(): List<CloudUpload>

    @Query("SELECT * FROM cloud_upload WHERE localPath = :localPath LIMIT 1")
    suspend fun byLocalPath(localPath: String): CloudUpload?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: CloudUpload)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(item: CloudUpload): Long

    @Update
    suspend fun update(item: CloudUpload)

    @Query("UPDATE cloud_upload SET sessionUri = :sessionUri, updatedAt = :updatedAt WHERE localPath = :localPath")
    suspend fun setSessionUri(
        localPath: String,
        sessionUri: String?,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE cloud_upload SET state = :state, lastError = :lastError, updatedAt = :updatedAt WHERE localPath = :localPath")
    suspend fun setState(
        localPath: String,
        state: UploadState,
        lastError: String? = null,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE cloud_upload SET state = :nextState, lastError = :error, updatedAt = :updatedAt WHERE state != :doneState")
    suspend fun markUnfinishedAs(
        nextState: UploadState,
        error: String? = null,
        doneState: UploadState = UploadState.DONE,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE cloud_upload SET state = :toState, lastError = null, attempts = 0, updatedAt = :updatedAt WHERE state = :fromState")
    suspend fun resetState(
        fromState: UploadState,
        toState: UploadState,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM cloud_upload WHERE localPath = :localPath")
    suspend fun deleteByLocalPath(localPath: String)
}
