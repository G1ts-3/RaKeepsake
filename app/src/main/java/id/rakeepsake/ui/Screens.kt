package id.rakeepsake.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.rakeepsake.data.Account
import id.rakeepsake.data.AppContainer
import id.rakeepsake.data.Post
import id.rakeepsake.net.TiktokResolver
import id.rakeepsake.util.MediaType
import id.rakeepsake.util.Storage
import id.rakeepsake.work.DownloadProgress
import id.rakeepsake.work.DownloadProgressHolder
import id.rakeepsake.work.PostWatchWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ======================= POSTINGAN (tab 0) =======================

@Composable
fun PostScreen(
    container: AppContainer,
    modifier: Modifier,
    pendingLink: String?,
    onLinkConsumed: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    var link by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val settings by container.settingsFlow.collectAsState()
    val audioOnly = settings.audioOnly
    var postFilter by rememberSaveable { mutableStateOf("Semua") }
    var showHeaderMenu by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var clipboardSuggestion by remember { mutableStateOf<String?>(null) }

    if (pendingLink != null && link.isBlank()) {
        link = pendingLink
        onLinkConsumed()
    }

    // Periksa papan klip sekali untuk banner saran tempel otomatis (Audit §4.5 #2)
    LaunchedEffect(Unit) {
        if (link.isBlank()) {
            runCatching {
                val clip = clipboardManager.getText()?.text?.trim()
                if (!clip.isNullOrBlank() && clip.contains("tiktok.com")) {
                    clipboardSuggestion = clip
                }
            }
        }
    }

    val posts by container.recentPostsFlow.collectAsState()
    val accounts by container.accountsFlow.collectAsState()
    val accountByHandle = remember(accounts) { accounts.associateBy { it.handle.lowercase() } }
    val progressMap by DownloadProgressHolder.progress.collectAsState()
    val batchProgress by DownloadProgressHolder.batch.collectAsState()
    val activePosts = remember(posts) { posts.filter { it.status == "DOWNLOADING" || it.status == "QUEUED" } }
    val showDownloadBanner = activePosts.isNotEmpty() || progressMap.isNotEmpty()

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth()) {
            ScreenHeader(
                title = "Postingan",
                subtitle = "Unduh tanpa watermark",
                actionIcon = Icons.Filled.Refresh,
                actionDesc = "Segarkan & cek akun",
                onAction = {
                    message = null
                    scope.launch(Dispatchers.IO) {
                        runCatching { PostWatchWorker.checkAccounts(container.appContext, container) }
                    }
                    Toast.makeText(context, "Memeriksa postingan & story baru...", Toast.LENGTH_SHORT).show()
                },
                secondActionIcon = if (posts.isNotEmpty()) Icons.Filled.MoreVert else null,
                secondActionDesc = "Opsi riwayat",
                onSecondAction = if (posts.isNotEmpty()) { { showHeaderMenu = true } } else null,
            )
            if (showHeaderMenu) {
                Box(Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 64.dp)) {
                    DropdownMenu(
                        expanded = true,
                        onDismissRequest = { showHeaderMenu = false },
                        containerColor = Ra.colors.surfaceHigh,
                    ) {
                        DropdownMenuItem(
                            text = { Text("Bersihkan Riwayat Unduhan", color = Ra.colors.danger) },
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

        // Banner tempel otomatis dari papan klip
        clipboardSuggestion?.let { clipUrl ->
            if (link.isBlank()) {
                Surface(
                    color = Ra.colors.sunDim,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Ra.colors.sun.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.ContentPaste, null, tint = Ra.colors.sun, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Tautan TikTok terdeteksi di papan klip",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ra.colors.text,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = {
                                link = clipUrl
                                clipboardSuggestion = null
                            },
                            modifier = Modifier.heightIn(min = 42.dp),
                        ) {
                            Text("Tempel", color = Ra.colors.sun, style = MaterialTheme.typography.labelLarge)
                        }
                        IconButton(onClick = { clipboardSuggestion = null }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.Close, "Tutup saran", tint = Ra.colors.textMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        // Kolom tempel + tombol Unduh (Outlined halus saat kosong, Emas penuh saat valid - Audit §4.5 #1)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Tempel tautan TikTok…", color = Ra.colors.textMuted) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    trailingIcon = {
                        if (link.isNotBlank()) {
                            IconButton(onClick = { link = "" }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Filled.Close, "Hapus tautan", tint = Ra.colors.textSecondary)
                            }
                        } else {
                            TextButton(
                                onClick = {
                                    val clip = clipboardManager.getText()?.text?.trim()
                                    if (!clip.isNullOrBlank()) {
                                        link = clip
                                    }
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text("Tempel", style = MaterialTheme.typography.labelLarge, color = Ra.colors.sun)
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
                )

                val hasLink = link.isNotBlank()
                if (hasLink) {
                    Button(
                        onClick = {
                            val u = TiktokResolver.extractUrl(link)
                            if (!u.contains("tiktok.com", ignoreCase = true)) {
                                message = "Tautan ini belum didukung."
                                return@Button
                            }
                            val initAuthor = TiktokResolver.extractAuthorHandle(u) ?: "tiktok"
                            val initType = if (u.contains("/story/", ignoreCase = true)) "STORY" else "VIDEO"
                            val newPostId = "manual_${System.currentTimeMillis()}"
                            scope.launch(Dispatchers.IO) {
                                container.db.posts().insert(
                                    Post(
                                        postId = newPostId,
                                        type = initType,
                                        caption = u.take(200),
                                        author = initAuthor,
                                        status = "QUEUED",
                                        sourceUrl = u,
                                    ),
                                )
                                PostWatchWorker.enqueueDownload(
                                    container.appContext,
                                    postId = newPostId,
                                    url = u,
                                    author = initAuthor,
                                    caption = "",
                                    type = initType,
                                    audioOnly = audioOnly,
                                    sourceUrl = u,
                                )
                            }
                            message = if (audioOnly) "Unduhan media & audio dimulai." else "Unduhan dimulai."
                            link = ""
                            clipboardSuggestion = null
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Ra.colors.sun,
                            contentColor = Ra.colors.onSun,
                        ),
                        modifier = Modifier.height(54.dp),
                    ) { Text("Unduh", style = MaterialTheme.typography.labelLarge) }
                } else {
                    OutlinedButton(
                        enabled = false,
                        onClick = {},
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Ra.colors.outline.copy(alpha = 0.6f)),
                        modifier = Modifier.height(54.dp),
                    ) {
                        Text("Unduh", style = MaterialTheme.typography.labelLarge, color = Ra.colors.textMuted)
                    }
                }
            }

            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Ra.colors.sun)
            }
        }

        // Filter chips & Riwayat
        val storyCount = posts.count { it.type == "STORY" }
        val feedCount = posts.count { it.type != "STORY" }
        val filteredPosts = remember(posts, postFilter) {
            when (postFilter) {
                "Story" -> posts.filter { it.type == "STORY" }
                "Postingan" -> posts.filter { it.type != "STORY" }
                else -> posts
            }
        }

        val postListState = rememberLazyListState()
        val topPostId = filteredPosts.firstOrNull()?.id
        var lastTopPostId by remember { mutableStateOf<Long?>(null) }
        LaunchedEffect(topPostId) {
            if (lastTopPostId != null && topPostId != null && topPostId != lastTopPostId) {
                postListState.scrollToItem(0)
            }
            lastTopPostId = topPostId
        }
        ScrollCollapsibleBody(
            canCollapse = { hdrPx -> (filteredPosts.isNotEmpty() || showDownloadBanner) && postListState.hasRealOverflow(hdrPx) },
            resetKey = listOf(postFilter, posts.size),
            onAutoResetScroll = {
                if (postListState.firstVisibleItemIndex > 0 || postListState.firstVisibleItemScrollOffset > 0) {
                    postListState.animateScrollToItem(0)
                }
            },
            modifier = Modifier.weight(1f),
            header = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Download Audio juga",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Ra.colors.textSecondary,
                            )
                            Text(
                                "Simpan berkas audio (.m4a) selain video/foto",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = Ra.colors.textMuted,
                            )
                        }
                        RaToggle(checked = audioOnly, labelDesc = "Download Audio juga") { on ->
                            scope.launch { container.settings.setAudioOnly(on) }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RaChip("Semua (${posts.size})", active = postFilter == "Semua") { postFilter = "Semua" }
                        RaChip("Story ($storyCount)", active = postFilter == "Story") { postFilter = "Story" }
                        RaChip("Postingan ($feedCount)", active = postFilter == "Postingan") { postFilter = "Postingan" }
                    }
                    Spacer(Modifier.height(2.dp))
                }
            },
        ) { bottomPadding ->
            if (filteredPosts.isEmpty() && !showDownloadBanner) {
                Box(Modifier.fillMaxSize().padding(bottom = bottomPadding), contentAlignment = Alignment.Center) {
                    EmptyState(
                        if (postFilter != "Semua") "Tidak ada item untuk filter ini."
                        else "Belum ada unduhan.\nTempel tautan TikTok di atas untuk mulai menyimpan.",
                    )
                }
            } else {
                LazyColumn(
                    state = postListState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding + 12.dp),
                ) {
                    if (showDownloadBanner) {
                        item(key = "dl_batch_banner", contentType = "dl_banner") {
                            DownloadSummaryBanner(
                                activeCount = activePosts.size,
                                batchCompleted = batchProgress.completed,
                                batchTotal = maxOf(batchProgress.total, activePosts.size),
                                current = progressMap.values.firstOrNull(),
                            )
                        }
                    }
                items(filteredPosts, key = { it.id }, contentType = { "post" }) { post ->
                    PostCard(
                        post = post,
                        authorAccount = accountByHandle[post.author.lowercase()],
                        progress = progressMap[post.postId],
                        onRetry = if (post.status == "FAILED") {
                            {
                                val rawPostId = post.postId.removePrefix("tt_${post.author}_")
                                val isStory = post.type == "STORY" || post.caption.contains("story", ignoreCase = true)
                                val url = post.sourceUrl.ifBlank {
                                    when {
                                        post.caption.contains("tiktok.com", ignoreCase = true) -> TiktokResolver.extractUrl(post.caption)
                                        !post.postId.startsWith("manual_") && rawPostId.all { it.isDigit() } && rawPostId.isNotBlank() ->
                                            if (isStory) "https://www.tiktok.com/@${post.author}/story/$rawPostId"
                                            else "https://www.tiktok.com/@${post.author}/video/$rawPostId"
                                        else -> ""
                                    }
                                }
                                if (url.isBlank()) {
                                    Toast.makeText(context, "Tautan asli tidak tersimpan, silakan tempel ulang di atas", Toast.LENGTH_SHORT).show()
                                } else {
                                    scope.launch(Dispatchers.IO) {
                                        container.db.posts().setStatus(post.postId, "QUEUED")
                                        PostWatchWorker.enqueueDownload(
                                            container.appContext,
                                            postId = post.postId,
                                            url = url,
                                            author = post.author,
                                            caption = post.caption,
                                            accountId = post.accountId,
                                            type = post.type,
                                            ext = if (post.type == "PHOTO") "jpg" else "mp4",
                                            audioOnly = audioOnly,
                                            sourceUrl = url,
                                        )
                                    }
                                }
                            }
                        } else null,
                        onDelete = {
                            scope.launch(Dispatchers.IO) {
                                val paths = post.filePaths.split("\n").filter { it.isNotBlank() }
                                for (p in paths) {
                                    runCatching {
                                        val uri = Uri.parse(p)
                                        if (uri.scheme == "content") {
                                            Storage.deleteEntry(container.appContext, uri)
                                        } else if (File(p).exists()) {
                                            File(p).delete()
                                        }
                                    }
                                }
                                container.db.posts().markDeleted(post.id)
                                container.refreshLibrary()
                            }
                        },
                    )
                }
            }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = Ra.colors.surfaceAlt,
            shape = RoundedCornerShape(24.dp),
            title = {
                Text("Bersihkan Riwayat Postingan?", style = MaterialTheme.typography.titleLarge, color = Ra.colors.text)
            },
            text = {
                Text(
                    "Catatan riwayat (${posts.size} item) akan dihapus dari daftar ini. Berkas yang sudah tersimpan di Pustaka tetap aman.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ra.colors.textSecondary,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            container.db.posts().markAllDeleted()
                        }
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Ra.colors.danger, contentColor = Ra.colors.onSun),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Bersihkan Riwayat")
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
private fun DownloadSummaryBanner(
    activeCount: Int,
    batchCompleted: Int,
    batchTotal: Int,
    current: DownloadProgress?,
) {
    val total = maxOf(batchTotal, activeCount).coerceAtLeast(1)
    val done = batchCompleted.coerceIn(0, total)
    val headline = when {
        total > 1 -> "Mengunduh $done/$total item selesai"
        current != null && current.totalFiles > 1 && current.currentFile > 0 ->
            "Mengunduh berkas ${current.currentFile}/${current.totalFiles}"
        else -> current?.stage ?: "Mengunduh media…"
    }
    val detailRight = when {
        current != null && current.currentFile > 0 -> {
            val filePart = if (total > 1 && current.totalFiles > 1) "Berkas ${current.currentFile}/${current.totalFiles} · " else ""
            filePart + formatProgressMetric(current)
        }
        total > 1 -> "$activeCount aktif"
        else -> "Menunggu…"
    }

    Surface(
        color = Ra.colors.sunDim,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Ra.colors.sun.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(Ra.colors.sun, CircleShape))
                Text(
                    headline,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Ra.colors.sun,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detailRight,
                    style = MaterialTheme.typography.labelMedium,
                    color = Ra.colors.textSecondary,
                )
            }
            val fileFraction = if (current != null && current.totalBytes > 0L) {
                (current.bytesRead.toFloat() / current.totalBytes.toFloat()).coerceIn(0f, 1f)
            } else if (total > 1) {
                done.toFloat() / total.toFloat()
            } else -1f

            if (fileFraction >= 0f) {
                LinearProgressIndicator(
                    progress = { fileFraction },
                    color = Ra.colors.sun,
                    trackColor = Ra.colors.surfaceHigh,
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(99.dp)),
                )
            } else {
                LinearProgressIndicator(
                    color = Ra.colors.sun,
                    trackColor = Ra.colors.surfaceHigh,
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(99.dp)),
                )
            }
        }
    }
}

