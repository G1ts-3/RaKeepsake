package id.rakeepsake.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PeopleAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.rakeepsake.data.Account
import id.rakeepsake.data.AppContainer
import id.rakeepsake.data.Platform
import id.rakeepsake.data.RosterTransfer
import id.rakeepsake.net.PlatformApi
import id.rakeepsake.util.Humanizer
import id.rakeepsake.work.PostWatchWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tab Akun ("Oshi Ringkas"):
 * - Kartu 1 baris ringkas (~76dp) dengan penanda teks pill ("Live Auto", "Post Auto", "Notif Saja") di bawah handle
 *   sehingga jelas merupakan label status (tidak dikira tombol refresh/download)
 * - Ketuk kartu membuka Halaman Detail Oshi
 */
@Composable
fun AccountsScreen(container: AppContainer, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accounts by container.accountsFlow.collectAsState()

    var showAdd by remember { mutableStateOf(false) }
    var showRosterTransfer by remember { mutableStateOf(false) }
    var showHeaderMenu by remember { mutableStateOf(false) }
    var detailAccount by remember { mutableStateOf<Account?>(null) }

    var platformFilter by rememberSaveable { mutableStateOf("Semua") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var groupByMember by rememberSaveable { mutableStateOf(false) }

    val bulkMode = remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    if (bulkMode.value) {
        BackHandler {
            bulkMode.value = false
            selectedIds.clear()
        }
    }

    val listState = rememberLazyListState()

    val favCount = remember(accounts) { accounts.count { it.isFavorite } }
    val tiktokCount = remember(accounts) { accounts.count { it.platform == Platform.TIKTOK.name } }
    val srCount = remember(accounts) { accounts.count { it.platform == Platform.SHOWROOM.name } }
    val idnCount = remember(accounts) { accounts.count { it.platform == Platform.IDN.name } }

    val filteredAccounts = remember(accounts, platformFilter, searchQuery) {
        accounts.filter { account ->
            val matchPlatform = when (platformFilter) {
                "Favorit" -> account.isFavorite
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

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (bulkMode.value) {
            Surface(
                color = Ra.colors.surfaceAlt,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Ra.colors.sun.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                bulkMode.value = false
                                selectedIds.clear()
                            },
                            modifier = Modifier.size(48.dp),
                        ) { Icon(Icons.Filled.Close, "Tutup mode pilih", tint = Ra.colors.text) }
                        Text(
                            "${selectedIds.size} dipilih",
                            style = MaterialTheme.typography.titleMedium,
                            color = Ra.colors.sun,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                if (selectedIds.size == filteredAccounts.size) selectedIds.clear()
                                else {
                                    selectedIds.clear()
                                    selectedIds.addAll(filteredAccounts.map { it.id })
                                }
                            },
                        ) {
                            Text(
                                if (selectedIds.size == filteredAccounts.size) "Batal Semua" else "Pilih Semua",
                                color = Ra.colors.sun,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            enabled = selectedIds.isNotEmpty(),
                            onClick = {
                                val ids = selectedIds.toList()
                                scope.launch(Dispatchers.IO) {
                                    container.db.accounts().setRecordLiveBulk(ids, true)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Auto-rekam diaktifkan untuk ${ids.size} akun", Toast.LENGTH_SHORT).show()
                                        bulkMode.value = false
                                        selectedIds.clear()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Ra.colors.sun, contentColor = Ra.colors.onSun),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        ) { Text("Aktifkan Auto-Rekam", style = MaterialTheme.typography.labelLarge) }

                        OutlinedButton(
                            enabled = selectedIds.isNotEmpty(),
                            onClick = {
                                val ids = selectedIds.toList()
                                scope.launch(Dispatchers.IO) {
                                    container.db.accounts().setRecordLiveBulk(ids, false)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Auto-rekam dimatikan untuk ${ids.size} akun", Toast.LENGTH_SHORT).show()
                                        bulkMode.value = false
                                        selectedIds.clear()
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.heightIn(min = 44.dp),
                        ) { Text("Matikan", color = Ra.colors.textSecondary) }
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxWidth()) {
                ScreenHeader(
                    title = "Akun",
                    subtitle = "${accounts.size} akun dipantau",
                    actionIcon = Icons.Filled.Add,
                    actionDesc = "Tambah akun",
                    onAction = { showAdd = true },
                    secondActionIcon = Icons.Filled.MoreVert,
                    secondActionDesc = "Opsi pengelolaan akun",
                    onSecondAction = { showHeaderMenu = true },
                )
                if (showHeaderMenu) {
                    Box(Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 64.dp)) {
                        DropdownMenu(
                            expanded = true,
                            onDismissRequest = { showHeaderMenu = false },
                            containerColor = Ra.colors.surfaceHigh,
                        ) {
                            DropdownMenuItem(
                                text = { Text("Impor / Ekspor Daftar Akun (JSON)", color = Ra.colors.text) },
                                leadingIcon = { Icon(Icons.Filled.PeopleAlt, null, tint = Ra.colors.sun) },
                                onClick = {
                                    showHeaderMenu = false
                                    showRosterTransfer = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Cek Semua Postingan & Story", color = Ra.colors.text) },
                                leadingIcon = { Icon(Icons.Filled.Refresh, null, tint = Ra.colors.textSecondary) },
                                onClick = {
                                    showHeaderMenu = false
                                    Toast.makeText(context, "Memeriksa story & postingan semua akun...", Toast.LENGTH_SHORT).show()
                                    scope.launch(Dispatchers.IO) {
                                        runCatching { PostWatchWorker.checkAccounts(context, container) }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Mode Pilih Massal (Auto-Rekam)", color = Ra.colors.text) },
                                leadingIcon = { Icon(Icons.Filled.CheckBox, null, tint = Ra.colors.textSecondary) },
                                onClick = {
                                    showHeaderMenu = false
                                    bulkMode.value = true
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (groupByMember) "Tampilkan Semua Kartu" else "Kelompokkan per Kreator",
                                        color = Ra.colors.text,
                                    )
                                },
                                leadingIcon = { Icon(Icons.Filled.PeopleAlt, null, tint = Ra.colors.textSecondary) },
                                onClick = {
                                    showHeaderMenu = false
                                    groupByMember = !groupByMember
                                },
                            )
                        }
                    }
                }
            }
        }

        ScrollCollapsibleBody(
            canCollapse = { hdrPx -> filteredAccounts.isNotEmpty() && listState.hasRealOverflow(hdrPx) },
            resetKey = listOf(platformFilter, searchQuery, bulkMode.value, groupByMember, filteredAccounts.size),
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
                        placeholder = { Text("Cari nama kreator atau @handle...", color = Ra.colors.textMuted) },
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
                        if (favCount > 0 || platformFilter == "Favorit") {
                            RaChip("Favorit ($favCount)", platformFilter == "Favorit") { platformFilter = "Favorit" }
                        }
                        RaChip("TikTok ($tiktokCount)", platformFilter == "TikTok") { platformFilter = "TikTok" }
                        RaChip("SHOWROOM ($srCount)", platformFilter == "SHOWROOM") { platformFilter = "SHOWROOM" }
                        RaChip("IDN ($idnCount)", platformFilter == "IDN") { platformFilter = "IDN" }
                    }
                    Spacer(Modifier.height(2.dp))
                }
            },
        ) { bottomPadding ->
            if (filteredAccounts.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(bottom = bottomPadding), contentAlignment = Alignment.Center) {
                    EmptyState(
                        message = if (searchQuery.isNotBlank() || platformFilter != "Semua") {
                            "Tidak ada akun yang cocok dengan pencarian."
                        } else {
                            "Belum ada akun dipantau.\nKetuk + untuk menambah kreator atau impor daftar JSON."
                        },
                        actionLabel = if (accounts.isEmpty()) "Tambah Akun" else null,
                        onAction = if (accounts.isEmpty()) { { showAdd = true } } else null,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding + 12.dp),
                ) {
                if (groupByMember) {
                    val grouped = buildOshiGroups(filteredAccounts)
                    val favGroups = grouped.filter { it.hasFavorite }
                    val otherGroups = grouped.filterNot { it.hasFavorite }

                    if (favGroups.isNotEmpty()) {
                        item(key = "sec_fav", contentType = "sec") {
                            SectionLabel(
                                "FAVORIT (${favGroups.size})",
                                Modifier.padding(horizontal = 0.dp, vertical = 2.dp),
                            )
                        }
                        items(favGroups, key = { "grp_${it.key}" }, contentType = { "group" }) { grp ->
                            OshiGroupBlock(
                                group = grp,
                                bulkMode = bulkMode.value,
                                selectedIds = selectedIds,
                                onToggleSelect = { id ->
                                    if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
                                },
                                onOpenDetail = { acc -> detailAccount = acc },
                            )
                        }
                    }

                    if (otherGroups.isNotEmpty()) {
                        if (favGroups.isNotEmpty()) {
                            item(key = "sec_other", contentType = "sec") {
                                SectionLabel(
                                    "KREATOR LAINNYA (${otherGroups.size})",
                                    Modifier.padding(horizontal = 0.dp, vertical = 4.dp),
                                )
                            }
                        }
                        items(otherGroups, key = { "grp_${it.key}" }, contentType = { "group" }) { grp ->
                            OshiGroupBlock(
                                group = grp,
                                bulkMode = bulkMode.value,
                                selectedIds = selectedIds,
                                onToggleSelect = { id ->
                                    if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
                                },
                                onOpenDetail = { acc -> detailAccount = acc },
                            )
                        }
                    }
                } else {
                    items(filteredAccounts, key = { it.id }, contentType = { "oshi_row" }) { account ->
                        OshiCompactRow(
                            account = account,
                            bulkMode = bulkMode.value,
                            selected = selectedIds.contains(account.id),
                            onClick = {
                                if (bulkMode.value) {
                                    if (selectedIds.contains(account.id)) selectedIds.remove(account.id)
                                    else selectedIds.add(account.id)
                                } else {
                                    detailAccount = account
                                }
                            },
                        )
                    }
                }
            }
            }
        }
    }

    val liveAccountSnapshot = detailAccount?.let { target ->
        accounts.firstOrNull { it.id == target.id }
    }
    if (liveAccountSnapshot != null) {
        AccountDetailSheet(
            container = container,
            account = liveAccountSnapshot,
            onDismiss = { detailAccount = null },
        )
    }

    if (showAdd) {
        AddAccountDialog(
            container = container,
            onDismiss = { showAdd = false },
            onAccountSaved = {
                showAdd = false
                scope.launch { listState.animateScrollToItem(0) }
            },
        )
    }

    if (showRosterTransfer) {
        RosterTransferDialog(container, onDismiss = { showRosterTransfer = false })
    }
}

private data class OshiGroup(
    val key: String,
    val title: String,
    val hasFavorite: Boolean,
    val accounts: List<Account>,
)

private val oshiSuffixRegex = Regex("\\b(jkt48|official|live|tv)\\b", RegexOption.IGNORE_CASE)
private val nonAlnumRegex = Regex("[^a-zA-Z0-9\\s]")
private val multiSpaceRegex = Regex("\\s+")
private val handlePrefixRegex = Regex("^(jkt48[._-]?)", RegexOption.IGNORE_CASE)

private fun cleanOshiDisplayName(raw: String): String =
    raw.substringBefore('/')
        .substringBefore('（')
        .substringBefore('(')
        .replace(oshiSuffixRegex, " ")
        .replace(multiSpaceRegex, " ")
        .trim()

private fun normalizeOshiGroupKey(account: Account): String {
    val cleaned = cleanOshiDisplayName(account.displayName)
        .replace(nonAlnumRegex, " ")
        .replace(multiSpaceRegex, " ")
        .trim()
        .lowercase()
    if (cleaned.length >= 2) return cleaned
    return account.handle
        .removePrefix("@")
        .replace(handlePrefixRegex, "")
        .replace(Regex("[._-]"), "")
        .lowercase()
}

private fun formatOshiGroupTitle(accounts: List<Account>): String {
    val candidates = accounts.map { cleanOshiDisplayName(it.displayName) }.filter { it.isNotBlank() }
    val picked = candidates.firstOrNull { it.any(Char::isUpperCase) } ?: candidates.firstOrNull()
    return picked?.replaceFirstChar { it.uppercase() } ?: "@${accounts.first().handle}"
}

private fun platformGroupOrder(platform: String): Int = when (platform) {
    Platform.TIKTOK.name -> 0
    Platform.IDN.name -> 1
    Platform.SHOWROOM.name -> 2
    else -> 3
}

private fun buildOshiGroups(accounts: List<Account>): List<OshiGroup> =
    accounts.groupBy { normalizeOshiGroupKey(it) }
        .entries
        .map { (key, list) ->
            val sortedMembers = list.sortedWith(
                compareByDescending<Account> { it.isFavorite }
                    .thenBy { platformGroupOrder(it.platform) }
                    .thenBy { it.id },
            )
            OshiGroup(
                key = key,
                title = formatOshiGroupTitle(sortedMembers),
                hasFavorite = sortedMembers.any { it.isFavorite },
                accounts = sortedMembers,
            )
        }
        .sortedWith(
            compareByDescending<OshiGroup> { it.hasFavorite }
                .thenByDescending { it.accounts.size > 1 }
                .thenBy { it.title.lowercase() },
        )

@Composable
private fun OshiGroupBlock(
    group: OshiGroup,
    bulkMode: Boolean,
    selectedIds: List<Long>,
    onToggleSelect: (Long) -> Unit,
    onOpenDetail: (Account) -> Unit,
) {
    if (group.accounts.size == 1) {
        val acc = group.accounts.first()
        OshiCompactRow(
            account = acc,
            bulkMode = bulkMode,
            selected = selectedIds.contains(acc.id),
            onClick = { if (bulkMode) onToggleSelect(acc.id) else onOpenDetail(acc) },
        )
        return
    }

    Surface(
        color = Ra.colors.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (group.hasFavorite) Ra.colors.sun.copy(alpha = 0.45f) else Ra.colors.outline.copy(alpha = 0.45f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = group.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (group.hasFavorite) Ra.colors.sun else Ra.colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (group.hasFavorite) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "Favorit",
                            tint = Ra.colors.sun,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                Text(
                    text = "${group.accounts.size} platform",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (group.hasFavorite) Ra.colors.sun else Ra.colors.textMuted,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            group.accounts.forEach { acc ->
                OshiCompactRow(
                    account = acc,
                    bulkMode = bulkMode,
                    selected = selectedIds.contains(acc.id),
                    onClick = { if (bulkMode) onToggleSelect(acc.id) else onOpenDetail(acc) },
                )
            }
        }
    }
}

/**
 * Kartu daftar ringkas (~76dp) dengan penanda pill teks lembut ("Live Auto", "Post Auto", "Notif Saja")
 * agar tidak pernah dikira tombol refresh/download oleh pengguna.
 */
@Composable
private fun OshiCompactRow(
    account: Account,
    bulkMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Ra.colors.sunDim else Ra.colors.surfaceAlt,
        ),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) Ra.colors.sun else Ra.colors.outline.copy(alpha = 0.35f),
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (bulkMode) {
                Icon(
                    imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (selected) "Terpilih" else "Pilih",
                    tint = if (selected) Ra.colors.sun else Ra.colors.textMuted,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
            }

            LiveRingAvatar(account = account, size = 44.dp, isLive = false)
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = account.displayName.ifBlank { account.handle },
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
                    text = "${platformLabel(account.platform)} · @${account.handle}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Ra.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Penanda mode aktif berbentuk tag teks lembut di bawah username
                val hasAutoLive = account.recordLive && !account.notifyOnly
                val hasAutoPost = account.watchPosts && account.platform == Platform.TIKTOK.name
                val hasNotifOnly = account.notifyOnly
                if (hasAutoLive || hasAutoPost || hasNotifOnly) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (hasAutoLive) {
                            StatusTag("Live Auto", Ra.colors.sun, Ra.colors.sunDim)
                        }
                        if (hasAutoPost) {
                            StatusTag("Post Auto", Ra.colors.postFg, Ra.colors.postBg)
                        }
                        if (hasNotifOnly) {
                            StatusTag("Notif Saja", Ra.colors.textMuted, Ra.colors.surfaceHigh)
                        }
                    }
                }
            }

            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = "Buka detail",
                tint = Ra.colors.textMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun StatusTag(text: String, fg: Color, bg: Color) {
    Text(
        text = text,
        color = fg,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun AccountDetailSheet(
    container: AppContainer,
    account: Account,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isChecking by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val allEntries by container.libraryEntries.collectAsState()
    val savedCount = remember(allEntries, account.handle) {
        allEntries?.count {
            it.name.contains(account.handle, ignoreCase = true) ||
                it.relativeDir.contains(account.handle, ignoreCase = true)
        } ?: 0
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = Ra.colors.bg, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    )
                    .navigationBarsPadding(),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = Ra.colors.text)
                    }
                    Text(
                        "Detail Akun",
                        style = MaterialTheme.typography.titleLarge,
                        color = Ra.colors.text,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                container.db.accounts().setFavorite(account.id, !account.isFavorite)
                            }
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = if (account.isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            contentDescription = if (account.isFavorite) "Hapus dari Favorit" else "Tandai Favorit",
                            tint = if (account.isFavorite) Ra.colors.sun else Ra.colors.textSecondary,
                        )
                    }
                }

                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        color = Ra.colors.surfaceAlt,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Ra.colors.outline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LiveRingAvatar(account = account, size = 72.dp, isLive = false)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    account.displayName.ifBlank { account.handle },
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Ra.colors.text,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    RaBadge(platformLabel(account.platform))
                                    Text("@${account.handle}", style = MaterialTheme.typography.bodySmall, color = Ra.colors.textMuted)
                                }
                                Text(
                                    buildString {
                                        append("$savedCount kenangan tersimpan")
                                        val lastLive = Humanizer.formatRelativeLastLive(account.lastLiveAt)
                                        if (lastLive != "Offline") append(" · ${lastLive.removePrefix("Offline · ")}")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Ra.colors.sun,
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        if (account.platform == Platform.TIKTOK.name) {
                            Button(
                                enabled = !isChecking,
                                onClick = {
                                    isChecking = true
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val found = PostWatchWorker.checkSingleAccount(context, container, account)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    context,
                                                    if (found > 0) "Ditemukan $found item baru dari @${account.handle}!"
                                                    else "@${account.handle}: Tidak ada postingan/story baru.",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, "Gagal cek: ${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        } finally {
                                            isChecking = false
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Ra.colors.sunSoft, contentColor = Ra.colors.sun),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            ) {
                                if (isChecking) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ra.colors.sun)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Memeriksa...")
                                } else {
                                    Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Cek Story & Post Sekarang", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                val url = when (account.platform) {
                                    Platform.TIKTOK.name -> "https://www.tiktok.com/@${account.handle}"
                                    Platform.SHOWROOM.name -> "https://www.showroom-live.com/r/${account.handle}"
                                    Platform.IDN.name -> "https://www.idn.app/${account.handle}"
                                    else -> ""
                                }
                                if (url.isNotBlank()) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = Ra.colors.textSecondary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Profil", color = Ra.colors.textSecondary)
                        }
                    }

                    SectionLabel("PENGATURAN PANTAUAN", Modifier.padding(horizontal = 0.dp))
                    Surface(
                        color = Ra.colors.surface,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            SettingRow(
                                title = "Rekam live otomatis",
                                subtitle = "Otomatis mulai rekam ke MP4 saat siaran dimulai",
                                toggle = true,
                                toggleValue = account.recordLive,
                                onToggle = { v ->
                                    scope.launch {
                                        container.db.accounts().update(
                                            account.copy(recordLive = v, notifyOnly = if (v) false else account.notifyOnly),
                                        )
                                    }
                                },
                            )
                            if (account.platform == Platform.TIKTOK.name) {
                                HorizontalDivider(color = Ra.colors.outline.copy(alpha = 0.4f))
                                SettingRow(
                                    title = "Unduh postingan & story baru",
                                    subtitle = "Pantau unggahan baru tiap 15 menit tanpa watermark",
                                    toggle = true,
                                    toggleValue = account.watchPosts,
                                    onToggle = { v ->
                                        scope.launch {
                                            container.db.accounts().update(account.copy(watchPosts = v))
                                        }
                                    },
                                )
                            }
                            HorizontalDivider(color = Ra.colors.outline.copy(alpha = 0.4f))
                            SettingRow(
                                title = "Hanya notifikasi",
                                subtitle = "Kirim notifikasi saja saat live dimulai, tanpa merekam video otomatis",
                                toggle = true,
                                toggleValue = account.notifyOnly,
                                onToggle = { v ->
                                    scope.launch {
                                        container.db.accounts().update(
                                            account.copy(notifyOnly = v, recordLive = if (v) false else account.recordLive),
                                        )
                                    }
                                },
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        border = BorderStroke(1.dp, Ra.colors.danger.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.Delete, null, tint = Ra.colors.danger, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Hapus Akun dari Pantauan", color = Ra.colors.danger, style = MaterialTheme.typography.labelLarge)
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = Ra.colors.surfaceAlt,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Hapus Akun", color = Ra.colors.text) },
            text = {
                Text(
                    "Hapus ${account.displayName.ifBlank { "@${account.handle}" }} (${platformLabel(account.platform)}) dari daftar pantauan? Kenangan yang sudah tersimpan di Pustaka tidak ikut terhapus.",
                    color = Ra.colors.textSecondary,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            container.db.accounts().delete(account.id)
                            showDeleteConfirm = false
                            onDismiss()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Ra.colors.danger, contentColor = Ra.colors.onSun),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Batal", color = Ra.colors.textMuted) }
            },
        )
    }
}

