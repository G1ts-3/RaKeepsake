package id.rakeepsake.cloud.data

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

@Immutable
@Entity(tableName = "cloud_upload")
data class CloudUpload(
    @PrimaryKey val localPath: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val state: UploadState,
    val remoteId: String? = null,
    val sessionUri: String? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)