private fun formatProgressMetric(p: DownloadProgress): String {
    if (p.totalBytes > 0L) {
        val pct = ((p.bytesRead * 100L) / p.totalBytes).coerceIn(0L, 100L)
        return "$pct% (${formatBytes(p.bytesRead)} / ${formatBytes(p.totalBytes)})"
    }
    return if (p.bytesRead > 0L) formatBytes(p.bytesRead) else p.stage
}

@Composable
private fun PostCard(
    post: Post,
    authorAccount: Account?,
    progress: DownloadProgress? = null,
    onRetry: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val savedFileCount = remember(post.filePaths) {
        post.filePaths.split("\n").count { it.startsWith("content://") }
    }

    val firstMediaUri = remember(post.filePaths) {
        post.filePaths.split("\n")
            .firstOrNull { it.startsWith("content://") }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }
    val guessMime = remember(post.type, post.filePaths, firstMediaUri) {
        val uriStr = firstMediaUri?.toString().orEmpty()
        when {
            post.type == "AUDIO" || uriStr.contains("/audio/") -> "audio/mp4"
            post.type == "PHOTO" || uriStr.contains("/images/") || post.filePaths.contains(".jpg", true) -> "image/jpeg"
            post.type == "VIDEO" || post.type == "STORY" || uriStr.contains("/video/") || post.filePaths.contains(".mp4", true) -> "video/mp4"
            post.filePaths.contains(".m4a", true) -> "audio/mp4"
            else -> "video/mp4"
        }
    }
    val badgeType = when {
        post.type == "STORY" -> MediaType.STORY
        post.type == "PHOTO" -> MediaType.PHOTO
        post.type == "AUDIO" -> MediaType.AUDIO
        else -> MediaType.POST
    }
    val displayAuthor = authorAccount?.displayName?.takeIf { it.isNotBlank() } ?: "@${post.author}"
    val cleanCaption = remember(post.caption, post.author) {
        val c = post.caption.trim()
        if (c.equals("Story @${post.author}", true) || c.equals("Postingan @${post.author}", true)) ""
        else c
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Ra.colors.surfaceAlt),
        border = BorderStroke(
            width = 1.dp,
            color = if (post.status == "FAILED") Ra.colors.danger.copy(alpha = 0.5f) else Ra.colors.outline.copy(alpha = 0.35f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MediaThumb(
                    uri = firstMediaUri,
                    mime = guessMime,
                    durationMs = 0L,
                    size = 64.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            displayAuthor,
                            style = MaterialTheme.typography.titleMedium,
                            color = Ra.colors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (post.status == "DONE") {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Selesai diunduh",
                                tint = Ra.colors.postFg,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }

                    if (cleanCaption.isNotBlank()) {
                        Text(
                            cleanCaption,
                            style = MaterialTheme.typography.bodySmall,
                            color = Ra.colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MediaBadge(badgeType)
                        if (savedFileCount > 1 && post.status == "DONE") {
                            Text(
                                "$savedFileCount berkas",
                                style = MaterialTheme.typography.labelMedium,
                                color = Ra.colors.sun,
                            )
                        }
                        if (post.downloadedAt > 0L) {
                            val dateLabel = remember(post.downloadedAt) {
                                postDateFormatter.format(Date(post.downloadedAt))
                            }
                            Text(
                                dateLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = Ra.colors.textMuted,
                            )
                        }
                    }
                }

                if (onDelete != null) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.MoreVert, "Menu item", tint = Ra.colors.textSecondary)
                        }
                        if (menuExpanded) {
                            DropdownMenu(
                                expanded = true,
                                onDismissRequest = { menuExpanded = false },
                                containerColor = Ra.colors.surfaceHigh,
                            ) {
                                if (onRetry != null) {
                                    DropdownMenuItem(
                                        text = { Text("Coba Unduh Lagi", color = Ra.colors.sun) },
                                        leadingIcon = { Icon(Icons.Filled.Refresh, null, tint = Ra.colors.sun) },
                                        onClick = {
                                            menuExpanded = false
                                            onRetry()
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Hapus dari Riwayat", color = Ra.colors.danger) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = Ra.colors.danger) },
                                    onClick = {
                                        menuExpanded = false
                                        onDelete()
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (post.status == "DOWNLOADING" || post.status == "QUEUED" || progress != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val p = progress
                    if (p != null && p.totalBytes > 0L) {
                        LinearProgressIndicator(
                            progress = { (p.bytesRead.toFloat() / p.totalBytes.toFloat()).coerceIn(0f, 1f) },
                            color = Ra.colors.sun,
                            trackColor = Ra.colors.surfaceHigh,
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(99.dp)),
                        )
                    } else {
                        LinearProgressIndicator(
                            color = Ra.colors.sun,
                            trackColor = Ra.colors.surfaceHigh,
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(99.dp)),
                        )
                    }
                    val statusLabel = when {
                        p != null && p.currentFile > 0 ->
                            "Mengunduh berkas ${p.currentFile}/${p.totalFiles} · ${formatProgressMetric(p)}"
                        p != null -> p.stage
                        post.status == "DOWNLOADING" -> "Mengunduh media tanpa watermark…"
                        else -> "Dalam antrean…"
                    }
                    Text(
                        statusLabel,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = Ra.colors.sun,
                    )
                }
            }

            if (post.status == "FAILED") {
                if (!post.error.isNullOrBlank()) {
                    val isUnavailable = post.error.contains("dihapus", ignoreCase = true) ||
                        post.error.contains("privat", ignoreCase = true) ||
                        post.error.contains("tidak tersedia", ignoreCase = true)
                    Text(
                        text = post.error,
                        color = if (isUnavailable) Ra.colors.textSecondary else Ra.colors.danger,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onRetry != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(
                            onClick = onRetry,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Ra.colors.sun.copy(alpha = 0.6f)),
                            modifier = Modifier.heightIn(min = 42.dp),
                        ) {
                            Text("Coba Lagi", style = MaterialTheme.typography.labelLarge, color = Ra.colors.sun)
                        }
                    }
                }
            }
        }
    }
}

private val postDateFormatter by lazy { SimpleDateFormat("dd MMM · HH:mm", Locale("id", "ID")) }

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}

internal fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val sec = totalSec % 60
    val min = (totalSec / 60) % 60
    val hour = totalSec / 3600
    return if (hour > 0) {
        "%d:%02d:%02d".format(hour, min, sec)
    } else {
        "%02d:%02d".format(min, sec)
    }
}
