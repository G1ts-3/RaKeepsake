package id.rakeepsake.ui

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.zIndex
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import id.rakeepsake.R
import id.rakeepsake.data.Account
import id.rakeepsake.data.Platform
import id.rakeepsake.util.MediaType
import id.rakeepsake.util.ThumbCache

/** Buka URL siaran langsung sesuai platform (TikTok, SHOWROOM, IDN). */
fun openLivePlatformUrl(context: Context, platform: String, handle: String) {
    val cleanHandle = handle.removePrefix("@").trim()
    val url = when (platform) {
        Platform.TIKTOK.name -> "https://www.tiktok.com/@$cleanHandle/live"
        Platform.SHOWROOM.name -> "https://www.showroom-live.com/r/$cleanHandle"
        Platform.IDN.name -> "https://www.idn.app/$cleanHandle/live"
        else -> return
    }
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, "Tidak dapat membuka tautan siaran", Toast.LENGTH_SHORT).show()
    }
}

fun platformNiceName(platform: String): String = when (platform) {
    Platform.TIKTOK.name -> "TikTok"
    Platform.SHOWROOM.name -> "SHOWROOM"
    Platform.IDN.name -> "IDN"
    else -> platform
}

/** Header layar: judul besar + subjudul kiri, hingga dua tombol bundar >= 48dp di kanan. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String,
    actionIcon: ImageVector?,
    actionDesc: String = "",
    onAction: (() -> Unit)? = null,
) {
    ScreenHeader(
        title = title,
        subtitle = subtitle,
        actionIcon = actionIcon,
        actionDesc = actionDesc,
        onAction = onAction,
        secondActionIcon = null,
        secondActionDesc = "",
        onSecondAction = null,
    )
}

/** Header layar dengan dua tombol aksi >= 48dp di kanan. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String,
    actionIcon: ImageVector?,
    actionDesc: String = "",
    onAction: (() -> Unit)?,
    secondActionIcon: ImageVector?,
    secondActionDesc: String = "",
    onSecondAction: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.displayLarge, color = Ra.colors.text)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ra.colors.textSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (secondActionIcon != null && onSecondAction != null) {
                Surface(
                    onClick = onSecondAction,
                    shape = CircleShape,
                    color = Ra.colors.surface,
                    border = BorderStroke(1.dp, Ra.colors.outline),
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(secondActionIcon, secondActionDesc, tint = Ra.colors.textSecondary, modifier = Modifier.size(22.dp))
                    }
                }
            }
            if (actionIcon != null && onAction != null) {
                Surface(
                    onClick = onAction,
                    shape = CircleShape,
                    color = Ra.colors.surface,
                    border = BorderStroke(1.dp, Ra.colors.outline),
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(actionIcon, actionDesc, tint = Ra.colors.textSecondary, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

/** Chip filter pil dengan area sentuh minimal 48dp untuk aksesibilitas. */
@Composable
fun RaChip(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (active) Ra.colors.sunDim else Ra.colors.surface,
        border = if (active) BorderStroke(1.dp, Ra.colors.sun) else BorderStroke(1.dp, Ra.colors.outline.copy(alpha = 0.5f)),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 38.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 13.sp,
                ),
                color = if (active) Ra.colors.sun else Ra.colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

/** Label seksi KAPITAL (min 12sp sesuai Audit §5.2). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.labelSmall,
        color = Ra.colors.textMuted,
    )
}

/** Header bagian yang rapi untuk pengelompokan tanggal/kreator di Pustaka. */
@Composable
fun SectionHeaderSticky(title: String, countLabel: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Ra.colors.bg.copy(alpha = 0.96f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
            color = Ra.colors.sun,
        )
        if (!countLabel.isNullOrBlank()) {
            Text(
                countLabel,
                style = MaterialTheme.typography.labelMedium,
                color = Ra.colors.textMuted,
            )
        }
    }
}

/** Pil ukuran rekaman "● 312 MB" dengan titik kuning. */
@Composable
fun SizePill(sizeText: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Ra.colors.sunDim,
        border = BorderStroke(1.dp, Ra.colors.sun),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(Ra.colors.sun, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(sizeText, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = Ra.colors.sun)
        }
    }
}

