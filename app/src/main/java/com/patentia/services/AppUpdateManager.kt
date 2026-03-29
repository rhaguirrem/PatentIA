package com.patentia.services

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.patentia.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AppUpdateManager(
    private val appContext: Context,
) {

    data class AppVersionInfo(
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val fileSizeBytes: Long? = null,
    )

    sealed interface UpdateCheckResult {
        data class UpdateAvailable(
            val remoteVersion: AppVersionInfo,
            val downloadUrl: String,
            val pageUrl: String,
        ) : UpdateCheckResult

        data class UpToDate(
            val remoteVersionName: String,
            val remoteVersionCode: Long,
            val pageUrl: String,
        ) : UpdateCheckResult

        data class Failed(val message: String) : UpdateCheckResult
    }

    sealed interface DownloadUpdateResult {
        data class Downloaded(
            val downloadedApkPath: String,
            val fileSizeBytes: Long,
        ) : DownloadUpdateResult

        data class Failed(val message: String) : DownloadUpdateResult
    }

    sealed interface InstallUpdateResult {
        data class InstallerOpened(val message: String) : InstallUpdateResult

        data class PermissionRequired(val message: String) : InstallUpdateResult

        data class Failed(val message: String) : InstallUpdateResult
    }

    fun getInstalledVersionInfo(): AppVersionInfo {
        val packageInfo = getInstalledPackageInfo()
        return AppVersionInfo(
            packageName = packageInfo.packageName,
            versionName = packageInfo.versionName ?: "unknown",
            versionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
        )
    }

    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val installedVersion = getInstalledVersionInfo()
            val remoteManifest = fetchUpdateManifest()
                ?: return@runCatching checkForUpdateByDownloadingApk(installedVersion)
            val remoteVersion = AppVersionInfo(
                packageName = remoteManifest.packageName,
                versionName = remoteManifest.versionName,
                versionCode = remoteManifest.versionCode,
                fileSizeBytes = remoteManifest.fileSizeBytes,
            )

            if (remoteVersion.packageName != appContext.packageName) {
                return@runCatching UpdateCheckResult.Failed(
                    "The published update manifest belongs to ${remoteVersion.packageName}, not ${appContext.packageName}."
                )
            }

            if (remoteVersion.versionCode <= installedVersion.versionCode) {
                return@runCatching UpdateCheckResult.UpToDate(
                    remoteVersionName = remoteVersion.versionName,
                    remoteVersionCode = remoteVersion.versionCode,
                    pageUrl = remoteManifest.pageUrl,
                )
            }

            UpdateCheckResult.UpdateAvailable(
                remoteVersion = remoteVersion,
                downloadUrl = remoteManifest.apkUrl,
                pageUrl = remoteManifest.pageUrl,
            )
        }.getOrElse { error ->
            UpdateCheckResult.Failed(error.message ?: "The update manifest could not be read.")
        }
    }

    suspend fun downloadUpdateApk(downloadUrl: String): DownloadUpdateResult = withContext(Dispatchers.IO) {
        runCatching {
            val downloadedApk = downloadRemoteApk(downloadUrl)
            DownloadUpdateResult.Downloaded(
                downloadedApkPath = downloadedApk.file.absolutePath,
                fileSizeBytes = downloadedApk.fileSizeBytes,
            )
        }.getOrElse { error ->
            DownloadUpdateResult.Failed(error.message ?: "The update package could not be downloaded.")
        }
    }

    fun installDownloadedApk(downloadedApkPath: String): InstallUpdateResult {
        val apkFile = File(downloadedApkPath)
        if (!apkFile.exists()) {
            return InstallUpdateResult.Failed("The downloaded APK is no longer available. Check for updates again.")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !appContext.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(settingsIntent)
            return InstallUpdateResult.PermissionRequired(
                "Allow installs from this app, then tap Install update again."
            )
        }

        val apkUri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apkFile,
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        }

        return runCatching {
            appContext.startActivity(installIntent)
            InstallUpdateResult.InstallerOpened("Android package installer opened for ${apkFile.name}.")
        }.getOrElse { error ->
            InstallUpdateResult.Failed(error.message ?: "Android could not open the package installer.")
        }
    }

    private fun getInstalledPackageInfo(): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }
    }

    private fun readArchiveVersionInfo(apkFile: File): AppVersionInfo? {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        } ?: return null

        return AppVersionInfo(
            packageName = packageInfo.packageName,
            versionName = packageInfo.versionName ?: "unknown",
            versionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
        )
    }

    private fun downloadRemoteApk(downloadUrl: String): DownloadedApk {
        val updateDirectory = File(appContext.cacheDir, UPDATE_DIRECTORY_NAME).apply {
            mkdirs()
        }
        val destinationFile = File(updateDirectory, DOWNLOADED_APK_NAME)
        val temporaryFile = File(updateDirectory, "$DOWNLOADED_APK_NAME.part")
        if (temporaryFile.exists()) {
            temporaryFile.delete()
        }

        val connection = openGetConnection(downloadUrl)

        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IOException("Shared APK request failed with HTTP ${connection.responseCode}.")
            }

            connection.inputStream.use { input ->
                temporaryFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (destinationFile.exists()) {
                destinationFile.delete()
            }
            if (!temporaryFile.renameTo(destinationFile)) {
                temporaryFile.copyTo(destinationFile, overwrite = true)
                temporaryFile.delete()
            }

            val fileSizeBytes = connection.contentLengthLong.takeIf { it > 0 } ?: destinationFile.length()
            return DownloadedApk(
                file = destinationFile,
                fileSizeBytes = fileSizeBytes,
            )
        } finally {
            connection.disconnect()
        }
    }

    private data class DownloadedApk(
        val file: File,
        val fileSizeBytes: Long,
    )

    private data class RemoteUpdateManifest(
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val apkUrl: String,
        val pageUrl: String,
        val fileSizeBytes: Long? = null,
    )

    private fun fetchUpdateManifest(): RemoteUpdateManifest? {
        if (BuildConfig.APP_UPDATE_MANIFEST_URL.isBlank()) {
            return null
        }

        val connection = openGetConnection(BuildConfig.APP_UPDATE_MANIFEST_URL)
        return try {
            connection.connect()
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                return null
            }
            if (connection.responseCode !in 200..299) {
                throw IOException("Update manifest request failed with HTTP ${connection.responseCode}.")
            }

            val payload = connection.inputStream.bufferedReader().use { it.readText() }
            parseUpdateManifest(payload)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseUpdateManifest(payload: String): RemoteUpdateManifest {
        val jsonObject = JSONObject(payload)
        val packageName = jsonObject.optString("packageName").trim()
        val versionName = jsonObject.optString("versionName").trim()
        val versionCode = jsonObject.optLong("versionCode")
        val apkUrl = normalizeGoogleDriveUrl(
            jsonObject.optString("apkUrl").trim().ifBlank { BuildConfig.APP_UPDATE_APK_URL }
        )
        val pageUrl = jsonObject.optString("pageUrl").trim().ifBlank { BuildConfig.APP_UPDATE_PAGE_URL }
        val fileSizeBytes = jsonObject.optLong("fileSizeBytes").takeIf { it > 0L }

        if (packageName.isBlank() || versionName.isBlank() || versionCode <= 0L || apkUrl.isBlank()) {
            throw IOException("Update manifest is missing one or more required fields.")
        }

        return RemoteUpdateManifest(
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            apkUrl = apkUrl,
            pageUrl = pageUrl,
            fileSizeBytes = fileSizeBytes,
        )
    }

    private fun checkForUpdateByDownloadingApk(installedVersion: AppVersionInfo): UpdateCheckResult {
        val downloadedApk = downloadRemoteApk(BuildConfig.APP_UPDATE_APK_URL)
        val remoteVersion = readArchiveVersionInfo(downloadedApk.file)
            ?: throw IOException("The shared file is not a readable Android APK.")

        if (remoteVersion.packageName != appContext.packageName) {
            downloadedApk.file.delete()
            return UpdateCheckResult.Failed(
                "The shared APK belongs to ${remoteVersion.packageName}, not ${appContext.packageName}."
            )
        }

        if (remoteVersion.versionCode <= installedVersion.versionCode) {
            downloadedApk.file.delete()
            return UpdateCheckResult.UpToDate(
                remoteVersionName = remoteVersion.versionName,
                remoteVersionCode = remoteVersion.versionCode,
                pageUrl = BuildConfig.APP_UPDATE_PAGE_URL,
            )
        }

        downloadedApk.file.delete()
        return UpdateCheckResult.UpdateAvailable(
            remoteVersion = remoteVersion.copy(fileSizeBytes = downloadedApk.fileSizeBytes),
            downloadUrl = BuildConfig.APP_UPDATE_APK_URL,
            pageUrl = BuildConfig.APP_UPDATE_PAGE_URL,
        )
    }

    private fun openGetConnection(url: String): HttpURLConnection {
        return (URL(normalizeGoogleDriveUrl(url)).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 120_000
            instanceFollowRedirects = true
        }
    }

    private fun normalizeGoogleDriveUrl(url: String): String {
        if (url.isBlank()) {
            return url
        }

        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        val host = uri.host.orEmpty().lowercase()
        if (!host.contains("drive.google.com")) {
            return url
        }

        val fileId = extractGoogleDriveFileId(uri) ?: return url
        return "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
    }

    private fun extractGoogleDriveFileId(uri: Uri): String? {
        uri.getQueryParameter("id")?.takeIf { it.isNotBlank() }?.let { return it }

        val pathSegments = uri.pathSegments
        val fileIndex = pathSegments.indexOf("file")
        if (fileIndex >= 0 && fileIndex + 2 < pathSegments.size && pathSegments[fileIndex + 1] == "d") {
            return pathSegments[fileIndex + 2]
        }

        val decodedPath = runCatching { URLDecoder.decode(uri.toString(), "UTF-8") }.getOrNull().orEmpty()
        val marker = "/file/d/"
        val markerIndex = decodedPath.indexOf(marker)
        if (markerIndex >= 0) {
            val startIndex = markerIndex + marker.length
            val trailingPath = decodedPath.substring(startIndex)
            return trailingPath.substringBefore('/').substringBefore('?').takeIf { it.isNotBlank() }
        }

        return null
    }

    companion object {
        private const val UPDATE_DIRECTORY_NAME = "updates"
        private const val DOWNLOADED_APK_NAME = "patentia-update.apk"
    }
}