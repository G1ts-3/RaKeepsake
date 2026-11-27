package id.rakeepsake.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PeopleAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import id.rakeepsake.cloud.data.CloudUpload
import id.rakeepsake.cloud.data.UploadState
import id.rakeepsake.cloud.work.CloudUploadPlaybackGuard
import id.rakeepsake.cloud.work.CloudUploadProgress
import id.rakeepsake.cloud.work.CloudUploadProgressHolder
import id.rakeepsake.cloud.work.CloudUploadScheduler
import id.rakeepsake.data.Account
import id.rakeepsake.data.AppContainer
import id.rakeepsake.monitor.LiveStateHolder
import id.rakeepsake.util.Humanizer
import id.rakeepsake.util.MediaType
import id.rakeepsake.util.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DynamicCreateDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent {
        val (mime, title) = input
        return Intent(Intent.ACTION_CREATE_DOCUMENT)
            .setType(mime)
            .putExtra(Intent.EXTRA_TITLE, title)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return if (intent == null || resultCode != android.app.Activity.RESULT_OK) null else intent.data
    }
}

/** Pustaka ("Kenangan"): thumbnail video/foto/audio, judul manusiawi, menu ⋮ + Undo Hapus, Grid & pengelompokan. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    container: AppContainer,
    modifier: Modifier,
    snackbarHostState: SnackbarHostState? = null,
    isActiveTab: Boolean = true,
    onOpenMedia: ((Storage.LibEntry, String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    val recTick by produceState(System.currentTimeMillis(), isActiveTab) {
        if (isActiveTab) {
            while (true) {
                delay(if (LiveStateHolder.recording.isNotEmpty()) 1000L else 5000L)
                value = System.currentTimeMillis()
            }
        }
    }
    val activeRecordings = remember(recTick, LiveStateHolder.recording.size) {
        LiveStateHolder.recording.values.sortedBy { it.startedAt }
    }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedFilter by rememberSaveable { mutableStateOf("Semua") } // Semua | Favorit | Live | Story | Postingan | Foto | Audio
    var movingEntry by remember { mutableStateOf<Storage.LibEntry?>(null) }
    var playing by remember { mutableStateOf<Storage.LibEntry?>(null) }
    var infoEntry by remember { mutableStateOf<Pair<Storage.LibEntry, Humanizer.HumanMediaInfo>?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showHeaderMenu by remember { mutableStateOf(false) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    val pendingDeletes = remember { mutableStateListOf<String>() }

    val selectedUris = remember { mutableStateListOf<String>() }
    val multiSelectActive = selectedUris.isNotEmpty()
    if (multiSelectActive) {
        BackHandler { selectedUris.clear() }
    }

    val accounts by container.accountsFlow.collectAsState()
    val accountByHandle = remember(accounts) { accounts.associateBy { it.handle.lowercase() } }

    val cloudSettings by container.cloudSettingsFlow.collectAsState()
    val cloudUploads by container.cloudUploadsFlow.collectAsState()
    val cloudProgressMap by CloudUploadProgressHolder.progress.collectAsState()
    val cloudByPath = remember(cloudUploads) { cloudUploads.associateBy { it.localPath } }

    val settings by container.settingsFlow.collectAsState()
    val favoriteMedia = settings.favoriteMedia
    val viewMode = settings.libraryViewMode
    val groupMode = settings.libraryGroupMode
    var gridOverrideForFilter by remember(selectedFilter) { mutableStateOf<Boolean?>(null) }
    val useGrid = gridOverrideForFilter ?: (selectedFilter == "Foto" || viewMode == "GRID")

    // Baca langsung dari warm StateFlow di AppContainer (0ms tanpa scan MediaStore atau statfs di UI thread!)
    val entries by container.libraryEntries.collectAsState()
    val freeSpaceBytes by container.freeBytes.collectAsState()

    val intentSenderLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            Toast.makeText(context, "Berkas berhasil dihapus!", Toast.LENGTH_SHORT).show()
            scope.launch(Dispatchers.IO) {
                syncDeletedPostsWithStorage(context, container)
            }
            pendingDeletes.clear()
            container.refreshLibrary()
        } else {
            pendingDeletes.clear()
        }
    }

    val createDocLauncher = rememberLauncherForActivityResult(
        DynamicCreateDocument(),
    ) { uri ->
        val target = movingEntry
        if (uri != null && target != null) {
            scope.launch(Dispatchers.IO) {
                val copied = Storage.copyUri(context, target.uri, uri)
                val deleteAfter = container.settings.deleteAfterMove.first()
                if (copied) {
                    if (deleteAfter) {
                        runCatching { Storage.deleteEntry(context, target.uri, target.name, target.relativeDir) }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Berkas berhasil dipindahkan!", Toast.LENGTH_SHORT).show()
                        container.refreshLibrary()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Gagal memindahkan berkas", Toast.LENGTH_SHORT).show()
                    }
                }
                movingEntry = null
            }
        }
    }

    fun requestDeleteWithUndo(entry: Storage.LibEntry, humanTitle: String) {
        val uriKey = entry.uri.toString()
        if (!pendingDeletes.contains(uriKey)) pendingDeletes.add(uriKey)

        if (snackbarHostState == null) {
            scope.launch(Dispatchers.IO) {
                performPhysicalDelete(context, container, entry, intentSenderLauncher) { container.refreshLibrary() }
            }
            return
        }

        scope.launch {
            val res = snackbarHostState.showSnackbar(
                message = "Dihapus · $humanTitle",
                actionLabel = "Urungkan",
                duration = SnackbarDuration.Long,
            )
            if (res == SnackbarResult.ActionPerformed) {
                pendingDeletes.remove(uriKey)
            } else {
                withContext(Dispatchers.IO) {
                    performPhysicalDelete(context, container, entry, intentSenderLauncher) {
                        pendingDeletes.remove(uriKey)
                        container.refreshLibrary()
                    }
                }
            }
        }
    }

    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val visibleList = remember(entries, pendingDeletes.size) {
            entries?.filterNot { pendingDeletes.contains(it.uri.toString()) }
        }

        // Top Bar: Mode Multi-Pilih atau Header Standar
        if (multiSelectActive) {
            Surface(
                color = Ra.colors.surfaceAlt,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Ra.colors.sun.copy(alpha = 0.5f)),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { selectedUris.clear() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Close, "Batal pilih", tint = Ra.colors.text)
                    }
                    Text(
                        "${selectedUris.size} dipilih",
                        style = MaterialTheme.typography.titleMedium,
                        color = Ra.colors.sun,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            val itemsToShare = visibleList.orEmpty().filter { selectedUris.contains(it.uri.toString()) }
                            if (itemsToShare.isNotEmpty()) {
                                runCatching {
                                    val uris = ArrayList<Uri>(itemsToShare.map { it.uri })
                                    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                        type = "*/*"
                                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(send, "Bagikan ${uris.size} kenangan"))
                                }
                            }
                            selectedUris.clear()
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Filled.Share, "Bagikan dipilih", tint = Ra.colors.text)
                    }
                    IconButton(
                        onClick = { showBatchDeleteConfirm = true },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Filled.Delete, "Hapus dipilih", tint = Ra.colors.danger)
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxWidth()) {
                ScreenHeader(
                    title = "Pustaka",
                    subtitle = when {
                        visibleList == null -> "Memuat kenangan…"
                        activeRecordings.isNotEmpty() -> "${visibleList.size} kenangan · ${activeRecordings.size} sedang direkam · ${formatBytes(freeSpaceBytes)} bebas"
                        else -> "${visibleList.size} kenangan tersimpan · ${formatBytes(freeSpaceBytes)} bebas"
                    },
                    actionIcon = if (useGrid) Icons.Filled.ViewAgenda else Icons.Filled.GridView,
                    actionDesc = if (useGrid) "Tampilan Daftar" else "Tampilan Grid",
                    onAction = {
                        val nextGrid = !useGrid
                        gridOverrideForFilter = nextGrid
                        if (selectedFilter != "Foto") {
                            scope.launch {
                                container.settings.setLibraryViewMode(if (nextGrid) "GRID" else "LIST")
                            }
                        }
                    },
                    secondActionIcon = Icons.Filled.MoreVert,
                    secondActionDesc = "Opsi Pustaka",
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
                                text = { Text("Segarkan Pustaka", color = Ra.colors.text) },
                                leadingIcon = { Icon(Icons.Filled.Refresh, null, tint = Ra.colors.sun) },
                                onClick = {
                                    showHeaderMenu = false
                                    container.refreshLibrary()
                                    scope.launch(Dispatchers.IO) {
                                        runCatching { id.rakeepsake.work.PostWatchWorker.checkAccounts(context, container) }
                                    }
                                },
                            )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (groupMode == "DATE") "Kelompokkan per Kreator" else "Kelompokkan per Tanggal",
                                    color = Ra.colors.text,
                                )
                            },
                            leadingIcon = { Icon(Icons.Filled.PeopleAlt, null, tint = Ra.colors.textSecondary) },
                            onClick = {
                                showHeaderMenu = false
                                scope.launch {
                                    container.settings.setLibraryGroupMode(if (groupMode == "DATE") "CREATOR" else "DATE")
                                }
                            },
                        )
                        if (!visibleList.isNullOrEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Upload semua yang belum terunggah", color = Ra.colors.text) },
                                leadingIcon = { Icon(Icons.Filled.CloudUpload, null, tint = Ra.colors.sun) },
                                onClick = {
                                    showHeaderMenu = false
                                    if (!cloudSettings.cloudEnabled) {
                                        Toast.makeText(context, "Hubungkan Google Drive di Setelan terlebih dahulu.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        scope.launch(Dispatchers.IO) {
                                            val count = CloudUploadScheduler.enqueueAllPending(context)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    context,
                                                    if (count > 0) "$count file dimasukkan ke antrean upload Drive."
                                                    else "Semua file sudah terunggah atau dalam antrean.",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    }
                                },
                            )
                            HorizontalDivider(color = Ra.colors.outline)
                            DropdownMenuItem(
                                text = { Text("Hapus Semua Arsip", color = Ra.colors.danger) },
                                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = Ra.colors.danger) },
                                onClick = {
                                    showHeaderMenu = false
                                    showClearConfirm = true
                                },
                            )
                        }
                    }
                    }
                }
            }
        }

        val favCount = remember(visibleList, favoriteMedia) {
            visibleList?.count { favoriteMedia.contains(it.uri.toString()) } ?: 0
        }
        val enriched = remember(visibleList, accountByHandle, searchQuery, selectedFilter, favoriteMedia) {
            visibleList?.map { entry ->
                entry to Humanizer.humanizeEntry(entry, accountByHandle)
            }?.filter { (entry, info) ->
                val matchQuery = searchQuery.isBlank() ||
                    info.title.contains(searchQuery, ignoreCase = true) ||
                    info.creatorLabel.contains(searchQuery, ignoreCase = true) ||
                    info.creatorHandle.contains(searchQuery, ignoreCase = true) ||
                    entry.name.contains(searchQuery, ignoreCase = true)
                val matchFilter = when (selectedFilter) {
                    "Favorit" -> favoriteMedia.contains(entry.uri.toString())
                    "Live" -> entry.kind == "LIVE"
                    "Story" -> entry.kind == "STORY"
                    "Postingan" -> entry.kind == "POSTINGAN" && !entry.mime.startsWith("image/") && !entry.mime.startsWith("audio/")
                    "Foto" -> entry.mime.startsWith("image/")
                    "Audio" -> entry.mime.startsWith("audio/")
                    else -> true
                }
                matchQuery && matchFilter
            }
        }

        val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        ScrollCollapsibleBody(
            canCollapse = { hdrPx ->
                (activeRecordings.isNotEmpty() || !enriched.isNullOrEmpty()) && if (useGrid) {
                    gridState.hasRealOverflow(hdrPx)
                } else {
                    listState.hasRealOverflow(hdrPx)
                }
            },
            resetKey = listOf(selectedFilter, searchQuery, multiSelectActive, useGrid, groupMode, visibleList?.size ?: 0),
            onAutoResetScroll = {
                if (useGrid) {
                    if (gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0) {
                        gridState.animateScrollToItem(0)
                    }
                } else {
                    if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
                        listState.animateScrollToItem(0)
                    }
                }
            },
            modifier = Modifier.weight(1f),
            header = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Kolom Pencarian
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Cari kreator atau judul media…", color = Ra.colors.textMuted) },
                        leadingIcon = { Icon(Icons.Filled.Search, "Cari", tint = Ra.colors.textMuted) },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Filled.Close, "Hapus pencarian", tint = Ra.colors.textSecondary)
                                }
                            }
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSearch = { focusManager.clearFocus() },
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Ra.colors.surfaceAlt,
                            unfocusedContainerColor = Ra.colors.surfaceAlt,
                            focusedTextColor = Ra.colors.text,
                            unfocusedTextColor = Ra.colors.text,
                            focusedBorderColor = Ra.colors.sun,
                            unfocusedBorderColor = Ra.colors.outline,
                            cursorColor = Ra.colors.sun,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )

                    // Filter Chips: Semua | Favorit ★ | Live | Story | Postingan | Foto | Audio
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            "Semua",
                            if (favCount > 0) "Favorit ($favCount)" else "Favorit",
                            "Live",
                            "Story",
                            "Postingan",
                            "Foto",
                            "Audio",
                        ).forEach { chipLabel ->
                            val filterKey = chipLabel.substringBefore(" (")
                            RaChip(
                                label = chipLabel,
                                active = selectedFilter == filterKey,
                                onClick = { selectedFilter = filterKey },
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
            },
        ) { bottomPadding ->
            if (enriched == null) {
                Column(Modifier.fillMaxSize().padding(bottom = bottomPadding)) {
                    if (activeRecordings.isNotEmpty()) {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            ActiveRecordingSection(activeRecordings, accounts, recTick)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Ra.colors.sun)
                    }
                }
            } else if (enriched.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(bottom = bottomPadding)) {
                    if (activeRecordings.isNotEmpty()) {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            ActiveRecordingSection(activeRecordings, accounts, recTick)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            message = if (searchQuery.isNotBlank() || selectedFilter != "Semua") {
                                "Tidak ada kenangan yang cocok dengan filter ini."
                            } else {
                                "Belum ada kenangan.\nTambahkan akun kreator agar RaKeepsake dapat menyimpannya."
                            },
                        )
                    }
                }
            } else {
                val grouped = remember(enriched, groupMode) {
                    if (groupMode == "CREATOR") {
                        enriched.groupBy { (_, info) -> info.creatorLabel }
                            .entries.sortedByDescending { it.value.maxOfOrNull { p -> p.first.dateModified } ?: 0L }
                    } else {
                        enriched.groupBy { (entry, _) -> Humanizer.formatDateSectionHeader(entry.dateModified) }
                            .entries.toList()
                    }
                }

                if (useGrid) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding + 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (activeRecordings.isNotEmpty()) {
                            item(span = { GridItemSpan(3) }, key = "active_rec_banner") {
                                ActiveRecordingSection(activeRecordings, accounts, recTick)
                            }
                        }
                        grouped.forEach { (sectionTitle, sectionItems) ->
                            item(span = { GridItemSpan(3) }, key = "grid_hdr_$sectionTitle") {
                                SectionHeaderSticky(sectionTitle, "${sectionItems.size} item")
                            }
                            items(sectionItems, key = { it.first.uri.toString() }, contentType = { it.second.mediaType }) { (entry, info) ->
                                val uriKey = entry.uri.toString()
                                val isFav = favoriteMedia.contains(uriKey)
                                val isSel = selectedUris.contains(uriKey)
                                val cloudUpload = cloudByPath[uriKey]
                                val cloudProgress = cloudProgressMap[uriKey]
                                LibGridTile(
                                    entry = entry,
                                    info = info,
                                    isFavorite = isFav,
                                    isSelected = isSel,
                                    multiSelectActive = multiSelectActive,
                                    cloudState = cloudUpload?.state,
                                    onClick = {
                                        if (multiSelectActive) {
                                            if (isSel) selectedUris.remove(uriKey) else selectedUris.add(uriKey)
                                        } else {
                                            if (onOpenMedia != null) onOpenMedia(entry, info.title) else playing = entry
                                        }
                                    },
                                    onLongClick = {
                                        if (!isSel) selectedUris.add(uriKey)
                                    },
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding + 12.dp),
                    ) {
                        if (activeRecordings.isNotEmpty()) {
                            item(key = "active_rec_banner", contentType = "rec_banner") {
                                ActiveRecordingSection(activeRecordings, accounts, recTick)
                            }
                        }
                    grouped.forEach { (sectionTitle, sectionItems) ->
                        stickyHeader(key = "hdr_$sectionTitle") {
                            SectionHeaderSticky(sectionTitle, "${sectionItems.size} item")
                        }
                        items(sectionItems, key = { it.first.uri.toString() }, contentType = { it.second.mediaType }) { (entry, info) ->
                            val uriKey = entry.uri.toString()
                            val isFav = favoriteMedia.contains(uriKey)
                            val isSel = selectedUris.contains(uriKey)
                            val cloudUpload = cloudByPath[uriKey]
                            val cloudProgress = cloudProgressMap[uriKey]
                            LibCard(
                                entry = entry,
                                info = info,
                                isFavorite = isFav,
                                isSelected = isSel,
                                multiSelectActive = multiSelectActive,
                                cloudEnabled = cloudSettings.cloudEnabled,
                                cloudUpload = cloudUpload,
                                cloudProgress = cloudProgress,
                                onClick = {
                                    if (multiSelectActive) {
                                        if (isSel) selectedUris.remove(uriKey) else selectedUris.add(uriKey)
                                    } else {
                                        if (onOpenMedia != null) onOpenMedia(entry, info.title) else playing = entry
                                    }
                                },
                                onLongClick = {
                                    if (!isSel) selectedUris.add(uriKey)
                                },
                                onToggleFavorite = {
                                    scope.launch { container.settings.toggleFavoriteMedia(uriKey) }
                                },
                                onUploadToCloud = {
                                    if (!cloudSettings.cloudEnabled) {
                                        Toast.makeText(context, "Hubungkan Google Drive di Setelan terlebih dahulu.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        scope.launch(Dispatchers.IO) {
                                            val queued = CloudUploadScheduler.enqueue(
                                                context = context,
                                                localPath = uriKey,
                                                displayName = entry.name,
                                                mimeType = entry.mime,
                                                sizeBytes = entry.size,
                                                forceReset = cloudUpload?.state == UploadState.FAILED ||
                                                    cloudUpload?.state == UploadState.NEEDS_RECONNECT ||
                                                    cloudUpload?.state == UploadState.QUOTA_FULL,
                                            )
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    context,
                                                    if (queued) "Mengantrekan ${entry.name} ke Drive…" else "Tidak dapat menjadwalkan upload.",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    }
                                },
                                onShare = {
                                    runCatching {
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = entry.mime
                                            putExtra(Intent.EXTRA_STREAM, entry.uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(send, "Bagikan via"))
                                    }
                                },
                                onMove = {
                                    movingEntry = entry
                                    createDocLauncher.launch(Pair(entry.mime, entry.name))
                                },
                                onInfo = { infoEntry = entry to info },
                                onDelete = { requestDeleteWithUndo(entry, info.title) },
                            )
                        }
                    }
                }
            }
            }
        }
    }

    // Pemutar Media Layar Penuh (Video, Foto, Audio)
    playing?.let { entry ->
        val uriKey = entry.uri.toString()
        DisposableEffect(uriKey) {
            CloudUploadPlaybackGuard.markPlaying(uriKey)
            onDispose { CloudUploadPlaybackGuard.unmarkPlaying(uriKey) }
        }
        val humanTitle = remember(entry, accountByHandle) {
            Humanizer.humanizeEntry(entry, accountByHandle).title
        }
        FullScreenMediaViewer(
            entry = entry,
            humanTitle = humanTitle,
            onDismiss = { playing = null },
        )
    }

    // Dialog Info Berkas (tampilkan nama file mentah, path, dan detail lengkap)
    infoEntry?.let { (entry, info) ->
        AlertDialog(
            onDismissRequest = { infoEntry = null },
            containerColor = Ra.colors.surfaceAlt,
            shape = RoundedCornerShape(22.dp),
            title = { Text("Info Berkas", color = Ra.colors.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(info.title, style = MaterialTheme.typography.titleMedium, color = Ra.colors.sun)
                    InfoRow("Kreator", info.creatorLabel)
                    InfoRow("Platform", info.platformLabel)
                    if (entry.durationMs > 0L) InfoRow("Durasi", formatDuration(entry.durationMs))
                    InfoRow("Ukuran", formatBytes(entry.size))
                    InfoRow(
                        "Tanggal",
                        SimpleDateFormat("EEEE, d MMM yyyy · HH:mm", Locale("id", "ID"))
                            .format(Date(entry.dateModified * 1000L)),
                    )
                    InfoRow("Folder", entry.relativeDir.ifBlank { "Movies/RaKeepsake/" })
                    InfoRow("Nama File Asli", entry.name)
                }
            },
            confirmButton = {
                TextButton(onClick = { infoEntry = null }) {
                    Text("Tutup", color = Ra.colors.sun)
                }
            },
        )
    }

    // Konfirmasi Hapus Massal (Multi-Select)
    if (showBatchDeleteConfirm) {
        val count = selectedUris.size
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            containerColor = Ra.colors.surfaceAlt,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Hapus $count Kenangan?", color = Ra.colors.text) },
            text = {
                Text("Berkas yang dipilih akan dihapus dari penyimpanan perangkat.", color = Ra.colors.textSecondary)
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBatchDeleteConfirm = false
                        val targets = entries.orEmpty().filter { selectedUris.contains(it.uri.toString()) }
                        selectedUris.clear()
                        scope.launch(Dispatchers.IO) {
                            val unowned = mutableListOf<Uri>()
                            for (t in targets) {
                                try {
                                    Storage.deleteEntry(context, t.uri, t.name, t.relativeDir)
                                } catch (_: SecurityException) {
                                    unowned.add(t.uri)
                                } catch (_: Exception) {}
                            }
                            if (unowned.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                val pi = runCatching {
                                    android.provider.MediaStore.createDeleteRequest(context.contentResolver, unowned)
                                }.getOrNull()
                                if (pi != null) {
                                    withContext(Dispatchers.Main) {
                                        intentSenderLauncher.launch(
                                            androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build(),
                                        )
                                    }
                                    return@launch
                                }
                            }
                            syncDeletedPostsWithStorage(context, container)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "$count berkas dihapus", Toast.LENGTH_SHORT).show()
                                container.refreshLibrary()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Ra.colors.danger, contentColor = Color.White),
                ) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) { Text("Batal", color = Ra.colors.textMuted) }
            },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = Ra.colors.surfaceAlt,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Hapus Semua Pustaka?", color = Ra.colors.text) },
            text = {
                Text(
                    "Semua rekaman, postingan, foto, dan audio di Pustaka akan dihapus permanen dari memori HP dan database aplikasi.",
                    color = Ra.colors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        scope.launch(Dispatchers.IO) {
                            val list = entries ?: emptyList()
                            var count = 0
                            val unownedUris = mutableListOf<Uri>()

                            for (e in list) {
                                try {
                                    if (Storage.deleteEntry(context, e.uri, e.name, e.relativeDir)) {
                                        count++
                                    }
                                } catch (sec: SecurityException) {
                                    unownedUris.add(e.uri)
                                } catch (_: Exception) {}
                            }

                            if (unownedUris.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                val pi = runCatching {
                                    android.provider.MediaStore.createDeleteRequest(context.contentResolver, unownedUris)
                                }.getOrNull()
                                if (pi != null) {
                                    withContext(Dispatchers.Main) {
                                        intentSenderLauncher.launch(
                                            androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build(),
                                        )
                                    }
                                    return@launch
                                }
                            }

                            Storage.clearLibrary(context)
                            container.db.posts().markAllDeleted()
                            container.db.recordings().deleteAll()
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "$count berkas dihapus dari Pustaka", Toast.LENGTH_SHORT).show()
                                container.refreshLibrary()
                            }
                        }
                    },
                ) {
                    Text("Hapus Semua", color = Ra.colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Batal", color = Ra.colors.textMuted)
                }
            },
        )
    }
}

