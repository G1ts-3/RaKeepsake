package id.rakeepsake.monitor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import id.rakeepsake.RaApp
import id.rakeepsake.cloud.work.CloudUploadScheduler
import id.rakeepsake.data.Platform
import id.rakeepsake.data.Account
import id.rakeepsake.data.Recording
import id.rakeepsake.net.LiveStatus
import id.rakeepsake.net.PlatformApi
import id.rakeepsake.data.ScheduleWindow
import id.rakeepsake.util.Storage
import id.rakeepsake.work.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service pemantau live. Konstanta mengikuti pola dyrecord yang terverifikasi:
 * interval aktif = pollSeconds (default 60 dtk), idle = max(poll, 180), grace offline akun
 * 5 menit setelah live berakhir, wakelock 12 jam diperpanjang 15 menit sebelum habis.
 */
class LiveMonitorService : LifecycleService() {

    private val tickMutex = Mutex()
    private val active = ConcurrentHashMap<Long, ActiveRec>()
    private val seen = ConcurrentHashMap<String, Long>() // key -> waktu live terakhir berakhir
    private var settingsSnap: id.rakeepsake.data.SettingsStore.Snapshot? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeUntil: Long = 0
    private var lastPostCheckAt: Long = 0
    private val lastStreamUrls = ConcurrentHashMap<Long, String>()
    private val notifiedLive = ConcurrentHashMap.newKeySet<Long>()