private fun platformLabel(platform: String) = when (platform) {
    "TIKTOK" -> "TikTok"
    "SHOWROOM" -> "SHOWROOM"
    "IDN" -> "IDN"
    else -> platform
}

@Composable
private fun ToggleLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Ra.colors.textSecondary)
        RaToggle(checked = checked, labelDesc = label) { onChange(it) }
    }
}

@Composable
private fun AddAccountDialog(
    container: AppContainer,
    onDismiss: () -> Unit,
    onAccountSaved: () -> Unit = onDismiss,
) {
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    var input by remember { mutableStateOf("") }
    var manualPlatform by remember { mutableStateOf(Platform.TIKTOK) }
    var recordLive by remember { mutableStateOf(true) }
    var watchPosts by remember { mutableStateOf(true) }
    var showMemberPicker by remember { mutableStateOf(false) }
    var memberSearch by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    val memberList = remember {
        RosterTransfer.loadFromAsset(container.appContext)
    }
    var pickedMember by remember { mutableStateOf<RosterTransfer.MemberEntry?>(null) }
    val filteredMembers = remember(memberList, memberSearch) {
        if (memberSearch.isBlank()) memberList
        else memberList.filter {
            it.name.contains(memberSearch, ignoreCase = true) ||
                it.fullName.contains(memberSearch, ignoreCase = true)
        }
    }

    val detectedPlatform = remember(input) {
        PlatformApi.detectPlatformHint(input)
    }
    val effectivePlatform = detectedPlatform ?: manualPlatform

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ra.colors.surfaceAlt,
        shape = RoundedCornerShape(28.dp),
        title = {
            Column {
                Text("Tambah Akun Pantauan", style = MaterialTheme.typography.titleLarge, color = Ra.colors.text)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tempel tautan profil atau ketik @username kreator",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ra.colors.textSecondary,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        "Tautan atau @username",
                        style = MaterialTheme.typography.labelMedium,
                        color = Ra.colors.textMuted,
                    )
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            if (it.isBlank()) pickedMember = null
                            error = null
                        },
                        placeholder = {
                            Text(
                                "@username atau link profil…",
                                color = Ra.colors.textMuted,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        singleLine = true,
                        maxLines = 1,
                        shape = RoundedCornerShape(14.dp),
                        trailingIcon = {
                            if (input.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        input = ""
                                        pickedMember = null
                                        error = null
                                    },
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(Icons.Filled.Close, "Hapus", tint = Ra.colors.textSecondary, modifier = Modifier.size(18.dp))
                                }
                            } else {
                                Surface(
                                    onClick = {
                                        val clip = clipboardManager.getText()?.text?.trim()
                                        if (!clip.isNullOrBlank()) {
                                            input = clip
                                            pickedMember = null
                                            error = null
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = Ra.colors.sunDim,
                                    modifier = Modifier.padding(end = 6.dp),
                                ) {
                                    Text(
                                        "Tempel",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = Ra.colors.sun,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Ra.colors.text,
                            unfocusedTextColor = Ra.colors.text,
                            focusedBorderColor = Ra.colors.sun,
                            unfocusedBorderColor = Ra.colors.outline,
                            focusedContainerColor = Ra.colors.surface,
                            unfocusedContainerColor = Ra.colors.surface,
                            cursorColor = Ra.colors.sun,
                        ),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    )
                }

                if (memberList.isNotEmpty()) {
                    Surface(
                        onClick = { showMemberPicker = !showMemberPicker },
                        shape = RoundedCornerShape(12.dp),
                        color = if (showMemberPicker || pickedMember != null) Ra.colors.sunDim else Ra.colors.surface,
                        border = BorderStroke(
                            1.dp,
                            if (showMemberPicker || pickedMember != null) Ra.colors.sun.copy(alpha = 0.55f) else Ra.colors.outline.copy(alpha = 0.6f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.PeopleAlt, null, tint = Ra.colors.sun, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = pickedMember?.let { "Dipilih: ${it.name}" } ?: "Pilih cepat member JKT48",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (pickedMember != null || showMemberPicker) Ra.colors.sun else Ra.colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = if (showMemberPicker) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (showMemberPicker) "Tutup daftar" else "Buka daftar",
                                tint = Ra.colors.textMuted,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    if (showMemberPicker) {
                        OutlinedTextField(
                            value = memberSearch,
                            onValueChange = { memberSearch = it },
                            placeholder = {
                                Text(
                                    "Cari nama member…",
                                    color = Ra.colors.textMuted,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = { Icon(Icons.Filled.Search, null, tint = Ra.colors.textMuted, modifier = Modifier.size(18.dp)) },
                            trailingIcon = {
                                if (memberSearch.isNotBlank()) {
                                    IconButton(onClick = { memberSearch = "" }, modifier = Modifier.size(40.dp)) {
                                        Icon(Icons.Filled.Close, "Hapus pencarian", tint = Ra.colors.textMuted, modifier = Modifier.size(18.dp))
                                    }
                                }
                            },
                            singleLine = true,
                            maxLines = 1,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Ra.colors.text,
                                unfocusedTextColor = Ra.colors.text,
                                focusedBorderColor = Ra.colors.sun,
                                unfocusedBorderColor = Ra.colors.outline,
                                focusedContainerColor = Ra.colors.surface,
                                unfocusedContainerColor = Ra.colors.surface,
                                cursorColor = Ra.colors.sun,
                            ),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        )

                        Surface(
                            color = Ra.colors.surface,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Ra.colors.outline.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 176.dp),
                        ) {
                            if (filteredMembers.isEmpty()) {
                                Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
                                    Text("Member tidak ditemukan.", style = MaterialTheme.typography.bodySmall, color = Ra.colors.textMuted)
                                }
                            } else {
                                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                                    filteredMembers.forEach { m ->
                                        val isSelected = pickedMember == m
                                        val platformsBadge = buildList {
                                            if (m.tiktokUser.isNotBlank()) add("TT")
                                            if (m.showroomKey.isNotBlank()) add("SR")
                                            if (m.idnUser.isNotBlank()) add("IDN")
                                        }.joinToString("·")
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    pickedMember = m
                                                    showMemberPicker = false
                                                    memberSearch = ""
                                                    error = null
                                                    when {
                                                        m.tiktokUser.isNotBlank() -> {
                                                            manualPlatform = Platform.TIKTOK
                                                            input = "@" + m.tiktokUser
                                                        }
                                                        m.idnUser.isNotBlank() -> {
                                                            manualPlatform = Platform.IDN
                                                            input = m.idnUser
                                                        }
                                                        m.showroomKey.isNotBlank() -> {
                                                            manualPlatform = Platform.SHOWROOM
                                                            input = m.showroomKey
                                                        }
                                                    }
                                                }
                                                .background(if (isSelected) Ra.colors.sunDim.copy(alpha = 0.5f) else Color.Transparent)
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Box(
                                                Modifier.size(28.dp).background(Ra.colors.sun.copy(alpha = 0.18f), CircleShape),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    m.name.take(1).uppercase(),
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = Ra.colors.sun,
                                                )
                                            }
                                            Spacer(Modifier.width(10.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    m.name,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = if (isSelected) Ra.colors.sun else Ra.colors.text,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                if (m.fullName.isNotBlank() && !m.fullName.equals(m.name, ignoreCase = true)) {
                                                    Text(
                                                        m.fullName,
                                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                                        color = Ra.colors.textMuted,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                            Text(
                                                platformsBadge,
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                                color = Ra.colors.textMuted,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    pickedMember?.let { m ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Akun ${m.name} (${m.fullName}):",
                                style = MaterialTheme.typography.labelMedium,
                                color = Ra.colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            ) {
                                if (m.tiktokUser.isNotBlank()) {
                                    val active = manualPlatform == Platform.TIKTOK && input.removePrefix("@").equals(m.tiktokUser, ignoreCase = true)
                                    RaChip("TikTok", active = active) {
                                        manualPlatform = Platform.TIKTOK
                                        input = "@" + m.tiktokUser
                                        error = null
                                    }
                                }
                                if (m.showroomKey.isNotBlank()) {
                                    val active = manualPlatform == Platform.SHOWROOM && input.equals(m.showroomKey, ignoreCase = true)
                                    RaChip("SHOWROOM", active = active) {
                                        manualPlatform = Platform.SHOWROOM
                                        input = m.showroomKey
                                        error = null
                                    }
                                }
                                if (m.idnUser.isNotBlank()) {
                                    val active = manualPlatform == Platform.IDN && input.equals(m.idnUser, ignoreCase = true)
                                    RaChip("IDN", active = active) {
                                        manualPlatform = Platform.IDN
                                        input = m.idnUser
                                        error = null
                                    }
                                }
                            }
                        }
                    }
                }

                if (detectedPlatform != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Platform otomatis:",
                            style = MaterialTheme.typography.labelMedium,
                            color = Ra.colors.textMuted,
                        )
                        Spacer(Modifier.width(8.dp))
                        RaBadge(detectedPlatform.label, dot = true)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Pilih platform (bila hanya username):",
                            style = MaterialTheme.typography.labelMedium,
                            color = Ra.colors.textMuted,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Platform.values().forEach { p ->
                                RaChip(p.label, active = effectivePlatform == p) {
                                    manualPlatform = p
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToggleLine("Rekam live otomatis", recordLive) { recordLive = it }
                    if (effectivePlatform == Platform.TIKTOK) {
                        ToggleLine("Unduh postingan & story otomatis", watchPosts) { watchPosts = it }
                    }
                }

                error?.let {
                    Text(it, color = Ra.colors.danger, style = MaterialTheme.typography.bodySmall)
                }

                if (checking) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Ra.colors.sun)
                        Spacer(Modifier.width(10.dp))
                        Text("Memverifikasi akun...", style = MaterialTheme.typography.bodySmall, color = Ra.colors.sun)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !checking && input.isNotBlank(),
                onClick = {
                    checking = true
                    error = null
                    scope.launch {
                        try {
                            val resolved = withContext(Dispatchers.IO) {
                                PlatformApi.resolveAccountInput(input, effectivePlatform)
                            }
                            val existing = container.db.accounts().byPlatformAndHandle(resolved.platform.name, resolved.handle)
                            if (existing != null) {
                                val updated = existing.copy(
                                    displayName = resolved.displayName,
                                    avatarUrl = resolved.avatarUrl ?: existing.avatarUrl,
                                    createdAt = System.currentTimeMillis(),
                                    recordLive = recordLive,
                                    watchPosts = watchPosts && resolved.platform == Platform.TIKTOK,
                                )
                                container.db.accounts().update(updated)
                                if (watchPosts && resolved.platform == Platform.TIKTOK) {
                                    scope.launch(Dispatchers.IO) {
                                        PostWatchWorker.checkAccounts(container.appContext, container)
                                    }
                                }
                                onAccountSaved()
                            } else {
                                val inserted = container.db.accounts().insert(
                                    Account(
                                        platform = resolved.platform.name,
                                        handle = resolved.handle,
                                        displayName = resolved.displayName,
                                        avatarUrl = resolved.avatarUrl,
                                        recordLive = recordLive,
                                        watchPosts = watchPosts && resolved.platform == Platform.TIKTOK,
                                        createdAt = System.currentTimeMillis(),
                                    ),
                                )
                                if (inserted == -1L) {
                                    error = "Akun @${resolved.handle} (${resolved.platform.label}) sudah ada di daftar."
                                } else {
                                    if (watchPosts && resolved.platform == Platform.TIKTOK) {
                                        scope.launch(Dispatchers.IO) {
                                            PostWatchWorker.checkAccounts(container.appContext, container)
                                        }
                                    }
                                    onAccountSaved()
                                }
                            }
                        } catch (e: Exception) {
                            val msg = e.message ?: e.javaClass.simpleName
                            error = if (msg.contains("tidak ditemukan", ignoreCase = true)) msg
                            else "Gagal memverifikasi akun: $msg"
                        } finally {
                            checking = false
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Ra.colors.sun, contentColor = Ra.colors.onSun),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Periksa & simpan") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Batal", color = Ra.colors.textMuted)
            }
        },
    )
}

@Composable
fun RosterTransferDialog(container: AppContainer, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf("Impor") }
    var urlInput by remember { mutableStateOf(RosterTransfer.DEFAULT_PRESET_URL) }
    var jsonInput by remember { mutableStateOf("") }
    var parsedMembers by remember { mutableStateOf<List<RosterTransfer.MemberEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    val openDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    isLoading = true
                    val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    val list = RosterTransfer.parseJson(content)
                    withContext(Dispatchers.Main) {
                        parsedMembers = list
                        statusMsg = "Terbaca ${list.size} member dari berkas."
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { statusMsg = "Gagal membaca berkas: ${e.message}" }
                } finally {
                    withContext(Dispatchers.Main) { isLoading = false }
                }
            }
        }
    }

    val createDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    isLoading = true
                    val accounts = container.db.accounts().allOnce()
                    val json = RosterTransfer.exportToJson(accounts)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    withContext(Dispatchers.Main) { statusMsg = "Roster berhasil disimpan ke berkas." }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { statusMsg = "Gagal menyimpan berkas: ${e.message}" }
                } finally {
                    withContext(Dispatchers.Main) { isLoading = false }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ra.colors.surfaceAlt,
        title = { Text("Transfer Roster (dyrecord)", style = MaterialTheme.typography.titleMedium, color = Ra.colors.text) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaChip("Impor", active = selectedTab == "Impor") { selectedTab = "Impor" }
                    RaChip("Ekspor", active = selectedTab == "Ekspor") { selectedTab = "Ekspor" }
                }

                if (selectedTab == "Impor") {
                    Text(
                        "Impor banyak akun kreator sekaligus dari berkas JSON atau daftar preset.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ra.colors.textMuted,
                    )

                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    isLoading = true
                                    val list = RosterTransfer.loadFromAsset(context)
                                    withContext(Dispatchers.Main) {
                                        parsedMembers = list
                                        statusMsg = "Memuat ${list.size} member dari preset bawaan!"
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { statusMsg = "Gagal memuat preset: ${e.message}" }
                                } finally {
                                    withContext(Dispatchers.Main) { isLoading = false }
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Ra.colors.sunSoft, contentColor = Ra.colors.sun),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("Muat Preset Bawaan (JKT48)", style = MaterialTheme.typography.labelLarge)
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    isLoading = true
                                    val list = RosterTransfer.fetchFromUrl(urlInput, context)
                                    withContext(Dispatchers.Main) {
                                        parsedMembers = list
                                        statusMsg = "Ditemukan ${list.size} member!"
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { statusMsg = "Gagal unduh: ${e.message}" }
                                } finally {
                                    withContext(Dispatchers.Main) { isLoading = false }
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("Unduh Daftar JKT48 Terbaru (Online)", color = Ra.colors.text)
                    }

                    OutlinedButton(
                        onClick = { openDocLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("Buka Berkas JSON Lokal", color = Ra.colors.text)
                    }

                    OutlinedTextField(
                        value = jsonInput,
                        onValueChange = {
                            jsonInput = it
                            if (it.isNotBlank()) parsedMembers = RosterTransfer.parseJson(it)
                        },
                        placeholder = { Text("Atau tempel teks JSON di sini…", color = Ra.colors.textMuted) },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )

                    if (isLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Ra.colors.sun, strokeWidth = 2.dp)
                            Text("Memproses…", style = MaterialTheme.typography.bodySmall, color = Ra.colors.textMuted)
                        }
                    }

                    statusMsg?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Gagal")) Ra.colors.danger else Ra.colors.sun)
                    }

                    if (parsedMembers.isNotEmpty()) {
                        Surface(
                            color = Ra.colors.surface,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(
                                    "Siap diimpor: ${parsedMembers.size} member",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Ra.colors.sun,
                                )
                                Text(
                                    parsedMembers.take(8).joinToString { it.name } + if (parsedMembers.size > 8) ", dll." else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Ra.colors.textMuted,
                                )
                            }
                        }

                        Button(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        isLoading = true
                                        val added = RosterTransfer.importIntoDb(container.db.accounts(), parsedMembers)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "$added akun baru berhasil ditambahkan!", Toast.LENGTH_LONG).show()
                                            onDismiss()
                                        }
                                    } finally {
                                        withContext(Dispatchers.Main) { isLoading = false }
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Ra.colors.sun, contentColor = Ra.colors.onSun),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text("Impor Sekarang (${parsedMembers.size} member)")
                        }
                    }
                } else {
                    Text(
                        "Ekspor seluruh akun tersimpan menjadi berkas JSON format dyrecord.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ra.colors.textMuted,
                    )

                    OutlinedButton(
                        onClick = { createDocLauncher.launch("members.json") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("Simpan ke Berkas members.json", color = Ra.colors.sun)
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val list = container.db.accounts().allOnce()
                                val json = RosterTransfer.exportToJson(list)
                                withContext(Dispatchers.Main) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Roster", json))
                                    Toast.makeText(context, "Roster disalin ke papan klip!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("Salin ke Papan Klip", color = Ra.colors.text)
                    }

                    statusMsg?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Ra.colors.sun)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Tutup", color = Ra.colors.textMuted)
            }
        },
    )
}
