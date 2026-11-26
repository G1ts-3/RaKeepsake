package id.rakeepsake.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import id.rakeepsake.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private object Keys {
        val POLL_SECONDS = intPreferencesKey("poll_seconds")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val MIN_FREE_MB = intPreferencesKey("min_free_mb")
        val MONITOR_WANTED = booleanPreferencesKey("monitor_wanted")
        val RELAY_URL = stringPreferencesKey("relay_url")
        val SAVE_WM_IF_ONLY_OPTION = booleanPreferencesKey("save_wm_if_only_option")
        val QUALITY = stringPreferencesKey("quality")            // ASLI | 1080 | 720 | 480
        val AUTO_RECORD = booleanPreferencesKey("auto_record")   // rekam otomatis saat live
        val MAX_PARALLEL = intPreferencesKey("max_parallel")     // mode hemat = 1
        val RETENTION_ON = booleanPreferencesKey("retention_on") // hapus otomatis
        val AUDIO_ONLY = booleanPreferencesKey("audio_only")     // unduh audio saja

        // dyrecord-compatible schedule window
        val SCHEDULE_ENABLED = booleanPreferencesKey("schedule_enabled")
        val SCHEDULE_START = intPreferencesKey("schedule_start") // menit dari tengah malam (default 1080 = 18:00)
        val SCHEDULE_END = intPreferencesKey("schedule_end")     // menit dari tengah malam (default 1440 = 24:00)

        // dyrecord-compatible storage quota & retention
        val STORAGE_AUTO_DELETE = booleanPreferencesKey("storage_auto_delete")
        val STORAGE_MAX_GB = intPreferencesKey("storage_max_gb")     // default 20 GB
        val STORAGE_MAX_DAYS = intPreferencesKey("storage_max_days") // default 7 hari
        val DELETE_AFTER_MOVE = booleanPreferencesKey("delete_after_move") // default true

        // TikTok session cookie
        val TIKTOK_SESSION = stringPreferencesKey("tiktok_session")

        // Preferensi UI & Kenangan (Favorit Pustaka, Mode Tampilan, Keamanan Layar)
        val FAVORITE_MEDIA = stringSetPreferencesKey("favorite_media")
        val LIBRARY_VIEW_MODE = stringPreferencesKey("library_view_mode")   // LIST | GRID
        val LIBRARY_GROUP_MODE = stringPreferencesKey("library_group_mode") // DATE | CREATOR
        val HIDE_SETTINGS_SCREENSHOTS = booleanPreferencesKey("hide_settings_screenshots")

        // Pembaruan aplikasi in-app (GitHub Releases / JSON manifest)
        val UPDATE_SOURCE = stringPreferencesKey("update_source")
        val AUTO_UPDATE_CHECK = booleanPreferencesKey("auto_update_check")
        val LAST_UPDATE_CHECK_AT = longPreferencesKey("last_update_check_at")
    }

    val pollSeconds: Flow<Int> = context.dataStore.data.map { it[Keys.POLL_SECONDS] ?: 60 }
    val wifiOnly: Flow<Boolean> = context.dataStore.data.map { it[Keys.WIFI_ONLY] ?: true }
    val minFreeMb: Flow<Int> = context.dataStore.data.map { it[Keys.MIN_FREE_MB] ?: 500 }
    val monitorWanted: Flow<Boolean> = context.dataStore.data.map { it[Keys.MONITOR_WANTED] ?: false }
    val relayUrl: Flow<String> = context.dataStore.data.map { it[Keys.RELAY_URL] ?: "" }
    val saveWmIfOnlyOption: Flow<Boolean> = context.dataStore.data.map { it[Keys.SAVE_WM_IF_ONLY_OPTION] ?: false }
    val quality: Flow<String> = context.dataStore.data.map { it[Keys.QUALITY] ?: "ASLI" }
    val autoRecord: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_RECORD] ?: true }
    val maxParallel: Flow<Int> = context.dataStore.data.map { it[Keys.MAX_PARALLEL] ?: 3 }
    val retentionOn: Flow<Boolean> = context.dataStore.data.map { it[Keys.RETENTION_ON] ?: false }
    val audioOnly: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUDIO_ONLY] ?: false }

    val scheduleEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.SCHEDULE_ENABLED] ?: false }
    val scheduleStart: Flow<Int> = context.dataStore.data.map { it[Keys.SCHEDULE_START] ?: 1080 } // 18:00
    val scheduleEnd: Flow<Int> = context.dataStore.data.map { it[Keys.SCHEDULE_END] ?: 1440 }     // 24:00

    val storageAutoDelete: Flow<Boolean> = context.dataStore.data.map { it[Keys.STORAGE_AUTO_DELETE] ?: false }
    val storageMaxGb: Flow<Int> = context.dataStore.data.map { it[Keys.STORAGE_MAX_GB] ?: 20 }
    val storageMaxDays: Flow<Int> = context.dataStore.data.map { it[Keys.STORAGE_MAX_DAYS] ?: 7 }
    val deleteAfterMove: Flow<Boolean> = context.dataStore.data.map { it[Keys.DELETE_AFTER_MOVE] ?: true }
    val tiktokSession: Flow<String> = context.dataStore.data.map {
        it[Keys.TIKTOK_SESSION]?.takeIf { s -> s.isNotBlank() } ?: "2db26e8650ba667ebb15f466ca55cccc"
    }
    val favoriteMedia: Flow<Set<String>> = context.dataStore.data.map { it[Keys.FAVORITE_MEDIA] ?: emptySet() }
    val libraryViewMode: Flow<String> = context.dataStore.data.map { it[Keys.LIBRARY_VIEW_MODE] ?: "LIST" }
    val libraryGroupMode: Flow<String> = context.dataStore.data.map { it[Keys.LIBRARY_GROUP_MODE] ?: "DATE" }
    val hideSettingsScreenshots: Flow<Boolean> = context.dataStore.data.map { it[Keys.HIDE_SETTINGS_SCREENSHOTS] ?: true }
    val updateSource: Flow<String> = context.dataStore.data.map {
        it[Keys.UPDATE_SOURCE]?.takeIf { s -> s.isNotBlank() } ?: BuildConfig.UPDATE_REPO
    }
    val autoUpdateCheck: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_UPDATE_CHECK] ?: true }
    val lastUpdateCheckAt: Flow<Long> = context.dataStore.data.map { it[Keys.LAST_UPDATE_CHECK_AT] ?: 0L }

    private fun fromPrefs(p: Preferences): Snapshot = Snapshot(
        pollSeconds = p[Keys.POLL_SECONDS] ?: 60,
        wifiOnly = p[Keys.WIFI_ONLY] ?: true,
        minFreeMb = p[Keys.MIN_FREE_MB] ?: 500,
        monitorWanted = p[Keys.MONITOR_WANTED] ?: false,
        relayUrl = p[Keys.RELAY_URL] ?: "",
        quality = p[Keys.QUALITY] ?: "ASLI",
        autoRecord = p[Keys.AUTO_RECORD] ?: true,
        maxParallel = p[Keys.MAX_PARALLEL] ?: 3,
        audioOnly = p[Keys.AUDIO_ONLY] ?: false,
        scheduleEnabled = p[Keys.SCHEDULE_ENABLED] ?: false,
        scheduleStart = p[Keys.SCHEDULE_START] ?: 1080,
        scheduleEnd = p[Keys.SCHEDULE_END] ?: 1440,
        storageAutoDelete = p[Keys.STORAGE_AUTO_DELETE] ?: false,
        storageMaxGb = p[Keys.STORAGE_MAX_GB] ?: 20,
        storageMaxDays = p[Keys.STORAGE_MAX_DAYS] ?: 7,
        deleteAfterMove = p[Keys.DELETE_AFTER_MOVE] ?: true,
        tiktokSession = p[Keys.TIKTOK_SESSION]?.takeIf { s -> s.isNotBlank() } ?: "2db26e8650ba667ebb15f466ca55cccc",
        favoriteMedia = p[Keys.FAVORITE_MEDIA] ?: emptySet(),
        libraryViewMode = p[Keys.LIBRARY_VIEW_MODE] ?: "LIST",
        libraryGroupMode = p[Keys.LIBRARY_GROUP_MODE] ?: "DATE",
        hideSettingsScreenshots = p[Keys.HIDE_SETTINGS_SCREENSHOTS] ?: true,
        updateSource = p[Keys.UPDATE_SOURCE]?.takeIf { s -> s.isNotBlank() } ?: BuildConfig.UPDATE_REPO,
        autoUpdateCheck = p[Keys.AUTO_UPDATE_CHECK] ?: true,
        lastUpdateCheckAt = p[Keys.LAST_UPDATE_CHECK_AT] ?: 0L,
    )

    val snapshotFlow: Flow<Snapshot> = context.dataStore.data.map { fromPrefs(it) }

    suspend fun snapshot(): Snapshot = fromPrefs(context.dataStore.data.first())

    @Immutable
    data class Snapshot(
        val pollSeconds: Int = 60,
        val wifiOnly: Boolean = true,
        val minFreeMb: Int = 500,
        val monitorWanted: Boolean = false,
        val relayUrl: String = "",
        val quality: String = "ASLI",
        val autoRecord: Boolean = true,
        val maxParallel: Int = 3,
        val audioOnly: Boolean = false,
        val scheduleEnabled: Boolean = false,
        val scheduleStart: Int = 1080,
        val scheduleEnd: Int = 1440,
        val storageAutoDelete: Boolean = false,
        val storageMaxGb: Int = 20,
        val storageMaxDays: Int = 7,
        val deleteAfterMove: Boolean = true,
        val tiktokSession: String = "2db26e8650ba667ebb15f466ca55cccc",
        val favoriteMedia: Set<String> = emptySet(),
        val libraryViewMode: String = "LIST",
        val libraryGroupMode: String = "DATE",
        val hideSettingsScreenshots: Boolean = true,
        val updateSource: String = BuildConfig.UPDATE_REPO,
        val autoUpdateCheck: Boolean = true,
        val lastUpdateCheckAt: Long = 0L,
    )

    suspend fun setPollSeconds(v: Int) = context.dataStore.edit { it[Keys.POLL_SECONDS] = v.coerceIn(30, 600) }
    suspend fun setWifiOnly(v: Boolean) = context.dataStore.edit { it[Keys.WIFI_ONLY] = v }
    suspend fun setMinFreeMb(v: Int) = context.dataStore.edit { it[Keys.MIN_FREE_MB] = v.coerceIn(100, 10000) }
    suspend fun setMonitorWanted(v: Boolean) = context.dataStore.edit { it[Keys.MONITOR_WANTED] = v }
    suspend fun setRelayUrl(v: String) = context.dataStore.edit { it[Keys.RELAY_URL] = v.trim().removeSuffix("/") }
    suspend fun setSaveWmIfOnlyOption(v: Boolean) = context.dataStore.edit { it[Keys.SAVE_WM_IF_ONLY_OPTION] = v }
    suspend fun setQuality(v: String) = context.dataStore.edit { it[Keys.QUALITY] = v }
    suspend fun setAutoRecord(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_RECORD] = v }
    suspend fun setMaxParallel(v: Int) = context.dataStore.edit { it[Keys.MAX_PARALLEL] = v.coerceIn(1, 5) }
    suspend fun setRetentionOn(v: Boolean) = context.dataStore.edit { it[Keys.RETENTION_ON] = v }
    suspend fun setAudioOnly(v: Boolean) = context.dataStore.edit { it[Keys.AUDIO_ONLY] = v }

    suspend fun setScheduleEnabled(v: Boolean) = context.dataStore.edit { it[Keys.SCHEDULE_ENABLED] = v }
    suspend fun setScheduleStart(v: Int) = context.dataStore.edit { it[Keys.SCHEDULE_START] = v }
    suspend fun setScheduleEnd(v: Int) = context.dataStore.edit { it[Keys.SCHEDULE_END] = v }

    suspend fun setStorageAutoDelete(v: Boolean) = context.dataStore.edit { it[Keys.STORAGE_AUTO_DELETE] = v }
    suspend fun setStorageMaxGb(v: Int) = context.dataStore.edit { it[Keys.STORAGE_MAX_GB] = v.coerceIn(1, 500) }
    suspend fun setStorageMaxDays(v: Int) = context.dataStore.edit { it[Keys.STORAGE_MAX_DAYS] = v.coerceIn(1, 365) }
    suspend fun setDeleteAfterMove(v: Boolean) = context.dataStore.edit { it[Keys.DELETE_AFTER_MOVE] = v }
    suspend fun setTiktokSession(v: String) = context.dataStore.edit { it[Keys.TIKTOK_SESSION] = v.trim() }

    suspend fun toggleFavoriteMedia(key: String) = context.dataStore.edit { prefs ->
        val cur = prefs[Keys.FAVORITE_MEDIA]?.toMutableSet() ?: mutableSetOf()
        if (!cur.add(key)) cur.remove(key)
        prefs[Keys.FAVORITE_MEDIA] = cur
    }
    suspend fun setLibraryViewMode(v: String) = context.dataStore.edit { it[Keys.LIBRARY_VIEW_MODE] = v }
    suspend fun setLibraryGroupMode(v: String) = context.dataStore.edit { it[Keys.LIBRARY_GROUP_MODE] = v }
    suspend fun setHideSettingsScreenshots(v: Boolean) = context.dataStore.edit { it[Keys.HIDE_SETTINGS_SCREENSHOTS] = v }
    suspend fun setUpdateSource(v: String) = context.dataStore.edit { it[Keys.UPDATE_SOURCE] = v.trim() }
    suspend fun setAutoUpdateCheck(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_UPDATE_CHECK] = v }
    suspend fun setLastUpdateCheckAt(v: Long) = context.dataStore.edit { it[Keys.LAST_UPDATE_CHECK_AT] = v }
}
