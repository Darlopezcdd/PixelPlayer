package com.theveloper.pixelplay.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class ReleaseUpdateInfo(
    val tagName: String,
    val releaseName: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val expectedSha256: String? = null,
    val publishedAt: String = ""
)

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class UpToDate(val currentVersion: String) : AppUpdateState
    data class UpdateAvailable(val info: ReleaseUpdateInfo) : AppUpdateState
    data class Downloading(val info: ReleaseUpdateInfo, val progressPercent: Int) : AppUpdateState
    data class ReadyToInstall(val info: ReleaseUpdateInfo, val apkFile: File) : AppUpdateState
    data class Error(val message: String, val info: ReleaseUpdateInfo? = null) : AppUpdateState
}

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    const val GITHUB_OWNER = "Darlopezcdd"
    const val GITHUB_REPO = "PixelPlayer"
    private const val RELEASES_API_URL =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases"
    private const val PREFS_NAME = "pixelplay_ota_updater"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    private const val KEY_DISMISSED_TAG = "dismissed_tag"
    private const val AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L // 6 hours

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val _updateState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val updateState: StateFlow<AppUpdateState> = _updateState.asStateFlow()

    fun dismissUpdateDialog(context: Context, tagName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISMISSED_TAG, tagName)
            .apply()
        val currentVersion = getInstalledVersionName(context)
        _updateState.value = AppUpdateState.UpToDate(currentVersion)
    }

    fun getInstalledVersionName(context: Context): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        }.getOrDefault("0.0.0")
    }

    suspend fun checkForUpdates(
        context: Context,
        force: Boolean = false
    ): AppUpdateState = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val currentVersion = getInstalledVersionName(context)

        if (!force) {
            val lastCheck = prefs.getLong(KEY_LAST_CHECK_MS, 0L)
            if (now - lastCheck < AUTO_CHECK_INTERVAL_MS) {
                return@withContext _updateState.value
            }
        }

        _updateState.value = AppUpdateState.Checking

        try {
            val request = Request.Builder()
                .url("$RELEASES_API_URL?per_page=5")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "PixelPlayer-Updater/$currentVersion")
                .build()

            val responseBody = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("GitHub API HTTP ${response.code}")
                }
                response.body?.string().orEmpty()
            }

            prefs.edit().putLong(KEY_LAST_CHECK_MS, now).apply()

            val releasesArray = JSONArray(responseBody)
            if (releasesArray.length() == 0) {
                val state = AppUpdateState.UpToDate(currentVersion)
                _updateState.value = state
                return@withContext state
            }

            var latestRelease: JSONObject? = null
            for (i in 0 until releasesArray.length()) {
                val candidate = releasesArray.getJSONObject(i)
                if (!candidate.optBoolean("draft", false)) {
                    latestRelease = candidate
                    break
                }
            }

            if (latestRelease == null) {
                val state = AppUpdateState.UpToDate(currentVersion)
                _updateState.value = state
                return@withContext state
            }

            val releaseInfo = parseReleaseJson(latestRelease)
            if (releaseInfo == null) {
                val state = AppUpdateState.UpToDate(currentVersion)
                _updateState.value = state
                return@withContext state
            }

            val dismissedTag = prefs.getString(KEY_DISMISSED_TAG, null)
            val isNewer = isRemoteVersionNewer(
                remoteTag = releaseInfo.tagName,
                localVersion = currentVersion
            )

            val newState = if (isNewer && (force || releaseInfo.tagName != dismissedTag)) {
                AppUpdateState.UpdateAvailable(releaseInfo)
            } else {
                AppUpdateState.UpToDate(currentVersion)
            }
            _updateState.value = newState
            newState
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to check for updates")
            val errState = if (force) {
                AppUpdateState.Error("No se pudo verificar actualizaciones: ${e.message ?: "Error de red"}")
            } else {
                AppUpdateState.Idle
            }
            _updateState.value = errState
            errState
        }
    }

    suspend fun downloadAndInstallUpdate(
        context: Context,
        info: ReleaseUpdateInfo
    ): AppUpdateState = withContext(Dispatchers.IO) {
        _updateState.value = AppUpdateState.Downloading(info, 0)

        try {
            val updatesDir = File(context.cacheDir, "updates").apply {
                if (!exists()) mkdirs()
            }
            // Clean old APKs before downloading
            updatesDir.listFiles()?.forEach { runCatching { it.delete() } }

            val targetFile = File(updatesDir, info.apkFileName.replace(Regex("[^a-zA-Z0-9._-]"), "_"))
            val request = Request.Builder()
                .url(info.apkDownloadUrl)
                .header("User-Agent", "PixelPlayer-Updater/${getInstalledVersionName(context)}")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code} al descargar el APK")
                }
                val body = response.body ?: throw IllegalStateException("Respuesta vacía al descargar APK")
                val totalBytes = if (body.contentLength() > 0) body.contentLength() else info.apkSizeBytes

                val digest = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var downloadedBytes = 0L
                        var lastEmittedPercent = 0
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0L) {
                                val percent = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 99)
                                if (percent != lastEmittedPercent) {
                                    lastEmittedPercent = percent
                                    _updateState.value = AppUpdateState.Downloading(info, percent)
                                }
                            }
                        }
                        output.flush()
                    }
                }

                // Verify SHA-256 digest if present on the GitHub release asset
                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                val expectedSha256 = info.expectedSha256?.lowercase()?.removePrefix("sha256:")?.trim()
                if (!expectedSha256.isNullOrBlank() && !expectedSha256.equals(actualSha256, ignoreCase = true)) {
                    targetFile.delete()
                    throw SecurityException("Verificación SHA-256 fallida para el APK descargado.")
                }
            }

            // Verify that the downloaded APK is a valid archive for this exact package name
            val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(
                    targetFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(targetFile.absolutePath, 0)
            }
            if (archiveInfo == null || archiveInfo.packageName != context.packageName) {
                targetFile.delete()
                throw SecurityException("El APK descargado no coincide con el paquete ${context.packageName}.")
            }

            val readyState = AppUpdateState.ReadyToInstall(info, targetFile)
            _updateState.value = readyState

            withContext(Dispatchers.Main) {
                launchApkInstaller(context, targetFile)
            }
            readyState
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to download or verify update APK")
            val errorState = AppUpdateState.Error(
                message = e.message ?: "Error al descargar la actualización",
                info = info
            )
            _updateState.value = errorState
            errorState
        }
    }

    fun launchApkInstaller(context: Context, apkFile: File) {
        if (!apkFile.exists()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(permissionIntent)
            return
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    }

    private fun parseReleaseJson(releaseJson: JSONObject): ReleaseUpdateInfo? {
        val tagName = releaseJson.optString("tag_name", "").trim()
        if (tagName.isBlank()) return null

        val releaseName = releaseJson.optString("name", tagName).ifBlank { tagName }
        val releaseNotes = releaseJson.optString("body", "").trim()
        val htmlUrl = releaseJson.optString("html_url", "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases")
        val publishedAt = releaseJson.optString("published_at", "")

        val assets = releaseJson.optJSONArray("assets") ?: return null
        val preferredAbis = Build.SUPPORTED_ABIS.orEmpty()

        data class ApkAssetCandidate(
            val name: String,
            val downloadUrl: String,
            val sizeBytes: Long,
            val digest: String?,
            val score: Int
        )

        val candidates = mutableListOf<ApkAssetCandidate>()
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            if (name.contains("wear", ignoreCase = true)) continue

            val downloadUrl = asset.optString("browser_download_url", "")
            if (downloadUrl.isBlank()) continue
            val sizeBytes = asset.optLong("size", 0L)
            val digest = asset.optString("digest", "").takeIf { it.startsWith("sha256:") }

            val abiMatchIndex = preferredAbis.indexOfFirst { abi ->
                name.contains(abi, ignoreCase = true)
            }
            val score = when {
                abiMatchIndex == 0 -> 100
                abiMatchIndex > 0 -> 80 - abiMatchIndex
                name.contains("universal", ignoreCase = true) -> 60
                !name.contains("armeabi", ignoreCase = true) && !name.contains("x86", ignoreCase = true) -> 50
                else -> 10
            }
            candidates.add(ApkAssetCandidate(name, downloadUrl, sizeBytes, digest, score))
        }

        val bestApk = candidates.maxByOrNull { it.score } ?: return null
        return ReleaseUpdateInfo(
            tagName = tagName,
            releaseName = releaseName,
            releaseNotes = releaseNotes,
            htmlUrl = htmlUrl,
            apkDownloadUrl = bestApk.downloadUrl,
            apkFileName = bestApk.name,
            apkSizeBytes = bestApk.sizeBytes,
            expectedSha256 = bestApk.digest,
            publishedAt = publishedAt
        )
    }

    internal fun isRemoteVersionNewer(remoteTag: String, localVersion: String): Boolean {
        val cleanRemote = remoteTag.trim().removePrefix("v").removePrefix("V")
        val cleanLocal = localVersion.trim().removePrefix("v").removePrefix("V")
        if (cleanRemote.isBlank() || cleanRemote.equals(cleanLocal, ignoreCase = true)) {
            return false
        }

        val remoteNumbers = Regex("\\d+").findAll(cleanRemote).mapNotNull { it.value.toIntOrNull() }.toList()
        val localNumbers = Regex("\\d+").findAll(cleanLocal).mapNotNull { it.value.toIntOrNull() }.toList()

        val maxLen = maxOf(remoteNumbers.size, localNumbers.size)
        for (i in 0 until maxLen) {
            val r = remoteNumbers.getOrElse(i) { 0 }
            val l = localNumbers.getOrElse(i) { 0 }
            if (r != l) {
                return r > l
            }
        }

        // If numeric prefix is equal (e.g. 0.7.5 vs 0.7.5-beta), stable > beta, or mod suffix > base
        val remoteIsPre = cleanRemote.contains("alpha", true) || cleanRemote.contains("beta", true) || cleanRemote.contains("rc", true)
        val localIsPre = cleanLocal.contains("alpha", true) || cleanLocal.contains("beta", true) || cleanLocal.contains("rc", true)
        if (!remoteIsPre && localIsPre) return true
        return false
    }
}
