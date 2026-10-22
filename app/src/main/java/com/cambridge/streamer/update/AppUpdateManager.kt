package com.cambridge.streamer.update

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.cambridge.streamer.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-App Over-The-Air (OTA) Auto-Updater for CamBridge Pro.
 *
 * Connects directly to the GitHub repository to check for updates.
 * If a newer version is published on GitHub, downloads the APK in the
 * background and prompts the Android Package Installer with 1 tap.
 */
object AppUpdateManager {

    private const val TAG = "AppUpdateManager"

    // Raw GitHub URL for version metadata
    const val VERSION_CHECK_URL =
        "https://raw.githubusercontent.com/dubeyjideepak/cambridge-pro/main/version.json"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val changelog: String
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Checks GitHub for a newer version of the APK.
     */
    fun checkForUpdate(
        context: Context,
        onResult: (UpdateInfo?) -> Unit
    ) {
        Thread {
            try {
                val url = URL(VERSION_CHECK_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.requestMethod = "GET"
                conn.useCaches = false

                if (conn.responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(jsonStr)

                    val remoteVersionCode: Int = json.optInt("versionCode", 0)
                    val remoteVersionName: String = json.optString("versionName", "")
                    val apkUrl: String = json.optString("apkUrl", "")
                    val changelog: String = json.optString("changelog", "Bug fixes and performance improvements")

                    val currentCode: Int = BuildConfig.VERSION_CODE

                    Log.d(TAG, "Current Version: $currentCode, Remote Version: $remoteVersionCode")

                    if (remoteVersionCode > currentCode && apkUrl.isNotEmpty()) {
                        val info = UpdateInfo(remoteVersionCode, remoteVersionName, apkUrl, changelog)
                        mainHandler.post { onResult(info) }
                        return@Thread
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check skipped or offline: ${e.message}")
            }
            mainHandler.post { onResult(null) }
        }.start()
    }

    /**
     * Shows an update dialog when a newer version is detected.
     */
    fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        AlertDialog.Builder(activity)
            .setTitle("🚀 Update Available (v${info.versionName})")
            .setMessage("A new version of CamBridge Pro is ready on GitHub!\n\nWhat's New:\n${info.changelog}\n\nUpdate now directly from the server?")
            .setPositiveButton("Update Now") { _, _ ->
                checkInstallPermissionAndDownload(activity, info)
            }
            .setNegativeButton("Later", null)
            .setCancelable(true)
            .show()
    }

    private fun checkInstallPermissionAndDownload(activity: Activity, info: UpdateInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(activity)
                    .setTitle("Permission Needed")
                    .setMessage("Android requires permission to install updates directly for CamBridge Pro. Please enable 'Allow from this source' on the next screen.")
                    .setPositiveButton("Allow") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${activity.packageName}")
                        }
                        activity.startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
        }
        downloadAndInstall(activity, info)
    }

    private fun downloadAndInstall(activity: Activity, info: UpdateInfo) {
        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(activity).apply {
            setTitle("Downloading Update")
            setMessage("Downloading v${info.versionName} from GitHub...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = false
            max = 100
            setCancelable(false)
            show()
        }

        Thread {
            try {
                val url = URL(info.apkUrl)
                var conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true

                // Follow GitHub releases redirects if needed (CDN redirects)
                var status = conn.responseCode
                var redirectCount = 0
                while ((status == HttpURLConnection.HTTP_MOVED_TEMP ||
                            status == HttpURLConnection.HTTP_MOVED_PERM ||
                            status == 307 || status == 308) && redirectCount < 5) {
                    val newUrl = conn.getHeaderField("Location")
                    conn = URL(newUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 30000
                    status = conn.responseCode
                    redirectCount++
                }

                val totalLength = conn.contentLength
                val downloadDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                val outputFile = File(downloadDir, "CamBridgePro_v${info.versionName}.apk")

                if (outputFile.exists()) outputFile.delete()

                conn.inputStream.use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var downloaded = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            if (totalLength > 0) {
                                val progress = ((downloaded * 100) / totalLength).toInt()
                                mainHandler.post { progressDialog.progress = progress }
                            }
                        }
                    }
                }

                mainHandler.post {
                    progressDialog.dismiss()
                    launchPackageInstaller(activity, outputFile)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Download error", e)
                mainHandler.post {
                    progressDialog.dismiss()
                    Toast.makeText(activity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun launchPackageInstaller(activity: Activity, apkFile: File) {
        try {
            val apkUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed launching package installer", e)
            Toast.makeText(activity, "Installer error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