/** Lencana kategori media dengan warna & teks token baru (Audit §5.4). */
@Composable
fun MediaBadge(type: MediaType) {
    val (fg, bg, label) = when (type) {
        MediaType.LIVE -> Triple(Ra.colors.live, Ra.colors.liveSoft, "● LIVE")
        MediaType.STORY -> Triple(Ra.colors.storyFg, Ra.colors.storyBg, "STORY")
        MediaType.POST -> Triple(Ra.colors.postFg, Ra.colors.postBg, "POSTINGAN")
        MediaType.PHOTO -> Triple(Ra.colors.photoFg, Ra.colors.photoBg, "FOTO")
        MediaType.AUDIO -> Triple(Ra.colors.audioFg, Ra.colors.audioBg, "AUDIO")
    }
    Text(
        label,
        color = fg,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Lencana kecil umum (platform, status info). */
@Composable
fun RaBadge(
    label: String,
    dot: Boolean = false,
    color: Color = Ra.colors.sun,
    bgColor: Color = Ra.colors.sunDim,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = bgColor,
        border = BorderStroke(1.dp, color.copy(alpha = 0.55f)),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dot) {
                Box(Modifier.size(6.dp).background(color, CircleShape))
                Spacer(Modifier.width(5.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

/** Avatar akun dengan cincin emas berdenyut lembut saat sedang live (Audit §5.4). */
@Composable
fun LiveRingAvatar(
    account: Account,
    size: Dp = 56.dp,
    isLive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val animatorsEnabled = remember { runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true) }
    val ringAlpha = if (isLive && animatorsEnabled) {
        val t = rememberInfiniteTransition(label = "live_ring")
        val a by t.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
            label = "ring_alpha",
        )
        a
    } else if (isLive) 0.9f else 0f

    val platformColor = when (account.platform) {
        "TIKTOK" -> Ra.colors.sun
        "SHOWROOM" -> Color(0xFF64B5F6)
        else -> Color(0xFFFF5A4F)
    }

    Box(
        modifier = modifier.size(if (isLive) size + 10.dp else size),
        contentAlignment = Alignment.Center,
    ) {
        if (isLive) {
            Box(
                Modifier
                    .size(size + 10.dp)
                    .border(2.5.dp, Ra.colors.sun.copy(alpha = ringAlpha), CircleShape),
            )
        }
        Surface(
            shape = CircleShape,
            color = platformColor.copy(alpha = 0.16f),
            border = BorderStroke(1.dp, if (isLive) Ra.colors.sun.copy(alpha = 0.5f) else Ra.colors.outline),
            modifier = Modifier.size(size),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (!account.avatarUrl.isNullOrBlank()) {
                    val ctx = LocalContext.current
                    val avatarReq = remember(account.avatarUrl) {
                        ImageRequest.Builder(ctx)
                            .data(account.avatarUrl)
                            .size(160)
                            .allowRgb565(true)
                            .crossfade(false)
                            .build()
                    }
                    AsyncImage(
                        model = avatarReq,
                        contentDescription = account.displayName.ifBlank { account.handle },
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                    )
                } else {
                    Text(
                        account.displayName.ifBlank { account.handle }.take(1).uppercase().ifBlank { "?" },
                        color = platformColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = if (size >= 52.dp) 20.sp else 16.sp),
                    )
                }
            }
        }
    }
}

/**
 * Thumbnail kenangan di Pustaka & Postingan (96dp default, lazy + RAM/disk cached).
 * Video = frame cuplikan + badge durasi "▶ 1:18", Foto = gambar asli, Audio = kartu musik hangat.
 */
@Composable
fun MediaThumb(
    uri: Uri?,
    mime: String,
    durationMs: Long,
    isFavorite: Boolean = false,
    size: Dp = 96.dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isImage = mime.startsWith("image/")
    val isAudio = mime.startsWith("audio/")

    val cachedThumb: Bitmap? = remember(uri) {
        if (uri != null && !isImage && !isAudio) ThumbCache.getCachedMemory(uri) else null
    }
    val videoBitmap by produceState<Bitmap?>(initialValue = cachedThumb, uri, mime) {
        if (uri != null && !isImage && !isAudio && value == null) {
            value = ThumbCache.loadVideoThumb(context, uri)
        }
    }
    val videoImageBitmap = remember(videoBitmap) { videoBitmap?.asImageBitmap() }
    val imageReq = remember(uri, isImage) {
        if (isImage && uri != null) {
            ImageRequest.Builder(context)
                .data(uri)
                .size(256)
                .allowRgb565(true)
                .crossfade(false)
                .build()
        } else null
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(Ra.colors.surface)
            .border(1.dp, if (isFavorite) Ra.colors.sun.copy(alpha = 0.7f) else Ra.colors.outline, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            imageReq != null -> {
                AsyncImage(
                    model = imageReq,
                    contentDescription = "Pratinjau foto",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            isAudio -> {
                val audioBg = Ra.colors.audioBg
                val surfaceHigh = Ra.colors.surfaceHigh
                val audioBrush = remember(audioBg, surfaceHigh) {
                    Brush.linearGradient(listOf(audioBg, surfaceHigh))
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(audioBrush),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = "Audio",
                        tint = Ra.colors.audioFg,
                        modifier = Modifier.size(size * 0.42f),
                    )
                }
            }
            videoImageBitmap != null -> {
                Image(
                    bitmap = videoImageBitmap,
                    contentDescription = "Thumbnail video",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> {
                Box(
                    Modifier.fillMaxSize().background(Ra.colors.surfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.VideoFile,
                        contentDescription = "Video",
                        tint = Ra.colors.textMuted,
                        modifier = Modifier.size(size * 0.36f),
                    )
                }
            }
        }

        // Pill durasi di sudut kanan bawah untuk video/audio
        if (!isImage && durationMs > 0L) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.76f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                if (!isAudio) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                }
                Text(
                    text = formatDuration(durationMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                )
            }
        } else if (!isImage && !isAudio && videoBitmap != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }

        // Bintang favorit di kiri atas jika ditandai favorit
        if (isFavorite) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(Ra.colors.sun, CircleShape)
                    .padding(3.dp),
            ) {
                Icon(Icons.Filled.Star, contentDescription = "Favorit", tint = Ra.colors.onSun, modifier = Modifier.size(12.dp))
            }
        }
    }
}

