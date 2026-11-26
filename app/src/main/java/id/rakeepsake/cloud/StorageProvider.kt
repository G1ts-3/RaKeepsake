package id.rakeepsake.cloud

import android.content.ContentResolver
import android.net.Uri

/**
 * Antarmuka generik penyedia penyimpanan cloud.
 * Menggunakan [Uri] MediaStore + [ContentResolver] karena seluruh file media
 * yang dikelola RaKeepsake disimpan melalui MediaStore (`content://...`).
 */
interface StorageProvider {
    suspend fun isConnected(): Boolean

    /** Upload satu file dari [localUri]. Sukses mengembalikan ID file di penyimpanan remote. */
    suspend fun upload(
        contentResolver: ContentResolver,
        localUri: Uri,
        remoteName: String,
        mimeType: String,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<String>

    /** True hanya jika file remote ada, tidak di tempat sampah, dan ukurannya sama persis. */
    suspend fun verify(remoteId: String, expectedSizeBytes: Long): Boolean

    suspend fun disconnect()
}
