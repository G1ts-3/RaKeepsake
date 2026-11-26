package id.rakeepsake.ui

import android.animation.ValueAnimator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import id.rakeepsake.R
import id.rakeepsake.data.AppContainer
import id.rakeepsake.monitor.LiveStateHolder
import id.rakeepsake.net.AppUpdater
import id.rakeepsake.util.Storage
import kotlinx.coroutines.delay

/**
 * Scaffold utama RaKeepsake:
 * - Memakai `SaveableStateHolder` + `when (tab)` sehingga HANYA 1 pohon layar aktif di GPU/UI Thread
 *   (memangkas overdraw & beban layout 5x lipat) sambil mempertahankan scroll, filter, & input per tab.
 * - State hangat (akun, pustaka, postingan, pengaturan) di-cache di `AppContainer` sehingga pindah tab instan (0 ms).
 * - Lencana navbar diisolasi di `RaNavBarContainer` agar tick tidak memicu rekomposisi layar konten.
 */
@Composable
fun RaScaffold(
    container: AppContainer,
    initialTab: Int,
    pendingLink: String?,
    onLinkConsumed: () -> Unit,
    onRequestNotif: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(initialTab.coerceIn(0, 4)) }
    var visitedMask by remember { mutableIntStateOf(1 shl initialTab.coerceIn(0, 4)) }
    LaunchedEffect(initialTab) {
        val next = initialTab.coerceIn(0, 4)
        visitedMask = visitedMask or (1 shl next)
        tab = next
    }

    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    var mediaViewerPayload by remember { mutableStateOf<Pair<Storage.LibEntry, String>?>(null) }

    val onNavigateToAccounts = remember {
        {
            visitedMask = visitedMask or (1 shl 3)
            tab = 3
        }
    }
    val onSelectTab: (Int) -> Unit = remember(focusManager) {
        { next ->
            visitedMask = visitedMask or (1 shl next)
            if (tab != next) {
                focusManager.clearFocus()
                tab = next
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Ra.colors.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ),
        ) {
            Box(Modifier.weight(1f)) {
                if ((visitedMask and (1 shl 0)) != 0) {
                    val active = tab == 0
                    Box(tabVisibilityModifier(active)) {
                        PostScreen(container, Modifier, pendingLink, onLinkConsumed, snackbarHostState)
                    }
                }
                if ((visitedMask and (1 shl 1)) != 0) {
                    val active = tab == 1
                    Box(tabVisibilityModifier(active)) {
                        LibraryScreen(
                            container = container,
                            modifier = Modifier,
                            snackbarHostState = snackbarHostState,
                            isActiveTab = active,
                            onOpenMedia = { entry, title -> mediaViewerPayload = entry to title },
                        )
                    }
                }
                if ((visitedMask and (1 shl 2)) != 0) {
                    val active = tab == 2
                    Box(tabVisibilityModifier(active)) {
                        LiveScreen(
                            container = container,
                            modifier = Modifier,
                            isActiveTab = active,
                            onNavigateToAccounts = onNavigateToAccounts,
                        )
                    }
                }
                if ((visitedMask and (1 shl 3)) != 0) {
                    val active = tab == 3
                    Box(tabVisibilityModifier(active)) {
                        AccountsScreen(container, Modifier)
                    }
                }
                if ((visitedMask and (1 shl 4)) != 0) {
                    val active = tab == 4
                    Box(tabVisibilityModifier(active)) {
                        SettingsScreen(container, Modifier, onRequestNotif, isActiveTab = active)
                    }
                }
                if (visitedMask != (1 shl tab)) {
                    // Blokir sentuhan agar area kosong pada tab aktif tidak tembus ke tab tidak aktif di zIndex 0
                    Box(
                        Modifier
                            .fillMaxSize()
                            .zIndex(0.5f)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                                        ev.changes.forEach { it.consume() }
                                    }
                                }
                            },
                    )
                }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = Ra.colors.surfaceHigh,
                        contentColor = Ra.colors.text,
                        actionColor = Ra.colors.sun,
                        shape = RoundedCornerShape(14.dp),
                    )
                }
            }

            RaUpdateBannerContainer(
                container = container,
                currentTab = tab,
                onOpenSettingsUpdate = {
                    container.updater.requestOpenDialog()
                    onSelectTab(4)
                },
            )

            RaNavBarContainer(
                container = container,
                current = tab,
                onSelect = onSelectTab,
            )
        }

        mediaViewerPayload?.let { (entry, title) ->
            FullScreenMediaViewer(
                entry = entry,
                humanTitle = title,
                onDismiss = { mediaViewerPayload = null },
                modifier = Modifier.fillMaxSize().zIndex(50f),
            )
        }
    }
}

private fun tabVisibilityModifier(active: Boolean): Modifier =
    if (active) {
        Modifier.fillMaxSize().zIndex(1f)
    } else {
        Modifier
            .fillMaxSize()
            .zIndex(0f)
            .drawWithContent { /* jangan gambar tab yang tidak aktif */ }
            .clearAndSetSemantics {}
    }