/** Tombol aksi kecil dengan target sentuh >= 48dp untuk aksesibilitas. */
@Composable
fun SmallButton(label: String, kind: SmallKind, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (kind == SmallKind.PRIMARY) Ra.colors.sunSoft else Ra.colors.surfaceHigh,
        modifier = Modifier.minimumInteractiveComponentSize().heightIn(min = 40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (kind == SmallKind.PRIMARY) Ra.colors.sun else Ra.colors.textSecondary,
            )
        }
    }
}

enum class SmallKind { PRIMARY, SECONDARY }

/** Toggle dengan kontras off >= 3:1 dan deskripsi aksesibilitas (Audit §7). */
@Composable
fun RaToggle(
    checked: Boolean,
    labelDesc: String = "",
    onChange: (Boolean) -> Unit,
) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        modifier = if (labelDesc.isNotBlank()) {
            Modifier.semantics {
                contentDescription = "$labelDesc: ${if (checked) "aktif" else "nonaktif"}"
            }
        } else Modifier,
        colors = SwitchDefaults.colors(
            checkedTrackColor = Ra.colors.sun,
            checkedThumbColor = Ra.colors.onSun,
            checkedBorderColor = Ra.colors.sun,
            uncheckedTrackColor = Color(0xFF28201C),
            uncheckedThumbColor = Color(0xFFD3C5B0),
            uncheckedBorderColor = Color(0xFF5A4E48),
        ),
    )
}

/** Baris pengaturan: judul + keterangan, trailing toggle atau nilai. */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color? = null,
    toggle: Boolean? = null,
    toggleValue: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onToggle: ((Boolean) -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 54.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled) { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) Ra.colors.text else Ra.colors.textMuted,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ra.colors.textMuted)
            }
        }
        when {
            toggle != null && onToggle != null -> RaToggle(toggleValue, labelDesc = title) { onToggle(it) }
            value != null -> Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor ?: Ra.colors.textSecondary,
            )
        }
    }
}