    data class ActiveRec(
        val recordingId: Long,
        val thread: Thread,
        val recorder: HlsRecorder,
        val file: File,
        val account: Account,
        val startedAt: Long,
    )

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        acquireWake()
        recoverUnfinishedRecordings()
        lifecycleScope.launch(Dispatchers.Default) { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val settings = (application as RaApp).container.settings
        lifecycleScope.launch {
            settingsSnap = settings.snapshot()
            startInForeground()
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopAllRecorders()
                lifecycleScope.launch {
                    settings.setMonitorWanted(false)
                    stopSelf()
                }
            }
            ACTION_STOP_ACCOUNT -> {
                val accountId = intent.getLongExtra(EXTRA_ACCOUNT_ID, -1)
                if (accountId > 0) {
                    val activeRec = active[accountId]
                    if (activeRec != null) {
                        android.util.Log.i("LiveMonitorService", "Permintaan stop rekaman manual untuk @${activeRec.account.handle}")
                        activeRec.recorder.requestStop()
                    }
                }
            }
            ACTION_RECORD -> {
                val accountId = intent.getLongExtra(EXTRA_ACCOUNT_ID, -1)
                if (accountId > 0) lifecycleScope.launch(Dispatchers.IO) { recordAccountNow(accountId) }
            }
            ACTION_CHECK_NOW -> {
                lifecycleScope.launch(Dispatchers.IO) { tick() }
            }
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val notif = monitorNotification()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, Notifier.ID_MONITOR, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifier.ID_MONITOR, notif)
        }
    }

    private fun monitorNotification(): android.app.Notification {
        val n = active.size
        val now = LiveStateHolder.liveNow.size
        val totalBytes = LiveStateHolder.recording.values.sumOf { it.sizeBytes }
        val text = when {
            n > 0 -> "Merekam $n live · ${formatSize(totalBytes)} — ketuk untuk membuka"
            now > 0 -> "$now live berlangsung — dipantau"
            else -> "Pemantau aktif — mencari live"
        }
        val open = Intent(this, id.rakeepsake.MainActivity::class.java)
        return NotificationCompat.Builder(this, Notifier.CH_MONITOR)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("RaKeepsake")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 0, open,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .addAction(
                android.R.drawable.ic_delete, "Berhenti",
                android.app.PendingIntent.getService(
                    this, 1, Intent(this, LiveMonitorService::class.java).setAction(ACTION_STOP),
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    private fun refreshNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(Notifier.ID_MONITOR, monitorNotification())
    }

    // ---------- loop utama ----------

    private suspend fun loop() {
        val container = (application as RaApp).container
        while (true) {
            try {
                renewWakeIfNeeded()
                settingsSnap = container.settings.snapshot()
                val s = settingsSnap ?: continue
                if (!s.monitorWanted) {
                    stopSelf()
                    return
                }
                tick()
                val intervalSec = maxOf(s.pollSeconds, 20)
                LiveStateHolder.nextCheckAt = System.currentTimeMillis() + intervalSec * 1000
                delay(intervalSec * 1000L)
            } catch (t: Throwable) {
                LiveStateHolder.lastTickError = t.message ?: t.javaClass.simpleName
                delay(10_000L)
            }
        }
    }

    private suspend fun tick() = withContext(Dispatchers.IO) {
        if (!tickMutex.tryLock()) return@withContext
        try {
            val container = (application as RaApp).container
            val accounts = container.db.accounts().allOnce()
            val now = System.currentTimeMillis()
            LiveStateHolder.lastTickAt = now
            val s = settingsSnap
            val inSchedule = if (s != null) ScheduleWindow.inWindow(s.scheduleEnabled, s.scheduleStart, s.scheduleEnd, now) else true
            if (s != null && s.scheduleEnabled && !inSchedule) {
                LiveStateHolder.lastTickError = "Di luar jadwal rekam (${ScheduleWindow.formatMinutes(s.scheduleStart)} - ${ScheduleWindow.formatMinutes(s.scheduleEnd)})"
            }

            // Pertahankan state live untuk seluruh akun yang terdaftar
            val allAccountIds = accounts.map { it.id }.toSet()
            LiveStateHolder.liveNow.keys.retainAll(allAccountIds)
            LiveStateHolder.recording.keys.retainAll(allAccountIds)

            // Pantau postingan & story baru dari akun yang dipantau (tiap ~1 menit di latar belakang secara asinkron tanpa memblokir pemantau live)
            if (now - lastPostCheckAt >= 60_000L) {
                lastPostCheckAt = now
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        id.rakeepsake.work.PostWatchWorker.checkAccounts(this@LiveMonitorService, container)
                    } catch (_: Throwable) {}
                }
            }

            // Periksa seluruh akun yang belum aktif direkam
            val toCheck = accounts.filter { account ->
                val key = "${account.platform}:${account.handle}"
                if (active.containsKey(account.id)) return@filter false
                val cooldownEnd = seen[key] ?: 0
                now >= cooldownEnd
            }

            val sem = Semaphore(2)
            val checkResults = coroutineScope {
                toCheck.map { account ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            try {
                                val st = checkLive(account)
                                account to st
                            } catch (t: Throwable) {
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            for ((account, status) in checkResults) {
                if (!status.avatarUrl.isNullOrBlank() && PlatformApi.isAvatarUrlExpiredOrBlank(account.avatarUrl)) {
                    container.db.accounts().setAvatarUrl(account.id, status.avatarUrl)
                }
                if (status.live) {
                    LiveStateHolder.liveNow[account.id] = LiveStateHolder.LiveNow(
                        account.id, account.handle, account.platform, status.title, status.viewers,
                    )
                    container.db.accounts().setLastLiveAt(account.id, now)
                    val shouldRecord = account.recordLive && (s?.autoRecord ?: true) && inSchedule && !account.notifyOnly
                    android.util.Log.i("LiveMonitorService", "Live terdeteksi @${account.handle}: shouldRecord=$shouldRecord (recLive=${account.recordLive}, autoRec=${s?.autoRecord}, inSched=$inSchedule, notifOnly=${account.notifyOnly})")
                    if (!shouldRecord) {
                        // Notifikasi sekali per deteksi (bukan tiap tick).
                        if (notifiedLive.add(account.id)) {
                            Notifier.notify(
                                this@LiveMonitorService, Notifier.CH_RECORD,
                                Notifier.ID_RECORD_BASE + (account.id % 50).toInt(),
                                "${account.displayName} sedang live!",
                                status.title ?: "Buka ${account.platform} untuk menonton",
                            )
                        }
                    } else {
                        // Batas perekaman paralel (mode hemat = 1; default 3) — antre ke tick berikutnya.
                        val maxParallel = s?.maxParallel ?: 3
                        if (active.size >= maxParallel) {
                            LiveStateHolder.lastTickError = "Slot rekam penuh (${maxParallel}); ${account.handle} menunggu."
                        } else {
                            startRecording(account, status)
                        }
                    }
                } else {
                    LiveStateHolder.liveNow.remove(account.id)
                    notifiedLive.remove(account.id)
                }
            }
            refreshNotification()
        } finally {
            tickMutex.unlock()
        }
    }

    private suspend fun checkLive(account: Account): LiveStatus {
        val container = (application as RaApp).container
        return when (account.platform) {
            Platform.TIKTOK.name -> {
                val cookie = settingsSnap?.tiktokSession?.takeIf { it.isNotBlank() }
                    ?: runCatching { container.settings.tiktokSession.first() }.getOrNull()
                PlatformApi.tiktokLive(account.handle, cookie)
            }
            Platform.SHOWROOM.name -> PlatformApi.showroomLive(account.handle)
            Platform.IDN.name -> PlatformApi.idnLive(account.handle)
            else -> LiveStatus(false)
        }
    }

    private suspend fun recordAccountNow(accountId: Long) = withContext(Dispatchers.IO) {
        val container = (application as RaApp).container
        val account = container.db.accounts().byId(accountId) ?: return@withContext
        if (active.containsKey(accountId)) return@withContext
        // Tandai ke UI bahwa rekaman sedang disiapkan
        LiveStateHolder.recording[account.id] = LiveStateHolder.RecState(
            account.id, account.handle, account.platform, -1L, 0L,
        )
        val status = try {
            checkLive(account)
        } catch (t: Throwable) {
            LiveStateHolder.recording.remove(account.id)
            android.util.Log.e("LiveMonitorService", "Manual check live gagal @${account.handle}", t)
            Notifier.notify(this@LiveMonitorService, Notifier.CH_RECORD, 999, "Rekam manual gagal",
                t.message ?: t.javaClass.simpleName)
            return@withContext
        }
        if (!status.live) {
            LiveStateHolder.recording.remove(account.id)
            Notifier.notify(this@LiveMonitorService, Notifier.CH_RECORD, 999, "${account.displayName} tidak sedang live",
                "Coba lagi nanti.")
            return@withContext
        }
        LiveStateHolder.liveNow[account.id] = LiveStateHolder.LiveNow(
            account.id, account.handle, account.platform, status.title, status.viewers,
        )
        startRecording(account, status)
    }

    // ---------- perekaman ----------

    private fun startRecording(account: Account, status: LiveStatus) {
        val container = (application as RaApp).container
        val minFreeBytes = (settingsSnap?.minFreeMb ?: 500) * 1024L * 1024L
        val recDir = File(getExternalFilesDir(null), "rec").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val file = File(recDir, "${Storage.sanitize(account.handle)}_${ts}.bin")
        val recStartedAt = System.currentTimeMillis()
        val displayTitle = account.displayName.ifBlank { "@${account.handle}" }

        LiveStateHolder.recording[account.id] = LiveStateHolder.RecState(
            account.id, account.handle, account.platform, recStartedAt, 0L,
        )
        Notifier.notify(
            this, Notifier.CH_RECORD,
            Notifier.ID_RECORD_BASE + (account.id % 50).toInt(),
            "$displayTitle lagi live! Merekam sekarang…",
            status.title?.takeIf { it.isNotBlank() } ?: "Disimpan otomatis ke Pustaka (${account.platform})",
            openTab = 2,
        )

        lifecycleScope.launch(Dispatchers.IO) {
            val recRowId = container.db.recordings().insert(
                Recording(
                    accountId = account.id,
                    platform = account.platform,
                    handle = account.handle,
                    startedAt = recStartedAt,
                ),
            )
            val recorder = HlsRecorder(
                quality = settingsSnap?.quality ?: "ASLI",
                urlProvider = {
                    try {
                        val st = checkLive(account)
                        if (!st.live) {
                            android.util.Log.i("LiveMonitorService", "urlProvider: @${account.handle} terdeteksi offline.")
                            null
                        } else {
                            st.streamUrl ?: lastStreamUrls[account.id] ?: status.streamUrl
                        }
                    } catch (t: Throwable) {
                        lastStreamUrls[account.id] ?: status.streamUrl
                    }
                },
                outputFile = file,
                minFreeBytes = minFreeBytes,
                onProgress = { bytes ->
                    LiveStateHolder.recording[account.id] = LiveStateHolder.RecState(
                        account.id, account.handle, account.platform,
                        recStartedAt, bytes,
                    )
                    lifecycleScope.launch(Dispatchers.IO) {
                        container.db.recordings().setSize(recRowId, bytes)
                    }
                },
            )
            val thread = Thread {
                android.util.Log.i("LiveMonitorService", "Thread rekam dimulai: rec-${account.handle}")
                recorder.run()
                finalizeRecording(recRowId, account, file, recorder, recStartedAt)
            }
            thread.name = "rec-${account.handle}"
            thread.start()
            active[account.id] = ActiveRec(recRowId, thread, recorder, file, account, recStartedAt)
            status.streamUrl?.let { lastStreamUrls[account.id] = it }
            LiveStateHolder.recording[account.id] = LiveStateHolder.RecState(
                account.id, account.handle, account.platform, recStartedAt, 0L,
            )
            refreshNotification()
        }
    }

    private fun finalizeRecording(recRowId: Long, account: Account, file: File, recorder: HlsRecorder, startedAtMs: Long = 0L) {
        val container = (application as RaApp).container
        val outcome = recorder.outcome
        val endedAt = System.currentTimeMillis()
        var publishedUri: android.net.Uri? = null
        var publishedName = ""
        var publishedMime = ""
        try {
            if (!file.exists() || file.length() == 0L) {
                file.delete()
                lifecycleScope.launch(Dispatchers.IO) {
                    container.db.recordings().finish(
                        recRowId, endedAt, "", 0, "FAILED",
                        recorder.lastError ?: "Tidak ada data terekam",
                    )
                }
                Notifier.notify(
                    this, Notifier.CH_RECORD,
                    Notifier.ID_RECORD_BASE + (account.id % 50).toInt(),
                    "Rekaman ${account.handle} gagal", recorder.lastError ?: "Tidak ada data",
                )
                return
            }
            var targetFile = file
            var ext = sniffExt(targetFile)
            if (ext == "ts") {
                // Remux MPEG-TS ke MP4 standar tanpa re-encode via MediaExtractor/MediaMuxer
                val mp4File = File(file.parentFile, file.nameWithoutExtension + ".mp4")
                val remuxed = TsRemuxer.remuxToMp4(file, mp4File)
                if (remuxed != null && remuxed.exists() && remuxed.length() > 0L) {
                    targetFile = remuxed
                    ext = "mp4"
                    file.delete()
                }
            } else if (ext == "flv") {
                // Remux FLV siaran TikTok ke MP4 standar tanpa re-encode
                val mp4File = File(file.parentFile, file.nameWithoutExtension + ".mp4")
                val remuxed = FlvToMp4Remuxer.remuxToMp4(file, mp4File)
                if (remuxed != null && remuxed.exists() && remuxed.length() > 0L) {
                    targetFile = remuxed
                    ext = "mp4"
                    file.delete()
                } else {
                    // Fallback: normalisasi timestamp FLV jika remux MP4 gagal
                    val normFlv = File(file.parentFile, file.nameWithoutExtension + "_norm.flv")
                    val normalized = FlvRemuxer.normalize(file, normFlv)
                    if (normalized != null && normalized.exists() && normalized.length() > 0L) {
                        targetFile = normalized
                        file.delete()
                    }
                }
            }
            val fileSize = targetFile.length()
            val displayName = Storage.resolveUniqueFileName(
                context = this,
                platform = account.platform,
                type = "live",
                handle = account.handle,
                timestampMs = targetFile.lastModified(),
                ext = ext,
            )
            val relativeDir = "${Storage.APP_DIR}/${Storage.REC_DIR}/${account.platform}/${Storage.sanitize(account.handle)}"
            val mime = Storage.mimeFor(ext)
            val uri = Storage.publish(this, targetFile, relativeDir, displayName, mime)
            targetFile.delete()
            if (uri != null && (outcome.startsWith("DONE") || outcome.startsWith("STOPPED"))) {
                publishedUri = uri
                publishedName = displayName
                publishedMime = mime
            }
            lifecycleScope.launch(Dispatchers.IO) {
                container.db.recordings().finish(
                    recRowId, endedAt, uri?.toString() ?: displayName, fileSize,
                    if (outcome.startsWith("DONE") || outcome.startsWith("STOPPED")) "DONE" else "FAILED",
                    recorder.lastError?.takeIf { it.isNotBlank() && !outcome.startsWith("DONE") },
                )
            }
            val durText = if (startedAtMs > 0L) {
                " · ${id.rakeepsake.util.Humanizer.formatLongHoursMinutes(endedAt - startedAtMs)}"
            } else ""
            val displayTitle = account.displayName.ifBlank { "@${account.handle}" }
            Notifier.notify(
                this, Notifier.CH_RECORD,
                Notifier.ID_RECORD_BASE + (account.id % 50).toInt(),
                "Rekaman $displayTitle tersimpan",
                "Tersimpan${durText} · ${formatSize(fileSize)} ($displayName)",
                openTab = 1,
                actionLabel = "Tonton",
            )
            if (settingsSnap?.storageAutoDelete == true) {
                Storage.enforceQuota(
                    this@LiveMonitorService,
                    settingsSnap?.storageMaxGb ?: 20,
                    settingsSnap?.storageMaxDays ?: 7,
                )
                Storage.cleanupTempRaw(this@LiveMonitorService)
            }
        } catch (t: Throwable) {
            lifecycleScope.launch(Dispatchers.IO) {
                container.db.recordings().finish(recRowId, endedAt, file.path, file.length(), "FAILED",
                    t.message ?: t.javaClass.simpleName)
            }
            Notifier.notify(this, Notifier.CH_RECORD, Notifier.ID_RECORD_BASE + (account.id % 50).toInt(),
                "Rekaman ${account.handle} gagal disimpan", t.message ?: t.javaClass.simpleName)
        } finally {
            active.remove(account.id)
            LiveStateHolder.recording.remove(account.id)
            LiveStateHolder.liveNow.remove(account.id)
            seen.remove("${account.platform}:${account.handle}")
            refreshNotification()
            publishedUri?.let { uri ->
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching { CloudUploadScheduler.enqueueIfAuto(this@LiveMonitorService, uri, publishedName, publishedMime) }
                }
            }
            lifecycleScope.launch(Dispatchers.IO) {
                delay(2000L)
                tick()
            }
        }
    }

    private fun recoverUnfinishedRecordings() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val recDir = File(getExternalFilesDir(null), "rec")
                if (!recDir.exists()) return@launch
                val files = recDir.listFiles() ?: return@launch
                for (file in files) {
                    if (!file.isFile || file.length() < 100_000L) continue
                    val handle = file.nameWithoutExtension.substringBefore('_')
                    android.util.Log.i("LiveMonitorService", "Memulihkan rekaman yang tersisa: ${file.name} (${file.length()} bytes)")
                    var targetFile = file
                    var ext = sniffExt(targetFile)
                    if (ext == "ts") {
                        val mp4File = File(file.parentFile, file.nameWithoutExtension + ".mp4")
                        val remuxed = TsRemuxer.remuxToMp4(file, mp4File)
                        if (remuxed != null && remuxed.exists() && remuxed.length() > 0L) {
                            targetFile = remuxed
                            ext = "mp4"
                            file.delete()
                        }
                    } else if (ext == "flv") {
                        val normFlv = File(file.parentFile, file.nameWithoutExtension + "_norm.flv")
                        val normalized = FlvRemuxer.normalize(file, normFlv)
                        if (normalized != null && normalized.exists() && normalized.length() > 0L) {
                            targetFile = normalized
                            file.delete()
                        }
                    }
                    val fileSize = targetFile.length()
                    val displayName = Storage.resolveUniqueFileName(
                        context = this@LiveMonitorService,
                        platform = "TIKTOK",
                        type = "live",
                        handle = handle,
                        timestampMs = targetFile.lastModified(),
                        ext = ext,
                    )
                    val relativeDir = "${Storage.APP_DIR}/${Storage.REC_DIR}/TIKTOK/${Storage.sanitize(handle)}"
                    val mime = Storage.mimeFor(ext)
                    val uri = Storage.publish(this@LiveMonitorService, targetFile, relativeDir, displayName, mime)
                    targetFile.delete()
                    if (uri != null) {
                        runCatching { CloudUploadScheduler.enqueueIfAuto(this@LiveMonitorService, uri, displayName, mime) }
                    }
                    val container = (application as RaApp).container
                    container.db.recordings().insert(
                        Recording(
                            accountId = 0,
                            platform = "TIKTOK",
                            handle = handle,
                            startedAt = file.lastModified() - 60_000L,
                            endedAt = System.currentTimeMillis(),
                            filePath = uri?.toString() ?: displayName,
                            sizeBytes = fileSize,
                            status = "DONE",
                        )
                    )
                    android.util.Log.i("LiveMonitorService", "Rekaman tersisa @$handle berhasil dipulihkan ke pustaka: $uri ($fileSize bytes)")
                }
            } catch (t: Throwable) {
                android.util.Log.w("LiveMonitorService", "Pemulihan rekaman tersisa gagal: ${t.message}")
            }
        }
    }

    private fun sniffExt(file: File): String = try {
        file.inputStream().use { input ->
            val buf = ByteArray(8)
            val n = input.read(buf)
            if (n > 0 && buf[0] == 0x47.toByte()) "ts"
            else if (n >= 3 && buf[0] == 'F'.code.toByte() && buf[1] == 'L'.code.toByte() && buf[2] == 'V'.code.toByte()) "flv"
            else "mp4"
        }
    } catch (_: Exception) {
        "ts"
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1 shl 30 -> "%.1f GB".format(bytes.toDouble() / (1 shl 30))
        bytes >= 1 shl 20 -> "%.1f MB".format(bytes.toDouble() / (1 shl 20))
        bytes >= 1 shl 10 -> "%.1f KB".format(bytes.toDouble() / (1 shl 10))
        else -> "$bytes B"
    }

    // ---------- wakelock ----------

    private fun acquireWake() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock?.release()
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rakeepsake:monitor").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_MS)
        }
        wakeUntil = System.currentTimeMillis() + WAKELOCK_MS
    }

    private fun renewWakeIfNeeded() {
        if (System.currentTimeMillis() > wakeUntil - RENEW_BEFORE_MS) acquireWake()
    }

    private fun stopAllRecorders() {
        active.values.forEach { it.recorder.requestStop() }
    }

    override fun onDestroy() {
        stopAllRecorders()
        wakeLock?.release()
        wakeLock = null
        LiveStateHolder.clear()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "id.rakeepsake.action.STOP_MONITOR"
        const val ACTION_STOP_ACCOUNT = "id.rakeepsake.action.STOP_ACCOUNT_RECORDING"
        const val ACTION_RECORD = "id.rakeepsake.action.RECORD_NOW"
        const val ACTION_CHECK_NOW = "id.rakeepsake.action.CHECK_NOW"
        const val EXTRA_ACCOUNT_ID = "account_id"
        const val WAKELOCK_MS = 12 * 60 * 60_000L
        const val RENEW_BEFORE_MS = 15 * 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LiveMonitorService::class.java))
        }

        fun checkNow(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LiveMonitorService::class.java).setAction(ACTION_CHECK_NOW),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LiveMonitorService::class.java).setAction(ACTION_STOP),
            )
        }

        fun stopRecording(context: Context, accountId: Long) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LiveMonitorService::class.java)
                    .setAction(ACTION_STOP_ACCOUNT)
                    .putExtra(EXTRA_ACCOUNT_ID, accountId),
            )
        }

        fun recordNow(context: Context, accountId: Long) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LiveMonitorService::class.java)
                    .setAction(ACTION_RECORD)
                    .putExtra(EXTRA_ACCOUNT_ID, accountId),
            )
        }
    }
}
