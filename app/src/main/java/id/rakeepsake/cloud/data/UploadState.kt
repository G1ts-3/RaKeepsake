package id.rakeepsake.cloud.data

import androidx.room.TypeConverter

enum class UploadState {
    PENDING,
    QUEUED,
    UPLOADING,
    DONE,
    FAILED,
    NEEDS_RECONNECT,
    QUOTA_FULL,
}

class UploadStateConverters {
    @TypeConverter
    fun fromUploadState(state: UploadState): String = state.name

    @TypeConverter
    fun toUploadState(raw: String): UploadState =
        runCatching { UploadState.valueOf(raw) }.getOrDefault(UploadState.PENDING)
}