/** Input teks rahasia (Session ID) yang dimaskir default + tombol mata & salin (Audit §3.5 S1). */
@Composable
fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    onInfoClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = Ra.colors.text)
            if (onInfoClick != null) {
                IconButton(onClick = onInfoClick, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Info, "Penjelasan $label", tint = Ra.colors.sun, modifier = Modifier.size(20.dp))
                }
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = Ra.colors.textMuted) },
            visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation('•'),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onToggleReveal, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (revealed) "Sembunyikan" else "Tampilkan sementara",
                            tint = Ra.colors.textSecondary,
                        )
                    }
                    IconButton(
                        onClick = {
                            if (value.isNotBlank()) {
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText(label, value))
                                Toast.makeText(context, "$label disalin ke papan klip", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Salin",
                            tint = Ra.colors.textSecondary,
                        )
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
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Keadaan kosong bermerek Keepsake: bingkai polaroid + pita merah + pesan hangat (Audit §6 #1). */
@Composable
fun EmptyState(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.TopCenter) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Ra.colors.surfaceAlt,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(88.dp)
                    .rotate(-3f),
                border = BorderStroke(1.dp, Ra.colors.outline),
                shadowElevation = 6.dp,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(R.drawable.app_logo),
                        contentDescription = "Logo RaKeepsake",
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                    )
                }
            }
            // Pita merah kecil khas Keepsake di atas bingkai polaroid
            Box(
                modifier = Modifier
                    .width(34.dp)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Ra.colors.ribbon.copy(alpha = 0.88f)),
            )
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                tint = Ra.colors.ribbon,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 6.dp, y = 4.dp)
                    .size(18.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = Ra.colors.textMuted,
            textAlign = TextAlign.Center,
        )
        if (!actionLabel.isNullOrBlank() && onAction != null) {
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = onAction,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Ra.colors.sun.copy(alpha = 0.6f)),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(actionLabel, color = Ra.colors.sun, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Menghitung apakah isi daftar benar-benar melampaui area pandang di bawah [headerHeightPx]
 * tanpa terpengaruh oleh `bottomPadding` kompensasi header.
 */
fun LazyListState.hasRealOverflow(headerHeightPx: Int, extraBottomPx: Int = 0): Boolean {
    val info = layoutInfo
    val items = info.visibleItemsInfo
    val collapsedViewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(0)
    if (info.totalItemsCount <= 0 || items.isEmpty() || headerHeightPx <= 0 || collapsedViewport <= headerHeightPx) {
        return false
    }
    if (items.size < info.totalItemsCount) return true
    val spacing = info.mainAxisItemSpacing
    val realContentHeight = items.sumOf { it.size } + spacing * (items.size - 1).coerceAtLeast(0)
    val expandedViewport = collapsedViewport - headerHeightPx
    return realContentHeight + extraBottomPx > expandedViewport
}

/**
 * Menghitung apakah isi grid benar-benar melampaui area pandang di bawah [headerHeightPx]
 * tanpa terpengaruh oleh `bottomPadding` kompensasi header.
 */
fun LazyGridState.hasRealOverflow(headerHeightPx: Int, extraBottomPx: Int = 0): Boolean {
    val info = layoutInfo
    val items = info.visibleItemsInfo
    val collapsedViewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(0)
    if (info.totalItemsCount <= 0 || items.isEmpty() || headerHeightPx <= 0 || collapsedViewport <= headerHeightPx) {
        return false
    }
    if (items.size < info.totalItemsCount) return true
    val top = items.minOf { it.offset.y }
    val bottom = items.maxOf { it.offset.y + it.size.height }
    val realContentHeight = (bottom - top).coerceAtLeast(0)
    val expandedViewport = collapsedViewport - headerHeightPx
    return realContentHeight + extraBottomPx > expandedViewport
}

/**
 * Container dengan panel filter/kontrol di atas yang ikut tergulung naik saat scroll ke bawah
 * dan muncul kembali seketika saat pengguna menggulir ke atas (1:1 locked, 0 re-measure).
 */
@Composable
fun ScrollCollapsibleBody(
    canCollapse: (headerHeightPx: Int) -> Boolean = { true },
    resetKey: Any? = Unit,
    onAutoResetScroll: (suspend () -> Unit)? = null,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    content: @Composable (bottomPadding: Dp) -> Unit,
) {
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val offsetPx = remember { mutableFloatStateOf(0f) }
    val snapHolder = remember { object { var job: Job? = null } }
    val latestCanCollapse by rememberUpdatedState(canCollapse)
    val latestOnAutoResetScroll by rememberUpdatedState(onAutoResetScroll)
    val canCollapseNow by remember { derivedStateOf { latestCanCollapse(headerHeightPx) } }

    fun animateOffsetTo(target: Float, durationMs: Int = 140) {
        snapHolder.job?.cancel()
        val start = offsetPx.floatValue
        if (start == target) return
        snapHolder.job = coroutineScope.launch {
            Animatable(start).animateTo(target, tween(durationMs)) {
                offsetPx.floatValue = value
            }
        }
    }

    LaunchedEffect(resetKey) {
        snapHolder.job?.cancel()
        if (offsetPx.floatValue != 0f) offsetPx.floatValue = 0f
    }

    LaunchedEffect(canCollapseNow) {
        if (!canCollapseNow) {
            if (offsetPx.floatValue < 0f) {
                animateOffsetTo(0f, 160)
            }
            latestOnAutoResetScroll?.invoke()
        }
    }

    val headerHeightDp = remember(headerHeightPx, density) {
        with(density) { headerHeightPx.toDp() }
    }

    val nestedScroll = remember(focusManager) {
        var lastDy = 0f
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (headerHeightPx <= 0) return Offset.Zero
                val dy = available.y
                if (dy != 0f) snapHolder.job?.cancel()
                val maxHide = headerHeightPx.toFloat()
                val cur = offsetPx.floatValue
                if (abs(dy) >= 2f) lastDy = dy
                if (dy < 0f && cur > -maxHide && latestCanCollapse(headerHeightPx)) {
                    val next = (cur + dy).coerceAtLeast(-maxHide)
                    if (next < -maxHide * 0.35f && cur >= -maxHide * 0.35f) {
                        focusManager.clearFocus()
                    }
                    val delta = next - cur
                    offsetPx.floatValue = next
                    return Offset(0f, delta)
                }
                // Scroll ke atas (jari geser ke bawah, dy > 0) selalu memunculkan bar meski daftar baru menyusut
                if (dy > 0f && cur < 0f) {
                    val next = (cur + dy).coerceAtMost(0f)
                    val delta = next - cur
                    offsetPx.floatValue = next
                    return Offset(0f, delta)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (headerHeightPx <= 0) return Offset.Zero
                val cur = offsetPx.floatValue
                val dy = available.y
                if (dy > 0f && cur < 0f) {
                    snapHolder.job?.cancel()
                    val next = (cur + dy).coerceAtMost(0f)
                    val delta = next - cur
                    offsetPx.floatValue = next
                    return Offset(0f, delta)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val maxHide = headerHeightPx.toFloat()
                val cur = offsetPx.floatValue
                val vy = consumed.y + available.y
                val dirSnapshot = lastDy
                lastDy = 0f
                if (headerHeightPx <= 0 || cur >= 0f) return Velocity.Zero
                if (!latestCanCollapse(headerHeightPx)) {
                    animateOffsetTo(0f, 150)
                    return Velocity.Zero
                }
                if (cur < 0f && cur > -maxHide) {
                    val target = when {
                        vy > 120f -> 0f
                        vy < -120f -> -maxHide
                        dirSnapshot >= 2f && cur > -maxHide * 0.88f -> 0f
                        dirSnapshot <= -2f && cur < -maxHide * 0.25f -> -maxHide
                        cur > -maxHide * 0.5f -> 0f
                        else -> -maxHide
                    }
                    animateOffsetTo(target, 140)
                }
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .nestedScroll(nestedScroll)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                    snapHolder.job?.cancel()
                    if (offsetPx.floatValue >= 0f) return@awaitEachGesture
                    val startY = down.position.y
                    var lastY = startY
                    var draggedDown = false
                    var draggedUp = false
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Final)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            val cur = offsetPx.floatValue
                            val h = headerHeightPx.toFloat()
                            val collapsible = latestCanCollapse(headerHeightPx)
                            if (cur < 0f && (!collapsible || (!ch.isConsumed && cur > -h))) {
                                val totalDy = ch.position.y - startY
                                val target = when {
                                    !collapsible -> 0f
                                    draggedDown && totalDy > 24f -> 0f
                                    draggedUp && totalDy < -24f && cur < -h * 0.25f -> -h
                                    cur > -h * 0.5f -> 0f
                                    else -> -h
                                }
                                animateOffsetTo(target, 140)
                            }
                            break
                        }
                        val dy = ch.position.y - lastY
                        lastY = ch.position.y
                        if (!ch.isConsumed) {
                            if (dy >= 2f) draggedDown = true
                            if (dy <= -2f) draggedUp = true
                            if (dy > 0f && offsetPx.floatValue < 0f) {
                                offsetPx.floatValue = (offsetPx.floatValue + dy).coerceAtMost(0f)
                            }
                        }
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val maxHide = headerHeightPx.toFloat()
                    translationY = if (maxHide > 0f) {
                        (maxHide + offsetPx.floatValue).coerceIn(0f, maxHide)
                    } else 0f
                },
        ) {
            content(headerHeightDp)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .zIndex(1f)
                .graphicsLayer {
                    val maxHide = headerHeightPx.toFloat()
                    translationY = if (maxHide > 0f) {
                        offsetPx.floatValue.coerceIn(-maxHide, 0f)
                    } else 0f
                }
                .background(Ra.colors.bg)
                .onSizeChanged { headerHeightPx = it.height },
        ) {
            header()
        }
    }
}
