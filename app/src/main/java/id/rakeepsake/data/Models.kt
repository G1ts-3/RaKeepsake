package id.rakeepsake.data

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import id.rakeepsake.net.AppUpdater
import id.rakeepsake.util.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Platform(val label: String, val hint: String) {
    TIKTOK("TikTok", "username (mis. johndoe)"),
    SHOWROOM("SHOWROOM", "room_url_key dari profil (mis. name.of.room)"),
    IDN("IDN Live", "slug live (mis. username)");
}

@Immutable
@Entity(tableName = "accounts", indices = [Index(value = ["platform", "handle"], unique = true)])
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val platform: String,
    val handle: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val recordLive: Boolean = true,
    val watchPosts: Boolean = false,
    val notifyOnly: Boolean = false,
    val lastPostId: String? = null,
    val lastLiveAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val memberColorHex: String? = null,
)

@Immutable
@Entity(tableName = "posts", indices = [Index(value = ["postId"], unique = true)])
data class Post(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val postId: String,
    val accountId: Long = 0,
    val type: String = "VIDEO", // VIDEO | PHOTO | AUDIO
    val caption: String = "",
    val author: String = "",
    val remoteCreatedAt: Long = 0,
    val downloadedAt: Long = 0,
    val filePaths: String = "",
    val status: String = "QUEUED", // QUEUED | DOWNLOADING | DONE | FAILED
    val error: String? = null,
    val thumbUrl: String? = null,
    val sourceUrl: String = "",
)

@Immutable
@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val platform: String,
    val handle: String,
    val startedAt: Long,
    val endedAt: Long = 0,
    val filePath: String = "",
    val sizeBytes: Long = 0,
    val status: String = "RECORDING", // RECORDING | DONE | FAILED | STOPPED
    val error: String? = null,
)

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY isFavorite DESC, createdAt DESC, id DESC")
    fun all(): Flow<List<Account>>

    @Query("SELECT * FROM accounts ORDER BY isFavorite DESC, createdAt DESC, id DESC")
    suspend fun allOnce(): List<Account>

    @Query("UPDATE accounts SET isFavorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("UPDATE accounts SET recordLive = :recordLive WHERE id IN (:ids)")
    suspend fun setRecordLiveBulk(ids: List<Long>, recordLive: Boolean)

    @Query("UPDATE accounts SET watchPosts = :watchPosts WHERE id IN (:ids) AND platform = 'TIKTOK'")
    suspend fun setWatchPostsBulk(ids: List<Long>, watchPosts: Boolean)

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: Long): Account?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(account: Account): Long

    @Update
    suspend fun update(account: Account)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE accounts SET lastPostId = :postId WHERE id = :id")
    suspend fun setLastPostId(id: Long, postId: String)

    @Query("UPDATE accounts SET lastLiveAt = :at WHERE id = :id")
    suspend fun setLastLiveAt(id: Long, at: Long)

    @Query("UPDATE accounts SET avatarUrl = :avatarUrl WHERE id = :id")
    suspend fun setAvatarUrl(id: Long, avatarUrl: String)

    @Query("DELETE FROM accounts WHERE handle NOT IN (:keepHandles)")
    suspend fun keepOnly(keepHandles: List<String>)

    @Query("DELETE FROM accounts WHERE platform = :platform AND handle = :handle")
    suspend fun deleteByPlatformAndHandle(platform: String, handle: String)

    @Query("SELECT * FROM accounts WHERE platform = :platform AND handle = :handle LIMIT 1")
    suspend fun byPlatformAndHandle(platform: String, handle: String): Account?

    @Query("UPDATE accounts SET recordLive = :recordLive, watchPosts = :watchPosts, notifyOnly = :notifyOnly WHERE handle = :handle")
    suspend fun updateOptions(handle: String, recordLive: Boolean, watchPosts: Boolean, notifyOnly: Boolean)
}

