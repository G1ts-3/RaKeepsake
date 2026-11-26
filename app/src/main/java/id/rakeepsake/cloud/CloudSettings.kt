package id.rakeepsake.cloud

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.cloudDataStore by preferencesDataStore(name = "cloud_settings")

/**
 * Pengaturan fitur penyimpanan cloud (Google Drive) di DataStore Preferences terpisah
 * agar tidak berbenturan dengan DataStore `"settings"` milik `SettingsStore`.
 */
class CloudSettings(private val context: Context) {

    private object Keys {
        val CLOUD_ENABLED = booleanPreferencesKey("cloud_enabled")
        val AUTO_UPLOAD = booleanPreferencesKey("auto_upload")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val CHARGING_ONLY = booleanPreferencesKey("charging_only")
        val DELETE_AFTER_UPLOAD = booleanPreferencesKey("delete_after_upload")
        val DRIVE_FOLDER_ID = stringPreferencesKey("drive_folder_id")
        val CONNECTED_ACCOUNT_EMAIL = stringPreferencesKey("connected_account_email")
    }

    @Immutable
    data class Snapshot(
        val cloudEnabled: Boolean = false,
        val autoUpload: Boolean = false,
        val wifiOnly: Boolean = true,
        val chargingOnly: Boolean = false,
        val deleteAfterUpload: Boolean = false,
        val driveFolderId: String? = null,
        val connectedAccountEmail: String? = null,
    )

    private fun fromPrefs(p: Preferences): Snapshot = Snapshot(
        cloudEnabled = p[Keys.CLOUD_ENABLED] ?: false,
        autoUpload = p[Keys.AUTO_UPLOAD] ?: false,
        wifiOnly = p[Keys.WIFI_ONLY] ?: true,
        chargingOnly = p[Keys.CHARGING_ONLY] ?: false,
        deleteAfterUpload = p[Keys.DELETE_AFTER_UPLOAD] ?: false,
        driveFolderId = p[Keys.DRIVE_FOLDER_ID]?.takeIf { it.isNotBlank() },
        connectedAccountEmail = p[Keys.CONNECTED_ACCOUNT_EMAIL]?.takeIf { it.isNotBlank() },
    )

    val snapshotFlow: Flow<Snapshot> = context.cloudDataStore.data.map { fromPrefs(it) }

    suspend fun snapshot(): Snapshot = fromPrefs(context.cloudDataStore.data.first())

    suspend fun setCloudEnabled(enabled: Boolean) = context.cloudDataStore.edit {
        it[Keys.CLOUD_ENABLED] = enabled
    }

    suspend fun setAutoUpload(enabled: Boolean) = context.cloudDataStore.edit {
        it[Keys.AUTO_UPLOAD] = enabled
    }

    suspend fun setWifiOnly(wifiOnly: Boolean) = context.cloudDataStore.edit {
        it[Keys.WIFI_ONLY] = wifiOnly
    }

    suspend fun setChargingOnly(chargingOnly: Boolean) = context.cloudDataStore.edit {
        it[Keys.CHARGING_ONLY] = chargingOnly
    }

    suspend fun setDeleteAfterUpload(deleteAfterUpload: Boolean) = context.cloudDataStore.edit {
        it[Keys.DELETE_AFTER_UPLOAD] = deleteAfterUpload
    }

    suspend fun setDriveFolderId(folderId: String?) = context.cloudDataStore.edit {
        val clean = folderId?.trim().orEmpty()
        if (clean.isBlank()) it.remove(Keys.DRIVE_FOLDER_ID)
        else it[Keys.DRIVE_FOLDER_ID] = clean
    }

    suspend fun setConnectedAccountEmail(email: String?) = context.cloudDataStore.edit {
        val clean = email?.trim().orEmpty()
        if (clean.isBlank()) it.remove(Keys.CONNECTED_ACCOUNT_EMAIL)
        else it[Keys.CONNECTED_ACCOUNT_EMAIL] = clean
    }

    /** Menghapus status akun terhubung & folder Drive tersimpan saat pengguna memutus koneksi. */
    suspend fun clearConnection() = context.cloudDataStore.edit {
        it[Keys.CLOUD_ENABLED] = false
        it.remove(Keys.CONNECTED_ACCOUNT_EMAIL)
        it.remove(Keys.DRIVE_FOLDER_ID)
    }
}
