package id.rakeepsake.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.rakeepsake.data.Account
import id.rakeepsake.data.AppContainer
import id.rakeepsake.data.Platform
import id.rakeepsake.monitor.LiveMonitorService
import id.rakeepsake.monitor.LiveStateHolder
import id.rakeepsake.util.Humanizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tab Live ("Sedang Live & Pantau"):
 * - Seksi pahlawan "SEDANG LIVE" di atas dengan cincin emas berdenyut & timer realtime
 * - Satu baris filter platform dengan jumlah per kategori (TikTok, SHOWROOM, IDN)
 * - Status info (Auto rekam / Hanya notif) ditulis jelas di teks, tombol aksi kanan berbentuk pil "Rekam" (tidak mirip shutter kamera)
 */
@Composable
fun LiveScreen(
    container: AppContainer,
    modifier: Modifier,
    isActiveTab: Boolean = true,
    onNavigateToAccounts: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val accounts by container.accountsFlow.collectAsState()
    val settings by container.settingsFlow.collectAsState()
    val monitorWanted = settings.monitorWanted

    var platformFilter by rememberSaveable { mutableStateOf("Semua") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Refresh UI 1 dtk bila sedang merekam di tab aktif; saat idle cukup tiap 30 dtk agar frame idle 0% jank
    val tick by produceState(System.currentTimeMillis(), isActiveTab) {
        if (isActiveTab) {
            while (true) {
                delay(if (LiveStateHolder.recording.isNotEmpty()) 1000L else 30_000L)
                value = System.currentTimeMillis()
            }
        }
    }

    val recordingCount = LiveStateHolder.recording.size
    val liveCount = LiveStateHolder.liveNow.size

    var prevLiveSignature by remember { mutableIntStateOf(liveCount + recordingCount) }
    LaunchedEffect(liveCount, recordingCount) {
        val curTotal = liveCount + recordingCount
        if (curTotal > prevLiveSignature && prevLiveSignature >= 0) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        prevLiveSignature = curTotal
    }

    val tiktokCount = remember(accounts) { accounts.count { it.platform == Platform.TIKTOK.name } }
    val srCount = remember(accounts) { accounts.count { it.platform == Platform.SHOWROOM.name } }
    val idnCount = remember(accounts) { accounts.count { it.platform == Platform.IDN.name } }

    val filtered = remember(accounts, platformFilter, searchQuery) {
        accounts.filter { account ->
            val matchPlatform = when (platformFilter) {
                "TikTok" -> account.platform == Platform.TIKTOK.name
                "SHOWROOM" -> account.platform == Platform.SHOWROOM.name
                "IDN" -> account.platform == Platform.IDN.name
                else -> true
            }
            val matchQuery = searchQuery.isBlank() ||
                account.handle.contains(searchQuery, ignoreCase = true) ||
                account.displayName.contains(searchQuery, ignoreCase = true)
            matchPlatform && matchQuery
        }
    }

    val liveOrRecList = remember(filtered, tick, liveCount, recordingCount) {
        filtered.filter { LiveStateHolder.liveNow.containsKey(it.id) || LiveStateHolder.recording.containsKey(it.id) }
    }
    val firstLiveId = liveOrRecList.firstOrNull()?.id
    LaunchedEffect(firstLiveId) {
        if (firstLiveId != null && listState.firstVisibleItemIndex <= 3 && !listState.isScrollInProgress) {
            listState.scrollToItem(0)
        }
    }
    val offlineList = remember(filtered, liveOrRecList) {
        val activeIds = liveOrRecList.map { it.id }.toSet()
        filtered.filterNot { activeIds.contains(it.id) }
            .sortedWith(compareByDescending<Account> { it.isFavorite }.thenByDescending { it.lastLiveAt })
    }

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val activeHeaderCount = (LiveStateHolder.liveNow.keys + LiveStateHolder.recording.keys).toSet().size
        val subtitle = when {
            activeHeaderCount > 0 -> "$activeHeaderCount live sekarang · $recordingCount direkam"
            monitorWanted -> buildString {
                append("${accounts.size} dipantau")
                if (tiktokCount > 0) append(" · $tiktokCount TikTok")
                if (srCount > 0) append(" · $srCount SR")
                if (idnCount > 0) append(" · $idnCount IDN")
            }
            else -> "Pemantau nonaktif"
        }

        ScreenHeader(
            title = "Live",
            subtitle = subtitle,
            actionIcon = Icons.Filled.Refresh,
            actionDesc = "Segarkan status live",
        ) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    if (monitorWanted) LiveMonitorService.checkNow(context)
                    else {
                        container.settings.setMonitorWanted(true)
                        LiveMonitorService.start(context)
                    }
                }
            }
            Toast.makeText(context, "Memeriksa status siaran langsung…", Toast.LENGTH_SHORT).show()
        }

        if (!monitorWanted) {
            Surface(
                color = Ra.colors.surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Ra.colors.outline),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Pemantau mati. Nyalakan agar live otomatis terekam.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ra.colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                container.settings.setMonitorWanted(true)
                                LiveMonitorService.start(context)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Ra.colors.sun, contentColor = Ra.colors.onSun),
                        modifier = Modifier.heightIn(min = 42.dp),
                    ) { Text("Nyalakan") }
                }
            }
        }

        ScrollCollapsibleBody(
            canCollapse = { hdrPx -> filtered.isNotEmpty() && listState.hasRealOverflow(hdrPx) },
            resetKey = listOf(platformFilter, searchQuery, accounts.size, monitorWanted),
            onAutoResetScroll = {
                if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
                    listState.animateScrollToItem(0)
                }
            },
            modifier = Modifier.weight(1f),
            header = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Cari kreator atau @handle...", color = Ra.colors.textMuted) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = { Icon(Icons.Filled.Search, "Cari", tint = Ra.colors.textMuted) },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Filled.Close, "Hapus pencarian", tint = Ra.colors.textMuted)
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Ra.colors.text,
                            unfocusedTextColor = Ra.colors.text,
                            focusedBorderColor = Ra.colors.sun,
                            unfocusedBorderColor = Ra.colors.outline,
                            focusedContainerColor = Ra.colors.surfaceAlt,
                            unfocusedContainerColor = Ra.colors.surfaceAlt,
                            cursorColor = Ra.colors.sun,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RaChip("Semua (${accounts.size})", platformFilter == "Semua") { platformFilter = "Semua" }
                        RaChip("TikTok ($tiktokCount)", platformFilter == "TikTok") { platformFilter = "TikTok" }
                        RaChip("SHOWROOM ($srCount)", platformFilter == "SHOWROOM") { platformFilter = "SHOWROOM" }
                        RaChip("IDN ($idnCount)", platformFilter == "IDN") { platformFilter = "IDN" }
                    }
                    Spacer(Modifier.height(2.dp))
                }
            },
        ) { bottomPadding ->
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(bottom = bottomPadding), contentAlignment = Alignment.Center) {
                    if (accounts.isEmpty()) {
                        EmptyState(
                            message = "Belum ada akun yang dipantau.\nTambahkan kreator favoritmu untuk rekam live otomatis.",
                            actionLabel = if (onNavigateToAccounts != null) "Tambah Akun" else null,
                            onAction = onNavigateToAccounts,
                        )
                    } else {
                        EmptyState("Tidak ada akun yang cocok dengan pencarian atau filter ini.")
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding + 12.dp),
                ) {
                    if (liveOrRecList.isNotEmpty()) {
                        item(key = "hdr_live", contentType = "hdr") {
                            SectionLabel("SEDANG LIVE (${liveOrRecList.size})", Modifier.padding(horizontal = 0.dp, vertical = 2.dp))
                        }
                        items(liveOrRecList, key = { "live_${it.id}" }, contentType = { "hero" }) { account ->
                            LiveHeroCard(account, tick, settings.autoRecord) { container.syncAvatarsAsync(setOf(account.id)) }
                        }
                    }

                    if (offlineList.isNotEmpty()) {
                        item(key = "hdr_monitored", contentType = "hdr") {
                            SectionLabel(
                                if (liveOrRecList.isNotEmpty()) "DIPANTAU (${offlineList.size})" else "AKUN DIPANTAU (${offlineList.size})",
                                Modifier.padding(horizontal = 0.dp, vertical = 2.dp),
                            )
                        }
                        items(offlineList, key = { "off_${it.id}" }, contentType = { "offline" }) { account ->
                            OfflineRowCard(account) { container.syncAvatarsAsync(setOf(account.id)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveHeroCard(
    account: Account,
    tick: Long,
    globalAutoRecord: Boolean = true,
    onAvatarError: () -> Unit = {},
) {
    val context = LocalContext.current
    val rec = LiveStateHolder.recording[account.id]
    val live = LiveStateHolder.liveNow[account.id]
    var showStopConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Ra.colors.surfaceAlt),
        border = BorderStroke(1.5.dp, Ra.colors.sun.copy(alpha = 0.65f)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveRingAvatar(account = account, size = 56.dp, isLive = true, onAvatarError = onAvatarError)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            account.displayName.ifBlank { account.handle },
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                            color = Ra.colors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (account.isFavorite) {
                            Icon(Icons.Filled.Star, "Favorit", tint = Ra.colors.sun, modifier = Modifier.size(16.dp))
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "● LIVE",
                            color = Ra.colors.live,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .background(Ra.colors.liveSoft, RoundedCornerShape(6.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                        if (rec != null && rec.startedAt > 1_000_000_000_000L) {
                            Text(
                                Humanizer.formatElapsedDuration(tick - rec.startedAt),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = Ra.colors.sun,
                            )
                        }
                        if (rec != null && rec.sizeBytes > 0L) {
                            SizePill(formatBytes(rec.sizeBytes))
                        }
                    }

                    Text(
                        buildString {
                            append("${platformNice(account.platform)} · @${account.handle}")
                            if (!live?.title.isNullOrBlank()) append(" · ${live!!.title!!.take(35)}")
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = Ra.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val autoRecordOn = globalAutoRecord && account.recordLive && !account.notifyOnly
                if (rec != null) {
                    Button(
                        onClick = { showStopConfirm = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Ra.colors.liveSoft,
                            contentColor = Ra.colors.danger,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.Stop, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Berhenti rekam", style = MaterialTheme.typography.labelLarge)
                    }
                } else if (!autoRecordOn) {
                    Button(
                        onClick = {
                            Toast.makeText(context, "Memulai rekam @${account.handle}...", Toast.LENGTH_SHORT).show()
                            LiveMonitorService.recordNow(context, account.id)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Ra.colors.sun,
                            contentColor = Ra.colors.onSun,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.FiberManualRecord, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Rekam", style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    Surface(
                        color = Ra.colors.sunDim,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Ra.colors.sun.copy(alpha = 0.45f)),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text(
                                "Auto rekam aktif",
                                style = MaterialTheme.typography.labelLarge,
                                color = Ra.colors.sun,
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = { openLivePlatformUrl(context, account.platform, account.handle) },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Ra.colors.outline),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = Ra.colors.textSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Buka", color = Ra.colors.textSecondary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            containerColor = Ra.colors.surfaceAlt,
            shape = RoundedCornerShape(24.dp),
            title = { Text("Hentikan Rekaman Live?", style = MaterialTheme.typography.titleLarge, color = Ra.colors.text) },
            text = {
                Text(
                    "Rekaman live ${account.displayName.ifBlank { "@${account.handle}" }} akan dihentikan dan disimpan ke Pustaka.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ra.colors.textSecondary,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        LiveMonitorService.stopRecording(context, account.id)
                        showStopConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Ra.colors.danger, contentColor = Ra.colors.onSun),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Hentikan Rekam") }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) { Text("Batal", color = Ra.colors.textMuted) }
            },
        )
    }
}

/** Baris akun pantauan offline: menampilkan info status pemantauan tanpa tombol rekam. */
@Composable
private fun OfflineRowCard(account: Account, onAvatarError: () -> Unit = {}) {
    val lastLiveText = remember(account.lastLiveAt) {
        Humanizer.formatRelativeLastLive(account.lastLiveAt)
    }
    val statusText = remember(lastLiveText, account.recordLive, account.notifyOnly) {
        buildString {
            append(lastLiveText)
            when {
                account.recordLive && !account.notifyOnly -> append(" · Auto rekam")
                account.notifyOnly -> append(" · Notif saja")
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Ra.colors.surfaceAlt),
        border = BorderStroke(1.dp, Ra.colors.outline.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveRingAvatar(account = account, size = 48.dp, isLive = false, onAvatarError = onAvatarError)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        account.displayName.ifBlank { account.handle },
                        style = MaterialTheme.typography.titleMedium,
                        color = Ra.colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (account.isFavorite) {
                        Icon(Icons.Filled.Star, "Favorit", tint = Ra.colors.sun, modifier = Modifier.size(15.dp))
                    }
                }
                Text(
                    "${platformNice(account.platform)} · @${account.handle}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Ra.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = if (account.recordLive && !account.notifyOnly) Ra.colors.sun.copy(alpha = 0.85f) else Ra.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun platformNice(platform: String) = platformNiceName(platform)