/** Banner pengingat ringan di atas navbar jika versi baru terdeteksi dan belum ditutup. */
@Composable
private fun RaUpdateBannerContainer(
    container: AppContainer,
    currentTab: Int,
    onOpenSettingsUpdate: () -> Unit,
) {
    val updateState by container.updater.state.collectAsState()
    var dismissedTag by remember { mutableStateOf<String?>(null) }

    val bannerInfo: Pair<String, String>? = when (val st = updateState) {
        is AppUpdater.UpdateState.Available -> st.info.tagName to "Versi baru v${st.info.versionName} tersedia"
        is AppUpdater.UpdateState.ReadyToInstall -> st.info.tagName to "Pembaruan v${st.info.versionName} siap dipasang"
        is AppUpdater.UpdateState.Downloading -> {
            val pct = if (st.totalBytes > 0L) ((st.downloadedBytes * 100L) / st.totalBytes).coerceIn(0L, 100L) else 0L
            st.info.tagName to "Mengunduh v${st.info.versionName} ($pct%)…"
        }
        else -> null
    }

    if (currentTab != 4 && bannerInfo != null && dismissedTag != bannerInfo.first) {
        Surface(
            color = Ra.colors.surfaceHigh,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Ra.colors.sun.copy(alpha = 0.5f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = Ra.colors.sun, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = bannerInfo.second,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = Ra.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onOpenSettingsUpdate,
                    colors = ButtonDefaults.buttonColors(containerColor = Ra.colors.sun, contentColor = Ra.colors.onSun),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.heightIn(min = 36.dp),
                ) {
                    Text(
                        text = if (updateState is AppUpdater.UpdateState.ReadyToInstall) "Pasang" else "Update",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    )
                }
                IconButton(
                    onClick = { dismissedTag = bannerInfo.first },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Tutup pengingat", tint = Ra.colors.textMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** Kontainer terisolasi untuk lencana navbar agar pembaruan status live/unduh tidak memicu rekomposisi layar konten. */
@Composable
private fun RaNavBarContainer(
    container: AppContainer,
    current: Int,
    onSelect: (Int) -> Unit,
) {
    val recentPosts by container.recentPostsFlow.collectAsState()
    val updateState by container.updater.state.collectAsState()
    val activeDownloadCount = remember(recentPosts) {
        recentPosts.count { it.status == "DOWNLOADING" || it.status == "QUEUED" }
    }
    val hasUpdateBadge = remember(updateState) {
        updateState is AppUpdater.UpdateState.Available ||
            updateState is AppUpdater.UpdateState.ReadyToInstall ||
            updateState is AppUpdater.UpdateState.Downloading
    }
    val liveTick by produceState(0L) {
        while (true) {
            delay(2500L)
            value = System.currentTimeMillis()
        }
    }
    val liveActiveCount = remember(liveTick, LiveStateHolder.recording.size, LiveStateHolder.liveNow.size) {
        (LiveStateHolder.recording.keys + LiveStateHolder.liveNow.keys).toSet().size
    }

    RaNavBar(
        current = current,
        liveBadge = liveActiveCount,
        downloadBadge = activeDownloadCount,
        updateBadge = hasUpdateBadge,
        onSelect = onSelect,
    )
}

private data class TabSpec(
    val label: String,
    val iconRes: Int,
)

@Composable
fun RaNavBar(
    current: Int,
    liveBadge: Int,
    downloadBadge: Int = 0,
    updateBadge: Boolean = false,
    onSelect: (Int) -> Unit,
) {
    val tabs = remember {
        listOf(
            TabSpec("Live", R.drawable.ic_nav_live),
            TabSpec("Postingan", R.drawable.ic_nav_postingan),
            TabSpec("Pustaka", R.drawable.ic_nav_pustaka),
            TabSpec("Akun", R.drawable.ic_nav_akun),
            TabSpec("Setelan", R.drawable.ic_nav_setelan),
        )
    }
    val screenIndex = remember { listOf(2, 0, 1, 3, 4) }

    val animatorsEnabled = remember { runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true) }
    val pulseAlpha = if (liveBadge > 0 && animatorsEnabled) {
        val t = rememberInfiniteTransition(label = "nav_live_pulse")
        val a by t.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "nav_pulse_alpha",
        )
        a
    } else 1f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp)
            .padding(WindowInsets.navigationBars.asPaddingValues()),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = Ra.colors.navBg,
            shape = CircleShape,
            border = BorderStroke(1.dp, Color(0xFF322822)),
            shadowElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { i, spec ->
                    val active = current == screenIndex[i]
                    val itemColor by animateColorAsState(
                        targetValue = if (active) Ra.colors.sun else Color(0xFFEDE0DA),
                        animationSpec = tween(durationMillis = 80),
                        label = "nav_color_$i",
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                onSelect(screenIndex[i])
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(spec.iconRes),
                                contentDescription = spec.label,
                                tint = itemColor,
                                modifier = Modifier.size(22.dp),
                            )
                            if (i == 0 && liveBadge > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 9.dp, y = (-4).dp)
                                        .alpha(pulseAlpha)
                                        .background(Ra.colors.live, CircleShape)
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        text = "$liveBadge",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                    )
                                }
                            } else if (i == 1 && downloadBadge > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 9.dp, y = (-4).dp)
                                        .background(Ra.colors.sun, CircleShape)
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        text = "$downloadBadge",
                                        color = Ra.colors.onSun,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                    )
                                }
                            } else if (i == 4 && updateBadge) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 3.dp, y = (-2).dp)
                                        .size(8.dp)
                                        .background(Ra.colors.sun, CircleShape),
                                )
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = spec.label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                letterSpacing = 0.1.sp,
                            ),
                            color = itemColor,
                            maxLines = 1,
                            softWrap = false,
                        )
                        Spacer(Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .background(
                                    color = if (active) Ra.colors.sun else Color.Transparent,
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }
        }
    }
}
