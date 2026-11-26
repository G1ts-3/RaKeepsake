package id.rakeepsake.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import id.rakeepsake.BuildConfig
import id.rakeepsake.R
import id.rakeepsake.cloud.ui.CloudSettingsSection
import id.rakeepsake.data.AppContainer
import id.rakeepsake.data.ScheduleWindow
import id.rakeepsake.monitor.LiveMonitorService
import id.rakeepsake.net.AppUpdater
import id.rakeepsake.util.Humanizer
import kotlinx.coroutines.launch

/**
 * Tab Setelan:
 * - Kartu Merek "Keepsake" di atas (logo polaroid, versi, statistik kenangan personal)
 * - Pengelompokan prioritas (Perekaman -> Unduhan & Penyimpanan -> Izin & Baterai -> Lanjutan terlipat)
 * - Kartu panduan MIUI dapat dilipat dengan status hijau ✓ jika sudah diatur
 * - Session ID dimaskir default (`••••••••••ccc`) + tombol mata/salin + FLAG_SECURE saat dibuka
 * - Penjelasan istilah teknis (Session ID & Relay) lewat ikon `ⓘ`
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    modifier: Modifier,
    onRequestNotif: () -> Unit,
    isActiveTab: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = context as? LifecycleOwner

    val settings by container.settingsFlow.collectAsState()
    val monitorWanted = settings.monitorWanted
    val pollSeconds = settings.pollSeconds
    val autoRecord = settings.autoRecord
    val quality = settings.quality
    val maxParallel = settings.maxParallel
    val wifiOnly = settings.wifiOnly
    val audioOnly = settings.audioOnly
    val minFreeMb = settings.minFreeMb

    val scheduleEnabled = settings.scheduleEnabled
    val scheduleStart = settings.scheduleStart
    val scheduleEnd = settings.scheduleEnd

    val storageAutoDelete = settings.storageAutoDelete
    val storageMaxGb = settings.storageMaxGb
    val storageMaxDays = settings.storageMaxDays
    val deleteAfterMove = settings.deleteAfterMove
    val hideSettingsScreenshots = settings.hideSettingsScreenshots

    val tiktokSession = settings.tiktokSession
    var tiktokSessionField by remember(tiktokSession) { mutableStateOf(tiktokSession) }
    var revealSession by remember { mutableStateOf(false) }

    val relayUrl = settings.relayUrl
    var relayField by remember(relayUrl) { mutableStateOf(relayUrl) }

    val updateSource = settings.updateSource
    var updateSourceField by remember(updateSource) { mutableStateOf(updateSource) }
    val autoUpdateCheck = settings.autoUpdateCheck
    val updateState by container.updater.state.collectAsState()
    val openUpdateDialogReq by container.updater.openDialogRequested.collectAsState()

    var showQualityMenu by remember { mutableStateOf(false) }
    var showIntervalMenu by remember { mutableStateOf(false) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    var showRetentionDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var waitingInstallPermission by remember { mutableStateOf(false) }
    var infoPopup by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(openUpdateDialogReq) {
        if (openUpdateDialogReq) {
            showUpdateDialog = true
            container.updater.consumeOpenDialogRequest()
        }
    }

    var expandBatteryGuide by rememberSaveable { mutableStateOf(false) }
    var expandAdvanced by rememberSaveable { mutableStateOf(false) }

    val isXiaomi = remember { android.os.Build.MANUFACTURER.lowercase() in listOf("xiaomi", "redmi", "poco") }
    var batteryIgnored by remember {
        mutableStateOf(
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName),
        )
    }
    var hasAllFilesAccess by remember {
        mutableStateOf(
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
                android.os.Environment.isExternalStorageManager(),
        )
    }
    var canInstallApks by remember {
        mutableStateOf(container.updater.canRequestPackageInstalls(context))
    }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryIgnored = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .isIgnoringBatteryOptimizations(context.packageName)
                hasAllFilesAccess = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
                    android.os.Environment.isExternalStorageManager()
                canInstallApks = container.updater.canRequestPackageInstalls(context)
                if (waitingInstallPermission && canInstallApks) {
                    waitingInstallPermission = false
                    val cur = container.updater.state.value
                    if (cur is AppUpdater.UpdateState.ReadyToInstall) {
                        container.updater.launchInstaller(context, cur.apkFile)
                    }
                }
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(obs)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(obs) }
    }

    // Aktifkan FLAG_SECURE saat bagian Lanjutan/Session ID dibuka atau pengguna mengaktifkan perlindungan screenshot
    val window = (context as? Activity)?.window
    DisposableEffect(isActiveTab, expandAdvanced, revealSession, hideSettingsScreenshots) {
        val shouldSecure = isActiveTab && hideSettingsScreenshots && (expandAdvanced || revealSession)
        if (shouldSecure) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    val libraryEntries by container.libraryEntries.collectAsState()
    val freeSpaceBytes by container.freeBytes.collectAsState()
    val totalSpaceBytes by container.totalBytes.collectAsState()
    val totalMemStats = remember(libraryEntries, freeSpaceBytes) {
        libraryEntries?.let { list ->
            val liveDur = list.filter { it.kind == "LIVE" }.sumOf { it.durationMs }
            Triple(list.size, liveDur, freeSpaceBytes)
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Setelan", "Cara kerja RaKeepsake", null)

        // ---- 1. KARTU MEREK & KENANGAN (dinaikkan ke atas sesuai Audit §4.7 #3) ----
        Surface(
            onClick = { showAboutDialog = true },
            color = Ra.colors.surfaceAlt,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Ra.colors.sun.copy(alpha = 0.3f)),
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.TopCenter) {
                    Image(
                        painter = painterResource(R.drawable.app_logo),
                        contentDescription = "Logo RaKeepsake",
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)),
                    )
                    Box(
                        Modifier
                            .width(24.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Ra.colors.ribbon),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("RaKeepsake v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium, color = Ra.colors.text)
                        val pendingInfo = when (val st = updateState) {
                            is AppUpdater.UpdateState.Available -> st.info
                            is AppUpdater.UpdateState.ReadyToInstall -> st.info
                            else -> null
                        }
                        if (pendingInfo != null) {
                            Surface(color = Ra.colors.sunSoft, shape = RoundedCornerShape(8.dp)) {
                                Text(
                                    "Update v${pendingInfo.versionName}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = Ra.colors.sun,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    Text("Penjaga momen live & postingan kreator", style = MaterialTheme.typography.bodySmall, color = Ra.colors.sun)
                    val statsText = totalMemStats?.let { (count, liveDur, _) ->
                        "$count kenangan tersimpan" + if (liveDur > 0) " · ${Humanizer.formatLongHoursMinutes(liveDur)} rekaman live" else ""
                    } ?: "Ketuk untuk lihat ringkasan & statistikmu"
                    Text(statsText, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Ra.colors.textMuted)
                }
                Icon(Icons.Outlined.Info, "Tentang RaKeepsake", tint = Ra.colors.textMuted, modifier = Modifier.size(20.dp))
            }
        }

        // ---- 1b. KARTU PEMBARUAN MENONJOL JIKA VERSI BARU TERSEDIA ----
        val bannerUpdateInfo = when (val st = updateState) {
            is AppUpdater.UpdateState.Available -> st.info
            is AppUpdater.UpdateState.ReadyToInstall -> st.info
            is AppUpdater.UpdateState.Downloading -> st.info
            else -> null
        }
        if (bannerUpdateInfo != null) {
            Surface(
                onClick = { showUpdateDialog = true },
                color = Ra.colors.sunDim,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Ra.colors.sun.copy(alpha = 0.6f)),
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Filled.SystemUpdate, null, tint = Ra.colors.sun, modifier = Modifier.size(26.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = when (updateState) {
                                is AppUpdater.UpdateState.ReadyToInstall -> "Pembaruan v${bannerUpdateInfo.versionName} siap dipasang"
                                is AppUpdater.UpdateState.Downloading -> "Mengunduh pembaruan v${bannerUpdateInfo.versionName}..."
                                else -> "Pembaruan v${bannerUpdateInfo.versionName} tersedia"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = Ra.colors.sun,
                        )
                        val sub = when (val st = updateState) {
                            is AppUpdater.UpdateState.Downloading -> {
                                if (st.totalBytes > 0L) {
                                    val pct = ((st.downloadedBytes * 100L) / st.totalBytes).coerceIn(0L, 100L)
                                    "${formatBytes(st.downloadedBytes)} / ${formatBytes(st.totalBytes)} ($pct%)"
                                } else "${formatBytes(st.downloadedBytes)} diunduh..."
                            }
                            is AppUpdater.UpdateState.ReadyToInstall -> "Ketuk Pasang untuk memperbarui aplikasi sekarang"
                            else -> "Ketuk untuk melihat catatan rilis & memperbarui"
                        }
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = Ra.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Surface(
                        color = Ra.colors.sun,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = when (updateState) {
                                is AppUpdater.UpdateState.ReadyToInstall -> "Pasang"
                                is AppUpdater.UpdateState.Downloading -> "Lihat"
                                else -> "Update"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = Ra.colors.onSun,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        }

        // ---- 2. RINGKASAN PENYIMPANAN ----
        Surface(
            color = Ra.colors.surface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val total = totalSpaceBytes.takeIf { it > 0L } ?: (64L * 1024L * 1024L * 1024L)
                val free = freeSpaceBytes.coerceIn(0L, total)
                val used = (total - free).coerceAtLeast(0L)
                val usedPct = ((used * 100L) / total).coerceIn(0L, 100L).toInt()
                val archiveBytes = remember(libraryEntries) { libraryEntries?.sumOf { it.size } ?: 0L }
                val isLow = free < (minFreeMb * 1024L * 1024L)

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Penyimpanan Arsip", style = MaterialTheme.typography.titleMedium, color = Ra.colors.text)
                    Surface(
                        color = Ra.colors.sunDim,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            "${formatBytes(archiveBytes)} arsip",
                            style = MaterialTheme.typography.labelMedium,
                            color = Ra.colors.sun,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }

                LinearProgressIndicator(
                    progress = { (used.toFloat() / total.toFloat()).coerceIn(0.02f, 1f) },
                    color = if (isLow || usedPct >= 92) Ra.colors.danger else Ra.colors.sun,
                    trackColor = Ra.colors.surfaceHigh,
                    modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(99.dp)),
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${formatBytes(used)} terpakai dari ${formatBytes(total)} ($usedPct%)",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = Ra.colors.textSecondary,
                    )
                    Text(
                        "${formatBytes(free)} bebas",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isLow) Ra.colors.danger else Ra.colors.sun,
                    )
                }

                Text(
                    "Batas aman: berhenti rekam otomatis bila sisa < ${minFreeMb} MB",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = Ra.colors.textMuted,
                )
            }
        }

        // ---- 2b. PEMBARUAN APLIKASI ----
        SectionLabel("PEMBARUAN APLIKASI")
        SettingGroup {
            val updateSubtitle = when (val st = updateState) {
                is AppUpdater.UpdateState.Checking -> "Memeriksa rilis terbaru di GitHub…"
                is AppUpdater.UpdateState.Available -> "Versi baru v${st.info.versionName} tersedia · ketuk untuk unduh"
                is AppUpdater.UpdateState.Downloading -> {
                    if (st.totalBytes > 0L) {
                        val pct = ((st.downloadedBytes * 100L) / st.totalBytes).coerceIn(0L, 100L)
                        "Mengunduh v${st.info.versionName} ($pct% · ${formatBytes(st.downloadedBytes)})"
                    } else "Mengunduh v${st.info.versionName} (${formatBytes(st.downloadedBytes)})"
                }
                is AppUpdater.UpdateState.ReadyToInstall -> "v${st.info.versionName} siap dipasang — ketuk untuk instal"
                is AppUpdater.UpdateState.UpToDate -> st.note ?: "Sudah menggunakan versi terbaru (v${BuildConfig.VERSION_NAME})"
                is AppUpdater.UpdateState.Error -> st.message
                AppUpdater.UpdateState.Idle -> "Versi terpasang v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
            }
            val updateActionLabel = when (val st = updateState) {
                is AppUpdater.UpdateState.Checking -> "Memeriksa…"
                is AppUpdater.UpdateState.Available -> "Update"
                is AppUpdater.UpdateState.Downloading -> {
                    if (st.totalBytes > 0L) "${((st.downloadedBytes * 100L) / st.totalBytes).coerceIn(0L, 100L)}%" else "Unduh…"
                }
                is AppUpdater.UpdateState.ReadyToInstall -> "Pasang"
                else -> "Cek"
            }
            val updateActionColor = when (updateState) {
                is AppUpdater.UpdateState.ReadyToInstall -> Ra.colors.postFg
                else -> Ra.colors.sun
            }
            SettingRow(
                title = "Cek Pembaruan",
                subtitle = updateSubtitle,
                value = updateActionLabel,
                valueColor = updateActionColor,
                onClick = {
                    val cur = updateState
                    if (cur !is AppUpdater.UpdateState.Available &&
                        cur !is AppUpdater.UpdateState.Downloading &&
                        cur !is AppUpdater.UpdateState.ReadyToInstall &&
                        cur !is AppUpdater.UpdateState.Checking
                    ) {
                        container.updater.checkNowAsync(manual = true)
                    }
                    showUpdateDialog = true
                },
            )
            SettingRow(
                title = "Cek pembaruan otomatis",
                subtitle = "Periksa rilis baru secara ringan saat aplikasi dibuka",
                toggle = true,
                toggleValue = autoUpdateCheck,
                onToggle = { on -> scope.launch { container.settings.setAutoUpdateCheck(on) } },
            )
        }

        // ---- 3. PEREKAMAN & PEMANTAUAN ----
        SectionLabel("PEREKAMAN & PEMANTAUAN")
        SettingGroup {
            SettingRow(
                title = "Monitor aktif",
                subtitle = "Cek status siaran kreator di latar belakang",
                toggle = true,
                toggleValue = monitorWanted,
                onToggle = { on ->
                    scope.launch {
                        container.settings.setMonitorWanted(on)
                        if (on) LiveMonitorService.start(context) else LiveMonitorService.stop(context)
                    }
                },
            )
            SettingRow(
                title = "Rekam otomatis",
                subtitle = "Mulai sendiri begitu kreator memulai live",
                toggle = true,
                toggleValue = autoRecord,
                onToggle = { on -> scope.launch { container.settings.setAutoRecord(on) } },
            )
            SettingRow(
                title = "Jadwal rekam",
                subtitle = if (scheduleEnabled) "${ScheduleWindow.formatMinutes(scheduleStart)} - ${ScheduleWindow.formatMinutes(scheduleEnd)}" else "Sepanjang hari (24 jam)",
                toggle = true,
                toggleValue = scheduleEnabled,
                onToggle = { on -> scope.launch { container.settings.setScheduleEnabled(on) } },
                onClick = { showScheduleDialog = true },
            )
            SettingRow(
                title = "Interval cek",
                value = "$pollSeconds detik",
                onClick = { showIntervalMenu = true },
            )
            if (showIntervalMenu) {
                DropdownMenuRa(expanded = true, onDismiss = { showIntervalMenu = false }) {
                    listOf(30, 60, 120, 300).forEach { s ->
                        DropdownItemRa("$s detik") {
                            scope.launch { container.settings.setPollSeconds(s) }
                            showIntervalMenu = false
                        }
                    }
                }
            }
            SettingRow(
                title = "Kualitas rekaman",
                value = qualityLabel(quality),
                onClick = { showQualityMenu = true },
            )
            if (showQualityMenu) {
                DropdownMenuRa(expanded = true, onDismiss = { showQualityMenu = false }) {
                    listOf("ASLI", "1080", "720", "480").forEach { q ->
                        DropdownItemRa(qualityLabel(q)) {
                            scope.launch { container.settings.setQuality(q) }
                            showQualityMenu = false
                        }
                    }
                }
            }
            SettingRow(
                title = "Mode hemat HP low-end",
                subtitle = "Batasi perekaman paralel menjadi 1 stream",
                toggle = true,
                toggleValue = maxParallel <= 1,
                onToggle = { on -> scope.launch { container.settings.setMaxParallel(if (on) 1 else 3) } },
            )
        }

        // ---- 4. UNDUHAN & PENYIMPANAN ----
        SectionLabel("UNDUHAN & PENYIMPANAN")
        SettingGroup {
            SettingRow(
                title = "Hanya lewat Wi-Fi",
                subtitle = "Tunda unduhan postingan saat memakai data seluler",
                toggle = true,
                toggleValue = wifiOnly,
                onToggle = { on -> scope.launch { container.settings.setWifiOnly(on) } },
            )
            SettingRow(
                title = "Download Audio juga",
                subtitle = "Ikut mengunduh berkas audio (.m4a) di setiap postingan & story",
                toggle = true,
                toggleValue = audioOnly,
                onToggle = { on -> scope.launch { container.settings.setAudioOnly(on) } },
            )
            SettingRow(
                title = "Retensi otomatis",
                subtitle = if (storageAutoDelete) "Maksimal ${storageMaxGb} GB atau > ${storageMaxDays} hari" else "Nonaktif (simpan semua)",
                toggle = true,
                toggleValue = storageAutoDelete,
                onToggle = { on -> scope.launch { container.settings.setStorageAutoDelete(on) } },
                onClick = { showRetentionDialog = true },
            )
            SettingRow(
                title = "Hapus setelah dipindah",
                subtitle = "Hapus berkas asli saat dipindah ke kartu SD / folder lain",
                toggle = true,
                toggleValue = deleteAfterMove,
                onToggle = { on -> scope.launch { container.settings.setDeleteAfterMove(on) } },
            )
        }

        // ---- 4b. CLOUD (GOOGLE DRIVE) ----
        CloudSettingsSection(container = container)

        // ---- 5. NOTIFIKASI & IZIN + KETAHANAN LATAR BELAKANG ----
        SectionLabel("NOTIFIKASI & KETAHANAN")
        SettingGroup {
            SettingRow(
                title = "Izin notifikasi",
                subtitle = "Beritahu saat live mulai & unduhan selesai",
                onClick = { onRequestNotif() },
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                SettingRow(
                    title = "Akses Semua Berkas (Storage)",
                    subtitle = if (hasAllFilesAccess) "Diizinkan (hapus & pindah langsung tanpa pop-up sistem)" else "Belum aktif (ketuk untuk beri izin penuh)",
                    value = if (hasAllFilesAccess) "✓ Aktif" else "Perlu Izin",
                    valueColor = if (hasAllFilesAccess) Ra.colors.postFg else Ra.colors.sun,
                    onClick = {
                        if (!hasAllFilesAccess) {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                        .setData(Uri.parse("package:${context.packageName}")),
                                )
                            }.onFailure {
                                runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                            }
                        }
                    },
                )
            }
            SettingRow(
                title = "Optimasi Baterai",
                subtitle = "Cegah sistem mematikan pemantau di latar belakang",
                value = if (batteryIgnored) "✓ Tidak dibatasi" else "Dibatasi",
                valueColor = if (batteryIgnored) Ra.colors.postFg else Ra.colors.sun,
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:${context.packageName}")),
                        )
                    }
                },
            )

            if (isXiaomi) {
                HorizontalDivider(color = Ra.colors.outline.copy(alpha = 0.4f))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { expandBatteryGuide = !expandBatteryGuide }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Panduan MIUI (Xiaomi / Redmi)", style = MaterialTheme.typography.titleMedium, color = Ra.colors.text)
                            if (batteryIgnored) {
                                Icon(Icons.Filled.CheckCircle, "Aman", tint = Ra.colors.postFg, modifier = Modifier.size(16.dp))
                            }
                        }
                        Text(
                            if (expandBatteryGuide) "Ketuk untuk tutup panduan" else "Langkah agar pemantau tidak dimatikan MIUI",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ra.colors.textMuted,
                        )
                    }
                    Icon(
                        imageVector = if (expandBatteryGuide) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = Ra.colors.textSecondary,
                    )
                }
                if (expandBatteryGuide) {
                    Column(
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "1. Aktifkan Autostart untuk RaKeepsake.\n" +
                                "2. Setelan > Aplikasi > RaKeepsake > Penghemat baterai: pilih Tanpa pembatasan.\n" +
                                "3. Di layar Recent Apps, tahan kartu RaKeepsake lalu aktifkan ikon gembok.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ra.colors.textMuted,
                        )
                        OutlinedButton(
                            onClick = {
                                val autostart = Intent().setClassName(
                                    "com.miui.securitycenter",
                                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                                )
                                val ok = runCatching { context.startActivity(autostart) }.isSuccess
                                if (!ok) {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                                .setData(Uri.parse("package:${context.packageName}")),
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("Buka pengaturan Autostart", color = Ra.colors.sun) }
                    }
                }
            }
        }

        // ---- 6. LANJUTAN (Dilipat default dengan perlindungan Session ID - Audit §3.5 S1, §4.7 #1 & #2) ----
        SectionLabel("LANJUTAN")
        SettingGroup {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expandAdvanced = !expandAdvanced }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Pengaturan Mahir & Keamanan", style = MaterialTheme.typography.titleMedium, color = Ra.colors.text)
                    Text(
                        if (expandAdvanced) "Session ID tersembunyi · FLAG_SECURE aktif" else "Untuk pengguna mahir (Session ID, Relay cadangan)",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ra.colors.textMuted,
                    )
                }
                Icon(
                    imageVector = if (expandAdvanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expandAdvanced) "Tutup bagian lanjutan" else "Buka bagian lanjutan",
                    tint = Ra.colors.textSecondary,
                )
            }

            if (expandAdvanced) {
                HorizontalDivider(color = Ra.colors.outline.copy(alpha = 0.4f))
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // SecretField dengan masking default (`••••••••ccc`) + Eye & Copy
                    SecretField(
                        value = tiktokSessionField,
                        onValueChange = { tiktokSessionField = it },
                        label = "TikTok Session ID",
                        placeholder = "Isi nilai cookie sessionid...",
                        revealed = revealSession,
                        onToggleReveal = { revealSession = !revealSession },
                        onInfoClick = {
                            infoPopup = "Apa itu Session ID?" to
                                "Cookie sesi akun TikTok (`sessionid`) dipakai agar aplikasi bisa mengunduh Story dan mengakses live yang dibatasi wilayah/usia. Jaga kerahasiaannya seperti kata sandi dan jangan dibagikan dalam tangkapan layar."
                        },
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch { container.settings.setTiktokSession(tiktokSessionField) }
                            Toast.makeText(context, "Session ID disimpan dengan aman", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Simpan Session ID", color = Ra.colors.sun)
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Blokir screenshot di menu ini", style = MaterialTheme.typography.bodyMedium, color = Ra.colors.textSecondary)
                            Text("Mencegah Session ID terekam oleh aplikasi rekam layar", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Ra.colors.textMuted)
                        }
                        RaToggle(checked = hideSettingsScreenshots, labelDesc = "Blokir screenshot") { on ->
                            scope.launch { container.settings.setHideSettingsScreenshots(on) }
                        }
                    }

                    HorizontalDivider(color = Ra.colors.outline.copy(alpha = 0.35f))

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Relay cadangan (opsional)", style = MaterialTheme.typography.titleMedium, color = Ra.colors.text)
                        IconButton(
                            onClick = {
                                infoPopup = "Apa itu Relay Cadangan?" to
                                    "Server proxy Cloudflare Worker pribadi (opsional) yang dapat dipakai jika jaringan/ISP membatasi akses langsung ke API publik TikTok. Kosongkan jika koneksi normal lancar."
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Outlined.Info, "Penjelasan Relay", tint = Ra.colors.sun, modifier = Modifier.size(20.dp))
                        }
                    }
                    OutlinedTextField(
                        value = relayField,
                        onValueChange = { relayField = it },
                        placeholder = { Text("https://relaymu.workers.dev", color = Ra.colors.textMuted) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Ra.colors.text,
                            unfocusedTextColor = Ra.colors.text,
                            focusedBorderColor = Ra.colors.sun,
                            unfocusedBorderColor = Ra.colors.outline,
                            focusedContainerColor = Ra.colors.surfaceAlt,
                            unfocusedContainerColor = Ra.colors.surfaceAlt,
                            cursorColor = Ra.colors.sun,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch { container.settings.setRelayUrl(relayField) }
                            Toast.makeText(context, "Relay cadangan disimpan", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Simpan Relay", color = Ra.colors.sun)
                    }

                    HorizontalDivider(color = Ra.colors.outline.copy(alpha = 0.35f))

                    Text("Sumber pembaruan (GitHub / URL JSON)", style = MaterialTheme.typography.titleMedium, color = Ra.colors.text)
                    OutlinedTextField(
                        value = updateSourceField,
                        onValueChange = { updateSourceField = it },
                        placeholder = { Text(AppUpdater.DEFAULT_SOURCE, color = Ra.colors.textMuted) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Ra.colors.text,
                            unfocusedTextColor = Ra.colors.text,
                            focusedBorderColor = Ra.colors.sun,
                            unfocusedBorderColor = Ra.colors.outline,
                            focusedContainerColor = Ra.colors.surfaceAlt,
                            unfocusedContainerColor = Ra.colors.surfaceAlt,
                            cursorColor = Ra.colors.sun,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val normalized = updateSourceField.trim().ifBlank { AppUpdater.DEFAULT_SOURCE }
                                updateSourceField = normalized
                                scope.launch { container.settings.setUpdateSource(normalized) }
                                Toast.makeText(context, "Sumber pembaruan disimpan", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Simpan Sumber", color = Ra.colors.sun)
                        }
                        if (updateSourceField.trim() != AppUpdater.DEFAULT_SOURCE) {
                            TextButton(
                                onClick = {
                                    updateSourceField = AppUpdater.DEFAULT_SOURCE
                                    scope.launch { container.settings.setUpdateSource(AppUpdater.DEFAULT_SOURCE) }
                                    Toast.makeText(context, "Sumber pembaruan dikembalikan ke default", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text("Reset Default", color = Ra.colors.textSecondary)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }

    // Dialog Pembaruan Aplikasi
    if (showUpdateDialog) {
        AppUpdateDialog(
            state = updateState,
            canInstallApks = canInstallApks,
            onCheckNow = { container.updater.checkNowAsync(manual = true) },
            onStartDownload = { info -> container.updater.startDownload(info) },
            onCancelDownload = { container.updater.cancelDownload() },
            onRequestInstallPermission = {
                waitingInstallPermission = true
                container.updater.openInstallPermissionSettings(context)
            },
            onInstallApk = { apkFile ->
                if (!container.updater.canRequestPackageInstalls(context)) {
                    canInstallApks = false
                    waitingInstallPermission = true
                    container.updater.openInstallPermissionSettings(context)
                } else {
                    val launched = container.updater.launchInstaller(context, apkFile)
                    if (!launched) {
                        Toast.makeText(context, "Gagal membuka pemasang paket Android.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { showUpdateDialog = false },
        )
    }

    // Dialog Tentang & Statistik Personal (Audit §4.7 #3 & §6 #5)
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            containerColor = Ra.colors.surfaceAlt,
            shape = RoundedCornerShape(24.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.app_logo),
                        contentDescription = null,
                        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("RaKeepsake v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge, color = Ra.colors.text)
                        Text("Arsip Live & Postingan Kreator", style = MaterialTheme.typography.bodySmall, color = Ra.colors.sun)
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "\"Ra\" diambil dari nama Dewa Matahari Mesir Kuno yang melambangkan aksen emas hangat dan energi cerah. \"Keepsake\" berarti benda kenang-kenangan yang sentimental, manis, dan berharga.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ra.colors.textSecondary,
                    )
                    Text(
                        "Dibuat agar kamu tak pernah lagi melewatkan momen siaran langsung, story, maupun postingan kreator favorit di SHOWROOM, IDN Live, dan TikTok.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ra.colors.textMuted,
                    )
                    totalMemStats?.let { (count, liveDur, free) ->
                        Surface(color = Ra.colors.surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Statistik Kenanganmu", style = MaterialTheme.typography.labelLarge, color = Ra.colors.sun)
                                Text("• $count item tersimpan di Pustaka lokal", style = MaterialTheme.typography.bodySmall, color = Ra.colors.text)
                                Text("• Total durasi live terekam: ${Humanizer.formatLongHoursMinutes(liveDur)}", style = MaterialTheme.typography.bodySmall, color = Ra.colors.text)
                                Text("• Ruang bebas tersisa: ${formatBytes(free)}", style = MaterialTheme.typography.bodySmall, color = Ra.colors.textMuted)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("Tutup", color = Ra.colors.sun) }
            },
        )
    }

    // Bottom sheet / Dialog penjelasan istilah teknis (`ⓘ`)
    infoPopup?.let { (title, desc) ->
        AlertDialog(
            onDismissRequest = { infoPopup = null },
            containerColor = Ra.colors.surfaceAlt,
            shape = RoundedCornerShape(22.dp),
            title = { Text(title, style = MaterialTheme.typography.titleMedium, color = Ra.colors.sun) },
            text = { Text(desc, style = MaterialTheme.typography.bodyMedium, color = Ra.colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = { infoPopup = null }) { Text("Mengerti", color = Ra.colors.sun) }
            },
        )
    }

    if (showScheduleDialog) {
        ScheduleDialog(
            currentStart = scheduleStart,
            currentEnd = scheduleEnd,
            onSave = { s, e ->
                scope.launch {
                    container.settings.setScheduleStart(s)
                    container.settings.setScheduleEnd(e)
                    container.settings.setScheduleEnabled(true)
                }
                showScheduleDialog = false
            },
            onDismiss = { showScheduleDialog = false },
        )
    }

    if (showRetentionDialog) {
        RetentionDialog(
            currentMaxGb = storageMaxGb,
            currentMaxDays = storageMaxDays,
            onSave = { gb, days ->
                scope.launch {
                    container.settings.setStorageMaxGb(gb)
                    container.settings.setStorageMaxDays(days)
                    container.settings.setStorageAutoDelete(true)
                }
                showRetentionDialog = false
            },
            onDismiss = { showRetentionDialog = false },
        )
    }
}

@Composable
private fun ScheduleDialog(
    currentStart: Int,
    currentEnd: Int,
    onSave: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val presets = listOf(
        Triple("Malam (18:00 - 24:00)", 1080, 1440),
        Triple("Larut Malam (20:00 - 02:00)", 1200, 120),
        Triple("Sore - Malam (16:00 - 23:00)", 960, 1380),
        Triple("Siang - Malam (12:00 - 24:00)", 720, 1440),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ra.colors.surfaceAlt,
        title = { Text("Jadwal Rekam Otomatis", style = MaterialTheme.typography.titleMedium, color = Ra.colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Pilih rentang jam siaran yang ingin direkam otomatis. Di luar jam ini, live tetap terdeteksi tetapi tidak direkam otomatis (kecuali manual).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ra.colors.textMuted,
                )
                presets.forEach { (label, start, end) ->
                    val isSelected = currentStart == start && currentEnd == end
                    OutlinedButton(
                        onClick = { onSave(start, end) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(label, color = if (isSelected) Ra.colors.sun else Ra.colors.text)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Tutup", color = Ra.colors.textMuted) }
        },
    )
}

@Composable
private fun RetentionDialog(
    currentMaxGb: Int,
    currentMaxDays: Int,
    onSave: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var maxGb by remember { mutableStateOf(currentMaxGb) }
    var maxDays by remember { mutableStateOf(currentMaxDays) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ra.colors.surfaceAlt,
        title = { Text("Batas Retensi Penyimpanan", style = MaterialTheme.typography.titleMedium, color = Ra.colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Rekaman paling lama akan otomatis dihapus jika melebihi kuota GB atau umur hari.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ra.colors.textMuted,
                )
                Text("Batas Kuota Penyimpanan (GB):", style = MaterialTheme.typography.labelMedium, color = Ra.colors.sun)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 10, 20, 50, 100).forEach { gb ->
                        RaChip("${gb}GB", active = maxGb == gb) { maxGb = gb }
                    }
                }
                Text("Maksimal Umur Berkas (Hari):", style = MaterialTheme.typography.labelMedium, color = Ra.colors.sun)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(3, 7, 14, 30).forEach { d ->
                        RaChip("$d hari", active = maxDays == d) { maxDays = d }
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(
                onClick = { onSave(maxGb, maxDays) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Simpan", color = Ra.colors.sun) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal", color = Ra.colors.textMuted) }
        },
    )
}

private fun qualityLabel(q: String) = when (q) {
    "ASLI" -> "Sumber asli"
    "1080" -> "1080p"
    "720" -> "720p"
    "480" -> "480p"
    else -> q
}

@Composable
internal fun SettingGroup(content: @Composable () -> Unit) {
    Surface(
        color = Ra.colors.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
    ) { Column { content() } }
}

@Composable
private fun DropdownMenuRa(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) { content() }
}

@Composable
private fun DropdownItemRa(label: String, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}

@Composable
private fun AppUpdateDialog(
    state: AppUpdater.UpdateState,
    canInstallApks: Boolean,
    onCheckNow: () -> Unit,
    onStartDownload: (AppUpdater.ReleaseInfo) -> Unit,
    onCancelDownload: () -> Unit,
    onRequestInstallPermission: () -> Unit,
    onInstallApk: (java.io.File) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ra.colors.surfaceAlt,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    imageVector = Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    tint = Ra.colors.sun,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = when (state) {
                        is AppUpdater.UpdateState.Available -> "Pembaruan Tersedia"
                        is AppUpdater.UpdateState.Downloading -> "Mengunduh Pembaruan"
                        is AppUpdater.UpdateState.ReadyToInstall -> "Siap Dipasang"
                        is AppUpdater.UpdateState.UpToDate -> "Versi Aplikasi"
                        is AppUpdater.UpdateState.Error -> "Gagal Memeriksa Update"
                        else -> "Cek Pembaruan"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = Ra.colors.text,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    is AppUpdater.UpdateState.Checking -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Ra.colors.sun, strokeWidth = 2.5.dp)
                            Text("Memeriksa rilis terbaru di GitHub…", style = MaterialTheme.typography.bodyMedium, color = Ra.colors.textSecondary)
                        }
                    }
                    is AppUpdater.UpdateState.UpToDate -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Filled.CheckCircle, null, tint = Ra.colors.postFg, modifier = Modifier.size(22.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "RaKeepsake v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Ra.colors.text,
                                )
                                Text(
                                    state.note ?: "Kamu sudah memakai versi terbaru.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Ra.colors.textSecondary,
                                )
                            }
                        }
                    }
                    is AppUpdater.UpdateState.Available -> {
                        UpdateInfoDetails(state.info)
                    }
                    is AppUpdater.UpdateState.Downloading -> {
                        Text(
                            "Mengunduh v${state.info.versionName} (${state.info.apkFileName})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ra.colors.text,
                        )
                        val hasTotal = state.totalBytes > 0L
                        if (hasTotal) {
                            val frac = (state.downloadedBytes.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = { frac },
                                color = Ra.colors.sun,
                                trackColor = Ra.colors.surfaceHigh,
                                modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(99.dp)),
                            )
                            val pct = (frac * 100f).toInt().coerceIn(0, 100)
                            Text(
                                "${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)} ($pct%)",
                                style = MaterialTheme.typography.labelMedium,
                                color = Ra.colors.sun,
                            )
                        } else {
                            LinearProgressIndicator(
                                color = Ra.colors.sun,
                                trackColor = Ra.colors.surfaceHigh,
                                modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(99.dp)),
                            )
                            Text(
                                "${formatBytes(state.downloadedBytes)} diunduh…",
                                style = MaterialTheme.typography.labelMedium,
                                color = Ra.colors.sun,
                            )
                        }
                    }
                    is AppUpdater.UpdateState.ReadyToInstall -> {
                        UpdateInfoDetails(state.info)
                        if (!canInstallApks) {
                            Surface(
                                color = Ra.colors.sunDim,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Ra.colors.sun.copy(alpha = 0.5f)),
                            ) {
                                Text(
                                    "Aktifkan izin \"Izinkan dari sumber ini\" agar RaKeepsake dapat memasang APK pembaruan secara langsung.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Ra.colors.text,
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.CheckCircle, null, tint = Ra.colors.postFg, modifier = Modifier.size(18.dp))
                                Text("APK terverifikasi & siap dipasang.", style = MaterialTheme.typography.bodySmall, color = Ra.colors.postFg)
                            }
                        }
                    }
                    is AppUpdater.UpdateState.Error -> {
                        Text(state.message, style = MaterialTheme.typography.bodyMedium, color = Ra.colors.danger)
                    }
                    AppUpdater.UpdateState.Idle -> {
                        Text(
                            "Versi terpasang: v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ra.colors.textSecondary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                is AppUpdater.UpdateState.Available -> {
                    Button(
                        onClick = { onStartDownload(state.info) },
                        colors = ButtonDefaults.buttonColors(containerColor = Ra.colors.sun, contentColor = Ra.colors.onSun),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Unduh & Pasang")
                    }
                }
                is AppUpdater.UpdateState.ReadyToInstall -> {
                    if (!canInstallApks) {
                        Button(
                            onClick = onRequestInstallPermission,
                            colors = ButtonDefaults.buttonColors(containerColor = Ra.colors.sun, contentColor = Ra.colors.onSun),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("Izinkan Pasang APK") }
                    } else {
                        Button(
                            onClick = { onInstallApk(state.apkFile) },
                            colors = ButtonDefaults.buttonColors(containerColor = Ra.colors.sun, contentColor = Ra.colors.onSun),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("Pasang Sekarang") }
                    }
                }
                is AppUpdater.UpdateState.Error -> {
                    OutlinedButton(
                        onClick = {
                            if (state.retryInfo != null) onStartDownload(state.retryInfo) else onCheckNow()
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.Refresh, null, tint = Ra.colors.sun, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Coba Lagi", color = Ra.colors.sun)
                    }
                }
                is AppUpdater.UpdateState.UpToDate, AppUpdater.UpdateState.Idle -> {
                    OutlinedButton(
                        onClick = onCheckNow,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.Refresh, null, tint = Ra.colors.sun, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Cek Ulang", color = Ra.colors.sun)
                    }
                }
                is AppUpdater.UpdateState.Downloading, AppUpdater.UpdateState.Checking -> Unit
            }
        },
        dismissButton = {
            when (state) {
                is AppUpdater.UpdateState.Downloading -> {
                    TextButton(onClick = onCancelDownload, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Batalkan", color = Ra.colors.danger)
                    }
                }
                else -> {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Tutup", color = Ra.colors.textMuted)
                    }
                }
            }
        },
    )
}

@Composable
private fun UpdateInfoDetails(info: AppUpdater.ReleaseInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(color = Ra.colors.surface, shape = RoundedCornerShape(8.dp)) {
                Text(
                    "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Ra.colors.textMuted,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Text("→", style = MaterialTheme.typography.bodyMedium, color = Ra.colors.sun)
            Surface(color = Ra.colors.sunSoft, shape = RoundedCornerShape(8.dp)) {
                Text(
                    "v${info.versionName}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Ra.colors.sun,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            if (info.sizeBytes > 0L) {
                Text(
                    "· ${formatBytes(info.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ra.colors.textMuted,
                )
            }
        }
        Surface(
            color = Ra.colors.surface,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
        ) {
            Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                Text("Catatan Rilis:", style = MaterialTheme.typography.labelMedium, color = Ra.colors.sun)
                Spacer(Modifier.height(4.dp))
                Text(
                    info.changelog,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ra.colors.textSecondary,
                )
            }
        }
    }
}
