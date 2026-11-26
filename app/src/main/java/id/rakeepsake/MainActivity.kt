package id.rakeepsake

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import id.rakeepsake.monitor.LiveMonitorService
import id.rakeepsake.ui.RaKeepsakeTheme
import id.rakeepsake.ui.RaScaffold
import id.rakeepsake.work.PostWatchWorker
import id.rakeepsake.work.Scheduling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as RaApp).container }
    private val scope = CoroutineScope(Dispatchers.Main)
    private var pollJob: Job? = null

    private var pendingLink by mutableStateOf<String?>(null)
    private var startTab by mutableStateOf(2)

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        handleIntent(intent)
        requestNotifIfNeeded()
        schedulePostWatch()
        resumeMonitorIfWanted()
        scope.launch(Dispatchers.IO) {
            // Tunda sinkronisasi avatar jaringan agar frame pertama saat startup tetap 60 fps mulus
            delay(1800L)
            container.syncAvatarsInternal()
        }
        scope.launch(Dispatchers.IO) {
            delay(3500L)
            runCatching { container.updater.checkIfDue() }
        }
        setContent {
            RaKeepsakeTheme {
                RaScaffold(
                    container = container,
                    initialTab = startTab,
                    pendingLink = pendingLink,
                    onLinkConsumed = { pendingLink = null },
                    onRequestNotif = { requestNotifIfNeeded() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        container.refreshLibrary()
        startForegroundPolling()
    }

    override fun onPause() {
        super.onPause()
        stopForegroundPolling()
    }

    private fun startForegroundPolling() {
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.IO) {
            delay(2500L) // Biarkan UI selesai merender layar awal tanpa beban DB/jaringan
            while (isActive) {
                try {
                    PostWatchWorker.checkAccounts(applicationContext, container)
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "Auto check accounts gagal: ${e.message}")
                }
                delay(30_000L) // Cek otomatis setiap 30 detik selama aplikasi dibuka
            }
        }
    }

    private fun stopForegroundPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val extraTab = intent?.getIntExtra("tab", -1) ?: -1
        if (extraTab in 0..4) {
            startTab = extraTab
        }
        val url: String? = when (intent?.action) {
            Intent.ACTION_SEND ->
                intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.data?.toString()
            else -> null
        }?.trim()
        if (url.isNullOrBlank()) return
        val link = if (url.contains("tiktok.com") || url.contains("showroom-live.com") || url.contains("idn.app")) {
            url
        } else {
            // Tautan dibagikan dengan teks tambahan: cari pola URL di dalamnya.
            Regex("https?://\\S+").findAll(url).map { it.value }.firstOrNull()
        }
        if (link != null) {
            startTab = 0
            if (link.contains("tiktok.com", ignoreCase = true)) {
                pendingLink = null
                scope.launch(Dispatchers.IO) { enqueue(link) }
            } else {
                pendingLink = link
            }
        }
    }

    private suspend fun enqueue(link: String) {
        val cleanUrl = id.rakeepsake.net.TiktokResolver.extractUrl(link)
        if (cleanUrl.contains("tiktok.com", ignoreCase = true)) {
            val author = id.rakeepsake.net.TiktokResolver.extractAuthorHandle(cleanUrl) ?: "tiktok"
            val initType = if (cleanUrl.contains("/story/", ignoreCase = true)) "STORY" else "VIDEO"
            val audioAlso = runCatching { container.settings.audioOnly.first() }.getOrDefault(false)
            val newPostId = "manual_${System.currentTimeMillis()}"
            container.db.posts().insert(
                id.rakeepsake.data.Post(
                    postId = newPostId,
                    type = initType,
                    caption = cleanUrl.take(200),
                    author = author,
                    status = "QUEUED",
                    sourceUrl = cleanUrl,
                ),
            )
            // Resolver dijalankan di dalam DownloadWorker: tautan halaman/pendek langsung diantrekan.
            PostWatchWorker.enqueueDownload(
                this,
                postId = newPostId,
                url = cleanUrl,
                author = author,
                caption = "",
                type = initType,
                audioOnly = audioAlso,
                sourceUrl = cleanUrl,
            )
        }
    }

    private fun requestNotifIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun resumeMonitorIfWanted() {
        scope.launch {
            delay(500L)
            val wanted = runCatching {
                container.settings.monitorWanted.first()
            }.getOrDefault(false)
            if (wanted) LiveMonitorService.start(this@MainActivity)
        }
    }

    private fun schedulePostWatch() {
        scope.launch(Dispatchers.Default) {
            delay(1800L)
            Scheduling.schedulePostWatch(this@MainActivity)
        }
    }
}