@Composable
private fun ActiveRecordingSection(
    recordings: List<LiveStateHolder.RecState>,
    accounts: List<Account>,
    nowMs: Long,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        recordings.forEach { rec ->
            val account = accounts.firstOrNull { it.id == rec.accountId }
                ?: accounts.firstOrNull { it.handle.equals(rec.handle, ignoreCase = true) }
            val displayName = account?.displayName?.takeIf { it.isNotBlank() } ?: "@${rec.handle}"
            val elapsed = if (rec.startedAt > 1_000_000_000_000L) {
                Humanizer.formatElapsedDuration(nowMs - rec.startedAt)
            } else null

            Surface(
                color = Ra.colors.sunDim,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Ra.colors.sun.copy(alpha = 0.65f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { openLivePlatformUrl(context, rec.platform, rec.handle) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(8.dp).background(Ra.colors.sun, CircleShape))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "$displayName sedang merekam",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Ra.colors.sun,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = buildString {
                            append(platformNiceName(rec.platform))
                            if (elapsed != null) append(" · $elapsed")
                            if (rec.sizeBytes > 0L) append(" · ${formatBytes(rec.sizeBytes)}")
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = Ra.colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        onClick = { openLivePlatformUrl(context, rec.platform, rec.handle) },
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, Ra.colors.sun.copy(alpha = 0.55f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 38.dp),
                    ) {
                        Text(
                            "Gabung",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = Ra.colors.sun,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Gabung ke siaran",
                            tint = Ra.colors.sun,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Ra.colors.textMuted)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Ra.colors.text)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibCard(
    entry: Storage.LibEntry,
    info: Humanizer.HumanMediaInfo,
    isFavorite: Boolean,
    isSelected: Boolean,
    multiSelectActive: Boolean,
    cloudEnabled: Boolean,
    cloudUpload: CloudUpload?,
    cloudProgress: CloudUploadProgress?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onUploadToCloud: () -> Unit,
    onShare: () -> Unit,
    onMove: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Ra.colors.sunDim else Ra.colors.surfaceAlt,
        ),
        border = if (isSelected) BorderStroke(1.5.dp, Ra.colors.sun) else BorderStroke(1.dp, Ra.colors.outline.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (multiSelectActive) {
                Icon(
                    imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (isSelected) "Terpilih" else "Pilih",
                    tint = if (isSelected) Ra.colors.sun else Ra.colors.textMuted,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
            }

            // Thumbnail 96dp: frame video / foto / kartu musik hangat
            MediaThumb(
                uri = entry.uri,
                mime = entry.mime,
                durationMs = entry.durationMs,
                isFavorite = isFavorite,
                size = 88.dp,
            )

            Spacer(Modifier.width(12.dp))

            // Judul manusiawi + metadata + badge kategori
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = info.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Ra.colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(info.dateTimeLabel)
                        if (info.versionTag != null) append(" · ${info.versionTag}")
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = Ra.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MediaBadge(info.mediaType)
                    Text(
                        text = formatBytes(entry.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = Ra.colors.textSecondary,
                    )
                    CloudStateIndicator(
                        cloudEnabled = cloudEnabled,
                        state = cloudUpload?.state,
                        progress = cloudProgress,
                    )
                }
            }

            // Aksi di kanan: Bintang Favorit + Menu ⋮ (target sentuh >= 48dp, Hapus jauh dari kartu)
            if (!multiSelectActive) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(48.dp)) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "Menu aksi untuk ${info.title}",
                                tint = Ra.colors.textSecondary,
                            )
                        }
                        if (menuExpanded) {
                            DropdownMenu(
                                expanded = true,
                                onDismissRequest = { menuExpanded = false },
                                containerColor = Ra.colors.surfaceHigh,
                            ) {
                                DropdownMenuItem(
                                    text = { Text(if (entry.mime.startsWith("image/")) "Lihat" else "Putar", color = Ra.colors.text) },
                                    onClick = {
                                        menuExpanded = false
                                        onClick()
                                    },
                                )
                                val uploadState = cloudUpload?.state
                                val canUploadNow = uploadState == null ||
                                    uploadState == UploadState.PENDING ||
                                    uploadState == UploadState.NEEDS_RECONNECT ||
                                    uploadState == UploadState.QUOTA_FULL
                                val canRetryUpload = uploadState == UploadState.FAILED
                                if (canUploadNow || canRetryUpload) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (canRetryUpload) "Coba lagi" else "Upload sekarang",
                                                color = Ra.colors.sun,
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Filled.CloudUpload, null, tint = Ra.colors.sun)
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            onUploadToCloud()
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(if (isFavorite) "Hapus dari Favorit" else "Tandai Favorit ★", color = Ra.colors.sun) },
                                    leadingIcon = {
                                        Icon(
                                            if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                            null,
                                            tint = Ra.colors.sun,
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onToggleFavorite()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Bagikan", color = Ra.colors.text) },
                                    leadingIcon = { Icon(Icons.Filled.Share, null, tint = Ra.colors.textSecondary) },
                                    onClick = {
                                        menuExpanded = false
                                        onShare()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Pindah ke Folder Lain", color = Ra.colors.text) },
                                    leadingIcon = { Icon(Icons.Filled.Folder, null, tint = Ra.colors.textSecondary) },
                                    onClick = {
                                        menuExpanded = false
                                        onMove()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Info Berkas", color = Ra.colors.text) },
                                    leadingIcon = { Icon(Icons.Outlined.Info, null, tint = Ra.colors.textSecondary) },
                                    onClick = {
                                        menuExpanded = false
                                        onInfo()
                                    },
                                )
                                HorizontalDivider(color = Ra.colors.outline)
                                DropdownMenuItem(
                                    text = { Text("Hapus", color = Ra.colors.danger) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = Ra.colors.danger) },
                                    onClick = {
                                        menuExpanded = false
                                        onDelete()
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = onToggleFavorite, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            contentDescription = if (isFavorite) "Favorit" else "Tandai favorit",
                            tint = if (isFavorite) Ra.colors.sun else Ra.colors.textMuted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudStateIndicator(
    cloudEnabled: Boolean,
    state: UploadState?,
    progress: CloudUploadProgress?,
) {
    when (state) {
        null, UploadState.PENDING -> {
            if (cloudEnabled) {
                Icon(
                    imageVector = Icons.Outlined.Cloud,
                    contentDescription = "Belum diunggah",
                    tint = Ra.colors.textMuted,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        UploadState.QUEUED -> {
            Icon(
                imageVector = Icons.Filled.Schedule,
                contentDescription = "Menunggu antrean upload",
                tint = Ra.colors.sun,
                modifier = Modifier.size(15.dp),
            )
        }
        UploadState.UPLOADING -> {
            val pct = if (progress != null && progress.totalBytes > 0L) {
                ((progress.sentBytes * 100L) / progress.totalBytes).toInt().coerceIn(0, 100)
            } else null
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowUpward,
                    contentDescription = "Mengunggah",
                    tint = Ra.colors.sun,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = if (pct != null) "$pct%" else "…",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ra.colors.sun,
                )
            }
        }
        UploadState.DONE -> {
            Icon(
                imageVector = Icons.Filled.CloudDone,
                contentDescription = "Terunggah & terverifikasi",
                tint = Ra.colors.postFg,
                modifier = Modifier.size(15.dp),
            )
        }
        UploadState.FAILED -> {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = "Upload gagal",
                tint = Ra.colors.danger,
                modifier = Modifier.size(15.dp),
            )
        }
        UploadState.NEEDS_RECONNECT -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = "Hubungkan ulang",
                    tint = Ra.colors.danger,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "Hubungkan ulang",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ra.colors.danger,
                    maxLines = 1,
                )
            }
        }
        UploadState.QUOTA_FULL -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = "Drive penuh",
                    tint = Ra.colors.danger,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "Drive penuh",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ra.colors.danger,
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibGridTile(
    entry: Storage.LibEntry,
    info: Humanizer.HumanMediaInfo,
    isFavorite: Boolean,
    isSelected: Boolean,
    multiSelectActive: Boolean,
    cloudState: UploadState? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        MediaThumb(
            uri = entry.uri,
            mime = entry.mime,
            durationMs = entry.durationMs,
            isFavorite = isFavorite,
            size = 160.dp,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Text(
                text = info.creatorLabel,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (multiSelectActive || isSelected) {
            Icon(
                imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) Ra.colors.sun else Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp),
            )
        } else if (cloudState != null && cloudState != UploadState.PENDING) {
            val (icon, tint) = when (cloudState) {
                UploadState.QUEUED -> Icons.Filled.Schedule to Ra.colors.sun
                UploadState.UPLOADING -> Icons.Filled.ArrowUpward to Ra.colors.sun
                UploadState.DONE -> Icons.Filled.CloudDone to Ra.colors.postFg
                UploadState.FAILED, UploadState.NEEDS_RECONNECT, UploadState.QUOTA_FULL -> Icons.Filled.ErrorOutline to Ra.colors.danger
                UploadState.PENDING -> Icons.Outlined.Cloud to Color.White
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                    .padding(4.dp),
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            }
        }
    }
}

private suspend fun syncDeletedPostsWithStorage(context: Context, container: AppContainer) {
    runCatching {
        val posts = container.db.posts().allOnce()
        val entriesRemaining = Storage.queryLibrary(context)
        val remainingNames = entriesRemaining.map { it.name }.toSet()
        val remainingUris = entriesRemaining.map { it.uri.toString() }.toSet()
        for (p in posts) {
            if (p.status == "DELETED") continue
            val rawId = p.postId.removePrefix("tt_${p.author}_").removePrefix("manual_")
            val stillThere = remainingNames.any { it.contains(p.postId) || (rawId.length >= 8 && it.contains(rawId)) } ||
                remainingUris.any { p.filePaths.contains(it) } ||
                remainingNames.any { p.filePaths.isNotBlank() && p.filePaths.contains(it) }
            if (!stillThere) {
                container.db.posts().markDeleted(p.id)
            }
        }
    }
}

private suspend fun performPhysicalDelete(
    context: Context,
    container: AppContainer,
    toDel: Storage.LibEntry,
    intentSenderLauncher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>,
    onDone: () -> Unit,
) {
    var deleteSuccess = false
    var intentSenderToLaunch: android.content.IntentSender? = null

    try {
        deleteSuccess = Storage.deleteEntry(context, toDel.uri, toDel.name, toDel.relativeDir)
    } catch (secEx: SecurityException) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            runCatching {
                val pi = android.provider.MediaStore.createDeleteRequest(context.contentResolver, listOf(toDel.uri))
                intentSenderToLaunch = pi.intentSender
            }
        }
    } catch (e: Exception) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && e is android.app.RecoverableSecurityException) {
            intentSenderToLaunch = e.userAction.actionIntent.intentSender
        }
    }

    val sender = intentSenderToLaunch
    if (sender != null) {
        withContext(Dispatchers.Main) {
            intentSenderLauncher.launch(
                androidx.activity.result.IntentSenderRequest.Builder(sender).build(),
            )
        }
    } else if (deleteSuccess) {
        runCatching {
            val posts = container.db.posts().allOnce()
            for (p in posts) {
                val rawId = p.postId.removePrefix("tt_${p.author}_").removePrefix("manual_")
                val match = toDel.name.contains(p.postId) ||
                    (rawId.length >= 8 && toDel.name.contains(rawId)) ||
                    (p.filePaths.isNotBlank() && (p.filePaths.contains(toDel.name) || p.filePaths.contains(toDel.uri.toString())))
                if (match) {
                    container.db.posts().markDeleted(p.id)
                }
            }
        }
        withContext(Dispatchers.Main) { onDone() }
    } else {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val pi = runCatching {
                android.provider.MediaStore.createDeleteRequest(context.contentResolver, listOf(toDel.uri))
            }.getOrNull()
            if (pi != null) {
                withContext(Dispatchers.Main) {
                    intentSenderLauncher.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build(),
                    )
                }
                return
            }
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Gagal menghapus berkas '${toDel.name}'", Toast.LENGTH_SHORT).show()
            onDone()
        }
    }
}