@Dao
interface PostDao {
    @Query("SELECT * FROM posts WHERE status != 'DELETED' ORDER BY CASE WHEN status IN ('QUEUED', 'DOWNLOADING') THEN 1 ELSE 0 END DESC, downloadedAt DESC, id DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<Post>>

    @Query("SELECT * FROM posts")
    suspend fun allOnce(): List<Post>

    @Query("SELECT * FROM posts WHERE postId = :postId LIMIT 1")
    suspend fun byPostId(postId: String): Post?

    @Query("SELECT * FROM posts WHERE status = :status")
    suspend fun byStatus(status: String): List<Post>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(post: Post): Long

    @Query("UPDATE posts SET status = :status, error = :error, filePaths = :filePaths, downloadedAt = :downloadedAt WHERE postId = :postId")
    suspend fun updateResult(postId: String, status: String, error: String?, filePaths: String, downloadedAt: Long)

    @Query("UPDATE posts SET accountId = :accountId, type = :type, caption = :caption, author = :author, sourceUrl = :sourceUrl WHERE postId = :postId")
    suspend fun updateResolvedMeta(postId: String, accountId: Long, type: String, caption: String, author: String, sourceUrl: String)

    @Query("UPDATE posts SET status = :status WHERE postId = :postId")
    suspend fun setStatus(postId: String, status: String)

    @Query("UPDATE posts SET status = 'DELETED', filePaths = '' WHERE id = :id")
    suspend fun markDeleted(id: Long)

    @Query("UPDATE posts SET status = 'DELETED', filePaths = '' WHERE postId = :postId")
    suspend fun markDeletedByPostId(postId: String)

    @Query("UPDATE posts SET status = 'DELETED', filePaths = ''")
    suspend fun markAllDeleted()

    @Query("DELETE FROM posts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM posts")
    suspend fun deleteAll()
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY startedAt DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE status = 'RECORDING'")
    fun active(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun byId(id: Long): Recording?

    @Insert
    suspend fun insert(rec: Recording): Long

    @Query("UPDATE recordings SET endedAt = :endedAt, filePath = :filePath, sizeBytes = :sizeBytes, status = :status, error = :error WHERE id = :id")
    suspend fun finish(id: Long, endedAt: Long, filePath: String, sizeBytes: Long, status: String, error: String?)

    @Query("UPDATE recordings SET sizeBytes = :sizeBytes WHERE id = :id")
    suspend fun setSize(id: Long, sizeBytes: Long)

    @Query("DELETE FROM recordings")
    suspend fun deleteAll()
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE accounts ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE accounts ADD COLUMN memberColorHex TEXT")
        db.execSQL("ALTER TABLE posts ADD COLUMN thumbUrl TEXT")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE posts ADD COLUMN sourceUrl TEXT NOT NULL DEFAULT ''")
    }
}

@Database(
    entities = [Account::class, Post::class, Recording::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun posts(): PostDao
    abstract fun recordings(): RecordingDao
}

@OptIn(FlowPreview::class)
@Stable
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val db: AppDatabase = Room.databaseBuilder(appContext, AppDatabase::class.java, "rakeepsake.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .fallbackToDestructiveMigration()
        .build()

    val settings: SettingsStore = SettingsStore(appContext)
    val updater: AppUpdater by lazy { AppUpdater(appContext, settings) }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // StateFlow hangat bersama di memori: semua tab berbagi 1 sumber tanpa re-kueri DB/MediaStore ulang!
    val accountsFlow: StateFlow<List<Account>> = db.accounts().all()
        .stateIn(ioScope, SharingStarted.Eagerly, emptyList())

    val recentPostsFlow: StateFlow<List<Post>> = db.posts().recent(100)
        .stateIn(ioScope, SharingStarted.Eagerly, emptyList())

    val settingsFlow: StateFlow<SettingsStore.Snapshot> = settings.snapshotFlow
        .stateIn(ioScope, SharingStarted.Eagerly, SettingsStore.Snapshot())

    private val _libraryEntries = MutableStateFlow<List<Storage.LibEntry>?>(null)
    val libraryEntries: StateFlow<List<Storage.LibEntry>?> = _libraryEntries.asStateFlow()

    private val _freeBytes = MutableStateFlow(0L)
    val freeBytes: StateFlow<Long> = _freeBytes.asStateFlow()

    private val _totalBytes = MutableStateFlow(0L)
    val totalBytes: StateFlow<Long> = _totalBytes.asStateFlow()

    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        ioScope.launch {
            kotlinx.coroutines.delay(250L)
            reloadLibraryNow()
        }
        ioScope.launch {
            refreshRequests.debounce(350L).collect {
                reloadLibraryNow()
            }
        }
        runCatching {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    refreshRequests.tryEmit(Unit)
                }
            }
            val cr = appContext.contentResolver
            cr.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
            cr.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            cr.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        }
    }

    fun refreshLibrary() {
        ioScope.launch { reloadLibraryNow() }
    }

    private fun reloadLibraryNow() {
        runCatching {
            _totalBytes.value = Storage.totalBytes()
            _freeBytes.value = Storage.freeBytes()
            _libraryEntries.value = Storage.queryLibrary(appContext)
        }
    }
}
