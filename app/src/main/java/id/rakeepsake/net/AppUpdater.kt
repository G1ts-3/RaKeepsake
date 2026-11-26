package id.rakeepsake.net

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import id.rakeepsake.BuildConfig
import id.rakeepsake.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Pengelola pembaruan aplikasi mandiri (in-app self-update) melalui GitHub Releases atau URL manifest JSON.
 */
class AppUpdater(
    private val appContext: Context,
    private val settings: SettingsStore,
) {
    companion object {
        private const val TAG = "RaUpdater"
        private const val AUTO_CHECK_INTERVAL_MS = 6 * 3600_000L
        private const val UPDATES_SUBDIR = "updates"
        val DEFAULT_SOURCE: String = BuildConfig.UPDATE_REPO

        fun parseSource(raw: String): SourceSpec? {
            val trimmed = raw.trim().removeSuffix("/")
            if (trimmed.isBlank()) return null

            val ghRegex = Regex("""^(?:https?://)?(?:www\.)?github\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git|/releases.*)?$""", RegexOption.IGNORE_CASE)
            ghRegex.matchEntire(trimmed)?.let { m ->
                return SourceSpec.GitHub(m.groupValues[1])
            }

            val slugRegex = Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$""")
            if (slugRegex.matches(trimmed)) {
                return SourceSpec.GitHub(trimmed.removeSuffix(".git"))
            }

            if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
                return SourceSpec.JsonManifest(trimmed)
            }
            return null
        }

        internal fun parseTagVersionAndCode(rawTag: String): Pair<String, Int?> {
            val trimmed = rawTag.trim().removePrefix("v").removePrefix("V")
            val plusIdx = trimmed.indexOf('+')
            if (plusIdx > 0) {
                val verPart = trimmed.substring(0, plusIdx).trim()
                val codePart = trimmed.substring(plusIdx + 1).trim().toIntOrNull()
                return verPart to codePart
            }
            return trimmed to null
        }

        internal fun isRemoteNewer(
            info: ReleaseInfo,
            currentVersionCode: Int,
            currentVersionName: String,
        ): Boolean {
            val remoteTuple = parseSemverTuple(info.versionName)
            val currentTuple = parseSemverTuple(currentVersionName)

            if (remoteTuple.isNotEmpty() && currentTuple.isNotEmpty()) {
                val maxLen = maxOf(remoteTuple.size, currentTuple.size)
                for (i in 0 until maxLen) {
                    val r = remoteTuple.getOrElse(i) { 0 }
                    val c = currentTuple.getOrElse(i) { 0 }
                    if (r > c) return true
                    if (r < c) return false
                }
                val remoteHasPre = info.versionName.contains('-')
                val currentHasPre = currentVersionName.contains('-')
                if (currentHasPre && !remoteHasPre) return true
                if (!currentHasPre && remoteHasPre) return false
                return info.versionCode != null && info.versionCode > currentVersionCode
            }

            if (info.versionCode != null) {
                return info.versionCode > currentVersionCode
            }
            val cleanRemote = info.versionName.trim().removePrefix("v").removePrefix("V")
            val cleanCurrent = currentVersionName.trim().removePrefix("v").removePrefix("V")
            return cleanRemote.isNotBlank() && !cleanRemote.equals(cleanCurrent, ignoreCase = true)
        }

        private fun parseSemverTuple(version: String): List<Int> {
            val core = version.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('+')
                .substringBefore('-')
            if (core.isBlank()) return emptyList()
            val parts = core.split('.')
            val out = mutableListOf<Int>()
            for (p in parts) {
                val digits = p.takeWhile { it.isDigit() }.toIntOrNull() ?: return emptyList()
                out.add(digits)
            }
            return out
        }
    }

    @Immutable
    data class ReleaseInfo(
        val versionName: String,
        val versionCode: Int?,
        val tagName: String,
        val changelog: String,
        val apkUrl: String,
        val apkFileName: String,
        val sizeBytes: Long,
        val htmlUrl: String,
    )

    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data class UpToDate(val checkedAt: Long, val note: String? = null) : UpdateState
        data class Available(val info: ReleaseInfo) : UpdateState
        data class Downloading(
            val info: ReleaseInfo,
            val downloadedBytes: Long,
            val totalBytes: Long,
        ) : UpdateState
        data class ReadyToInstall(val info: ReleaseInfo, val apkFile: File) : UpdateState
        data class Error(val message: String, val retryInfo: ReleaseInfo? = null) : UpdateState
    }

    sealed interface SourceSpec {
        data class GitHub(val ownerRepo: String) : SourceSpec
        data class JsonManifest(val url: String) : SourceSpec
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _openDialogRequested = MutableStateFlow(false)
    val openDialogRequested: StateFlow<Boolean> = _openDialogRequested.asStateFlow()

    private var downloadJob: Job? = null

    fun requestOpenDialog() {
        _openDialogRequested.value = true
    }

    fun consumeOpenDialogRequest() {
        _openDialogRequested.value = false
    }

    fun isUpdatePending(): Boolean = when (_state.value) {
        is UpdateState.Available,
        is UpdateState.Downloading,
        is UpdateState.ReadyToInstall,
        -> true
        else -> false
    }

    /** Cek pembaruan ringan saat startup jika diaktifkan dan jeda 6 jam sudah lewat. */
    suspend fun checkIfDue() {
        val snap = settings.snapshot()
        cleanupStaleApks()
        if (!snap.autoUpdateCheck) return
        val now = System.currentTimeMillis()
        if (now - snap.lastUpdateCheckAt < AUTO_CHECK_INTERVAL_MS) return
        checkNow(manual = false)
    }

    /** Jalankan pemeriksaan pembaruan sekarang. [manual]=false menekan error jaringan/404 saat startup. */
    fun checkNowAsync(manual: Boolean = true) {
        scope.launch { checkNow(manual) }
    }

    suspend fun checkNow(manual: Boolean): UpdateState = checkMutex.withLock {
        val curState = _state.value
        if (curState is UpdateState.Downloading) return curState
        if (manual) _state.value = UpdateState.Checking

        val snap = settings.snapshot()
        val rawSource = snap.updateSource.ifBlank { DEFAULT_SOURCE }
        val source = parseSource(rawSource)
        if (source == null) {
            val res = if (manual) {
                UpdateState.Error("Sumber pembaruan tidak valid: \"$rawSource\". Gunakan format username/repo atau URL https://.")
            } else {
                UpdateState.Idle
            }
            _state.value = res
            return res
        }

        val now = System.currentTimeMillis()
        return try {
            val outcome = when (source) {
                is SourceSpec.GitHub -> fetchGitHubRelease(source.ownerRepo)
                is SourceSpec.JsonManifest -> fetchJsonManifest(source.url)
            }
            settings.setLastUpdateCheckAt(now)
            when (outcome) {
                is FetchOutcome.NoReleaseYet -> {
                    cleanupStaleApks()
                    val next = if (manual) {
                        UpdateState.UpToDate(now, "Belum ada rilis pembaruan di $rawSource")
                    } else {
                        UpdateState.Idle
                    }
                    _state.value = next
                    next
                }
                is FetchOutcome.MissingApk -> {
                    val next = if (manual) {
                        UpdateState.UpToDate(now, "Rilis ${outcome.tagName} ditemukan, tetapi berkas APK belum dilampirkan.")
                    } else {
                        UpdateState.Idle
                    }
                    _state.value = next
                    next
                }
                is FetchOutcome.Found -> {
                    val remote = outcome.info
                    if (isRemoteNewer(remote, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)) {
                        val cachedApk = findCachedValidApk(remote)
                        val next = if (cachedApk != null) {
                            UpdateState.ReadyToInstall(remote, cachedApk)
                        } else {
                            UpdateState.Available(remote)
                        }
                        _state.value = next
                        next
                    } else {
                        cleanupStaleApks()
                        val next = if (manual) UpdateState.UpToDate(now) else UpdateState.Idle
                        _state.value = next
                        next
                    }
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "Gagal memeriksa pembaruan ($rawSource): ${t.message}")
            val next = if (manual) {
                UpdateState.Error(humanizeNetworkError(t))
            } else {
                // Jangan ganggu pengguna dengan error saat pengecekan latar belakang
                if (curState is UpdateState.Available || curState is UpdateState.ReadyToInstall) curState else UpdateState.Idle
            }
            _state.value = next
            next
        }
    }

    fun startDownload(info: ReleaseInfo) {
        val current = _state.value
        if (current is UpdateState.Downloading) return

        downloadJob?.cancel()
        downloadJob = scope.launch {
            val dir = updatesDir()
            val safeName = sanitizeFileName(info.apkFileName.ifBlank { "RaKeepsake-v${info.versionName}.apk" })
                .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
            val finalFile = File(dir, safeName)
            val partFile = File(dir, "$safeName.part")

            try {
                // Bersihkan unduhan lama lain agar cache tidak menumpuk
                dir.listFiles()?.forEach { f ->
                    if (f.absolutePath != finalFile.absolutePath && f.absolutePath != partFile.absolutePath) {
                        f.delete()
                    }
                }
                if (finalFile.exists() && isValidDownloadedApk(finalFile, info)) {
                    _state.value = UpdateState.ReadyToInstall(info, finalFile)
                    return@launch
                }
                if (partFile.exists()) partFile.delete()

                _state.value = UpdateState.Downloading(info, 0L, info.sizeBytes.coerceAtLeast(0L))

                val req = Request.Builder()
                    .url(info.apkUrl)
                    .header("User-Agent", "RaKeepsake/${BuildConfig.VERSION_NAME}")
                    .header("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
                    .build()

                Http.downloadClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        error("Server mengembalikan HTTP ${resp.code} saat mengunduh APK")
                    }
                    val body = resp.body ?: error("Berkas APK kosong dari server")
                    val contentLen = body.contentLength().takeIf { it > 0L } ?: info.sizeBytes.coerceAtLeast(0L)
                    var downloaded = 0L
                    var lastEmitAt = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(partFile).use { output ->
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                output.write(buf, 0, read)
                                downloaded += read
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastEmitAt >= 120L) {
                                    lastEmitAt = now
                                    _state.value = UpdateState.Downloading(info, downloaded, contentLen)
                                }
                            }
                            output.flush()
                        }
                    }
                    _state.value = UpdateState.Downloading(info, downloaded, maxOf(downloaded, contentLen))
                }

                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }

                val verifyError = verifyApkArchive(finalFile)
                if (verifyError != null) {
                    finalFile.delete()
                    _state.value = UpdateState.Error(verifyError, retryInfo = info)
                    return@launch
                }

                _state.value = UpdateState.ReadyToInstall(info, finalFile)
            } catch (_: CancellationException) {
                partFile.delete()
                _state.value = UpdateState.Available(info)
            } catch (t: Throwable) {
                Log.w(TAG, "Unduh APK pembaruan gagal: ${t.message}")
                partFile.delete()
                _state.value = UpdateState.Error(humanizeNetworkError(t), retryInfo = info)
            }
        }
    }

    fun cancelDownload() {
        val current = _state.value
        downloadJob?.cancel()
        downloadJob = null
        if (current is UpdateState.Downloading) {
            _state.value = UpdateState.Available(current.info)
        }
    }

    fun canRequestPackageInstalls(context: Context = appContext): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            val fallback = Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(fallback) }
        }
    }

    fun launchInstaller(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists()) {
            _state.value = UpdateState.Error("Berkas APK pembaruan tidak ditemukan. Silakan unduh ulang.")
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { err ->
            Log.w(TAG, "Gagal membuka installer Android: ${err.message}")
            false
        }
    }

    // ==================== PARSING & NETWORK ====================

    private sealed interface FetchOutcome {
        data object NoReleaseYet : FetchOutcome
        data class MissingApk(val tagName: String) : FetchOutcome
        data class Found(val info: ReleaseInfo) : FetchOutcome
    }

    private fun fetchGitHubRelease(ownerRepo: String): FetchOutcome {
        val apiLatestUrl = "https://api.github.com/repos/$ownerRepo/releases/latest"
        val req = Request.Builder()
            .url(apiLatestUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "RaKeepsake/${BuildConfig.VERSION_NAME}")
            .header("Cache-Control", "no-cache")
            .build()

        Http.client.newCall(req).execute().use { resp ->
            when (resp.code) {
                200 -> {
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) return FetchOutcome.NoReleaseYet
                    val obj = json.parseToJsonElement(body) as? JsonObject ?: return FetchOutcome.NoReleaseYet
                    return parseGitHubReleaseObject(obj)
                }
                404 -> {
                    // Coba daftar releases terbaru (barangkali rilis ditandai pre-release di GitHub)
                    return fetchGitHubReleaseListFallback(ownerRepo)
                }
                403, 429 -> {
                    // Rate-limit API anonim GitHub: gunakan halaman HTML releases publik sebagai cadangan
                    Log.w(TAG, "GitHub API HTTP ${resp.code}, beralih ke jalur HTML cadangan")
                    return fetchGitHubHtmlFallback(ownerRepo)
                }
                else -> error("GitHub mengembalikan status HTTP ${resp.code}")
            }
        }
    }

    private fun fetchGitHubReleaseListFallback(ownerRepo: String): FetchOutcome {
        val listUrl = "https://api.github.com/repos/$ownerRepo/releases?per_page=5"
        val req = Request.Builder()
            .url(listUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "RaKeepsake/${BuildConfig.VERSION_NAME}")
            .header("Cache-Control", "no-cache")
            .build()

        Http.client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return FetchOutcome.NoReleaseYet
            if (resp.code == 403 || resp.code == 429) return fetchGitHubHtmlFallback(ownerRepo)
            if (!resp.isSuccessful) error("GitHub mengembalikan status HTTP ${resp.code}")
            val arr = json.parseToJsonElement(resp.body?.string().orEmpty()) as? JsonArray ?: return FetchOutcome.NoReleaseYet
            val nonDraft = arr.firstOrNull { el ->
                val o = el as? JsonObject ?: return@firstOrNull false
                o["draft"]?.jsonPrimitive?.booleanOrNull != true
            } as? JsonObject ?: return FetchOutcome.NoReleaseYet
            return parseGitHubReleaseObject(nonDraft)
        }
    }

    private fun fetchGitHubHtmlFallback(ownerRepo: String): FetchOutcome {
        val latestUrl = "https://github.com/$ownerRepo/releases/latest"
        val req = Request.Builder()
            .url(latestUrl)
            .header("User-Agent", Http.UA_WEB)
            .header("Cache-Control", "no-cache")
            .build()

        val (finalUrl, pageHtml) = Http.client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return FetchOutcome.NoReleaseYet
            if (!resp.isSuccessful) error("GitHub halaman rilis HTTP ${resp.code}")
            resp.request.url.toString() to resp.body?.string().orEmpty()
        }
        if (!finalUrl.contains("/releases/tag/")) {
            return FetchOutcome.NoReleaseYet
        }
        val rawTag = finalUrl.substringAfter("/releases/tag/").substringBefore("?").substringBefore("#")
        val tagName = Uri.decode(rawTag)
        if (tagName.isBlank()) return FetchOutcome.NoReleaseYet

        val expandedUrl = "https://github.com/$ownerRepo/releases/expanded_assets/$rawTag"
        val expandedHtml = runCatching {
            Http.getText(expandedUrl, referer = finalUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
        }.getOrElse { pageHtml }

        val apkHrefRegex = Regex("""href="(/[^"]+/releases/download/[^"]+\.apk)"""", RegexOption.IGNORE_CASE)
        val candidates = apkHrefRegex.findAll(expandedHtml).map { it.groupValues[1] }.toList()
        val pickedHref = candidates.firstOrNull {
            it.contains("release", ignoreCase = true) && !it.contains("debug", ignoreCase = true)
        } ?: candidates.firstOrNull {
            !it.contains("debug", ignoreCase = true)
        } ?: candidates.firstOrNull()
            ?: return FetchOutcome.MissingApk(tagName)

        val apkUrl = "https://github.com$pickedHref"
        val fileName = Uri.decode(pickedHref.substringAfterLast('/'))
        val parsedTag = parseTagVersionAndCode(tagName)
        val rawBodyHtml = Regex("""data-test-selector="body-content"[^>]*>(.*?)</div>""", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(pageHtml)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        val parsedChangelog = android.text.Html.fromHtml(rawBodyHtml, android.text.Html.FROM_HTML_MODE_COMPACT)
            .toString()
            .trim()
        val bodyCode = Regex("""versionCode\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(parsedChangelog)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        return FetchOutcome.Found(
            ReleaseInfo(
                versionName = parsedTag.first,
                versionCode = parsedTag.second ?: bodyCode,
                tagName = tagName,
                changelog = parsedChangelog.ifBlank { "Pembaruan versi ${parsedTag.first} tersedia di GitHub Releases." },
                apkUrl = apkUrl,
                apkFileName = fileName,
                sizeBytes = 0L,
                htmlUrl = finalUrl,
            ),
        )
    }

    private fun parseGitHubReleaseObject(obj: JsonObject): FetchOutcome {
        val tagName = obj["tag_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val releaseName = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val bodyText = obj["body"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val assets = obj["assets"] as? JsonArray

        if (tagName.isBlank()) return FetchOutcome.NoReleaseYet

        val apkAssets = assets?.mapNotNull { el ->
            val asset = el as? JsonObject ?: return@mapNotNull null
            val name = asset["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val dlUrl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (!name.endsWith(".apk", ignoreCase = true) || dlUrl.isBlank()) return@mapNotNull null
            val size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L
            Triple(name, dlUrl, size)
        }.orEmpty()

        val chosenApk = apkAssets.firstOrNull { (name, _, _) ->
            name.contains("release", ignoreCase = true) && !name.contains("debug", ignoreCase = true)
        } ?: apkAssets.firstOrNull { (name, _, _) ->
            !name.contains("debug", ignoreCase = true)
        } ?: apkAssets.firstOrNull()
            ?: return FetchOutcome.MissingApk(tagName)

        val (parsedVer, tagCode) = parseTagVersionAndCode(tagName.ifBlank { releaseName })
        val bodyCode = Regex("""versionCode\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find("$releaseName\n$bodyText")
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        return FetchOutcome.Found(
            ReleaseInfo(
                versionName = parsedVer,
                versionCode = tagCode ?: bodyCode,
                tagName = tagName,
                changelog = bodyText.ifBlank { releaseName.ifBlank { "Pembaruan versi $parsedVer tersedia." } },
                apkUrl = chosenApk.second,
                apkFileName = chosenApk.first,
                sizeBytes = chosenApk.third,
                htmlUrl = htmlUrl,
            ),
        )
    }

    private fun fetchJsonManifest(url: String): FetchOutcome {
        val body = Http.getText(url, headers = mapOf("Cache-Control" to "no-cache"))
        val obj = json.parseToJsonElement(body) as? JsonObject ?: return FetchOutcome.NoReleaseYet
        val apkUrl = obj["apkUrl"]?.jsonPrimitive?.contentOrNull
            ?: obj["downloadUrl"]?.jsonPrimitive?.contentOrNull
            ?: obj["url"]?.jsonPrimitive?.contentOrNull
            ?: ""
        if (apkUrl.isBlank()) return FetchOutcome.NoReleaseYet

        val rawVer = obj["versionName"]?.jsonPrimitive?.contentOrNull
            ?: obj["version"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val verCode = obj["versionCode"]?.jsonPrimitive?.intOrNull
        val (cleanVer, tagCode) = parseTagVersionAndCode(rawVer)
        val changelog = obj["changelog"]?.jsonPrimitive?.contentOrNull
            ?: obj["notes"]?.jsonPrimitive?.contentOrNull
            ?: "Pembaruan versi $cleanVer tersedia."
        val size = obj["sizeBytes"]?.jsonPrimitive?.longOrNull ?: 0L
        val fileName = Uri.parse(apkUrl).lastPathSegment?.takeIf { it.endsWith(".apk", true) }
            ?: "RaKeepsake-v$cleanVer.apk"

        return FetchOutcome.Found(
            ReleaseInfo(
                versionName = cleanVer.ifBlank { "baru" },
                versionCode = verCode ?: tagCode,
                tagName = rawVer.ifBlank { cleanVer },
                changelog = changelog,
                apkUrl = apkUrl,
                apkFileName = fileName,
                sizeBytes = size,
                htmlUrl = url,
            ),
        )
    }

    // ==================== APK CACHE & SIGNATURE CHECK ====================

    private fun updatesDir(): File = File(appContext.cacheDir, UPDATES_SUBDIR).apply { mkdirs() }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun cleanupStaleApks() {
        runCatching {
            val dir = File(appContext.cacheDir, UPDATES_SUBDIR)
            if (!dir.exists()) return
            dir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".part", true)) {
                    file.delete()
                } else if (file.name.endsWith(".apk", true)) {
                    val pkg = readArchivePackageInfo(file)
                    val code = pkg?.let { PackageInfoCompat.getLongVersionCode(it) } ?: -1L
                    if (pkg == null || code <= BuildConfig.VERSION_CODE.toLong()) {
                        file.delete()
                    }
                }
            }
        }
    }

    private fun findCachedValidApk(info: ReleaseInfo): File? {
        val dir = File(appContext.cacheDir, UPDATES_SUBDIR)
        if (!dir.exists()) return null
        val targetName = sanitizeFileName(info.apkFileName)
        val candidates = dir.listFiles { f -> f.isFile && f.name.endsWith(".apk", true) }?.toList().orEmpty()
        val match = candidates.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
            ?: candidates.firstOrNull()
            ?: return null
        return if (isValidDownloadedApk(match, info)) match else null
    }

    private fun isValidDownloadedApk(file: File, info: ReleaseInfo): Boolean {
        if (!file.exists() || file.length() < 100_000L) return false
        if (info.sizeBytes > 0L && file.length() != info.sizeBytes) return false
        return verifyApkArchive(file) == null
    }

    @Suppress("DEPRECATION")
    private fun readArchivePackageInfo(file: File, flags: Int = 0): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            appContext.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun verifyApkArchive(file: File): String? {
        if (!file.exists() || file.length() < 100_000L) {
            return "Berkas APK pembaruan tidak lengkap atau rusak."
        }
        val signFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archivePkg = readArchivePackageInfo(file, signFlag)
            ?: return "Berkas yang diunduh bukan paket APK Android yang valid."

        if (archivePkg.packageName != appContext.packageName) {
            return "ID paket APK (${archivePkg.packageName}) tidak cocok dengan RaKeepsake (${appContext.packageName})."
        }

        val apkCode = PackageInfoCompat.getLongVersionCode(archivePkg)
        if (apkCode < BuildConfig.VERSION_CODE.toLong()) {
            return "Versi build APK ($apkCode) lebih rendah dari yang terpasang (${BuildConfig.VERSION_CODE})."
        }

        val archiveDigests = extractSigningDigests(archivePkg)
        val installedPkg = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getPackageInfo(
                    appContext.packageName,
                    PackageManager.PackageInfoFlags.of(signFlag.toLong()),
                )
            } else {
                appContext.packageManager.getPackageInfo(appContext.packageName, signFlag)
            }
        }.getOrNull()
        val installedDigests = installedPkg?.let { extractSigningDigests(it) }.orEmpty()

        if (archiveDigests.isNotEmpty() && installedDigests.isNotEmpty() && archiveDigests.intersect(installedDigests).isEmpty()) {
            return "Tanda tangan (keystore) APK pembaruan tidak sama dengan aplikasi terpasang."
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun extractSigningDigests(pkg: PackageInfo): Set<String> {
        val out = mutableSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = pkg.signingInfo
            val signers = si?.apkContentsSigners ?: si?.signingCertificateHistory
            signers?.forEach { sig ->
                out.add(sha256Hex(sig.toByteArray()))
            }
        }
        if (out.isEmpty()) {
            pkg.signatures?.forEach { sig ->
                out.add(sha256Hex(sig.toByteArray()))
            }
        }
        return out
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun humanizeNetworkError(t: Throwable): String {
        val msg = t.message.orEmpty()
        return when {
            t is java.net.UnknownHostException -> "Tidak ada koneksi internet atau domain tidak dapat dijangkau."
            t is java.net.SocketTimeoutException -> "Koneksi ke server pembaruan kehabisan waktu (timeout)."
            msg.contains("HTTP 403") || msg.contains("HTTP 429") -> "Server membatasi permintaan sementara. Coba lagi sebentar lagi."
            msg.isNotBlank() -> msg
            else -> "Gagal memeriksa atau mengunduh pembaruan."
        }
    }
}