/** Pemutar media layar penuh (Full Screen Media Viewer) di window utama agar safeDrawing insets akurat lintas perangkat. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun FullScreenMediaViewer(
    entry: Storage.LibEntry,
    humanTitle: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    BackHandler { onDismiss() }

    val uriKey = entry.uri.toString()
    DisposableEffect(uriKey) {
        CloudUploadPlaybackGuard.markPlaying(uriKey)
        onDispose { CloudUploadPlaybackGuard.unmarkPlaying(uriKey) }
    }

    val openExternal = {
        runCatching {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(entry.uri, entry.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(viewIntent, "Buka dengan aplikasi lain"))
        }.onFailure {
            Toast.makeText(context, "Tidak dapat membuka aplikasi eksternal", Toast.LENGTH_SHORT).show()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {},
    ) {
        when {
            entry.mime.startsWith("image/") -> {
                AsyncImage(
                    model = entry.uri,
                    contentDescription = humanTitle,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .align(Alignment.Center),
                )
            }
            entry.mime.startsWith("audio/") -> {
                val player = remember(entry.uri) {
                    buildLocalMediaPlayer(context, entry.uri)
                }
                DisposableEffect(player) {
                    onDispose { player.release() }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Audiotrack,
                        contentDescription = "Audio",
                        tint = Ra.colors.sun,
                        modifier = Modifier.size(96.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        humanTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${if (entry.durationMs > 0) formatDuration(entry.durationMs) + " · " else ""}${formatBytes(entry.size)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.LightGray,
                    )
                    Spacer(Modifier.height(32.dp))
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                this.player = player
                                useController = true
                                controllerShowTimeoutMs = 0
                                setShowNextButton(false)
                                setShowPreviousButton(false)
                                setShowFastForwardButton(true)
                                setShowRewindButton(true)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                    )
                }
            }
            else -> {
                val player = remember(entry.uri) {
                    buildLocalMediaPlayer(context, entry.uri)
                }
                var isBuffering by remember(entry.uri) { mutableStateOf(true) }
                var playerErrorMsg by remember(entry.uri) { mutableStateOf<String?>(null) }

                DisposableEffect(player) {
                    val listener = object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            isBuffering = playbackState == Player.STATE_BUFFERING
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            isBuffering = false
                            playerErrorMsg = error.localizedMessage ?: "Pemutar internal tidak dapat memutar berkas ini."
                        }
                    }
                    player.addListener(listener)
                    onDispose {
                        player.removeListener(listener)
                        player.release()
                    }
                }
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            keepScreenOn = true
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            useController = true
                            controllerShowTimeoutMs = 3500
                            controllerHideOnTouch = true
                            setShowNextButton(false)
                            setShowPreviousButton(false)
                            setShowFastForwardButton(true)
                            setShowRewindButton(true)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .padding(bottom = 10.dp),
                )

                if (isBuffering && playerErrorMsg == null) {
                    CircularProgressIndicator(
                        color = Ra.colors.sun,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                playerErrorMsg?.let { err ->
                    Surface(
                        color = Ra.colors.surfaceAlt.copy(alpha = 0.94f),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, Ra.colors.outline),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "Video gagal diputar di pemutar internal",
                                style = MaterialTheme.typography.titleMedium,
                                color = Ra.colors.text,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                err,
                                style = MaterialTheme.typography.bodySmall,
                                color = Ra.colors.textSecondary,
                                textAlign = TextAlign.Center,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = {
                                    playerErrorMsg = null
                                    isBuffering = true
                                    player.prepare()
                                    player.playWhenReady = true
                                }) {
                                    Text("Coba lagi", color = Ra.colors.text)
                                }
                                Button(
                                    onClick = { openExternal() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Ra.colors.sun,
                                        contentColor = Ra.colors.onSun,
                                    ),
                                ) {
                                    Text("Buka Eksternal")
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent),
                    ),
                )
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Kembali",
                    tint = Color.White,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = humanTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${if (entry.durationMs > 0) formatDuration(entry.durationMs) + " · " else ""}${formatBytes(entry.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray,
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { openExternal() },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Buka di Aplikasi Eksternal",
                    tint = Color.White,
                )
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun buildLocalMediaPlayer(context: Context, uri: Uri): ExoPlayer {
    // Buffer ringan untuk berkas lokal agar pemutaran & seek video live besar tidak membebani RAM/storage penuh
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(2_500, 8_000, 250, 1_000)
        .setTargetBufferBytes(8 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()
    val audioAttrs = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()
    return ExoPlayer.Builder(context)
        .setLoadControl(loadControl)
        .build()
        .apply {
            setSeekParameters(SeekParameters.CLOSEST_SYNC)
            setAudioAttributes(audioAttrs, true)
            setHandleAudioBecomingNoisy(true)
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
}
