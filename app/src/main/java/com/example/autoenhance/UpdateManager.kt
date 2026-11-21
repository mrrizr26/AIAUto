package com.example.autoenhance

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases theke update check, download ar install.
 * Repo ta build somoy BuildConfig.GITHUB_REPO te boshe jay (GitHub Actions theke).
 */
object UpdateManager {

    class Info(
        val versionCode: Long,
        val versionName: String,
        val notes: String,
        val apkUrl: String,
        val size: Long
    )

    sealed class CheckResult {
        class UpToDate(val latestName: String) : CheckResult()
        class Available(val info: Info) : CheckResult()
        class Error(val message: String) : CheckResult()
    }

    private const val PREFS = "settings"
    const val KEY_AUTO_CHECK = "auto_check"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_AVAIL_CODE = "avail_code"
    private const val KEY_AVAIL_NAME = "avail_name"

    // ---------------------------------------------------------------- app info

    fun repo(): String = BuildConfig.GITHUB_REPO

    fun currentVersionCode(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    fun currentVersionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    // ---------------------------------------------------------------- prefs

    fun isAutoCheckEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_CHECK, true)

    fun setAutoCheckEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_CHECK, on).apply()
    }

    /** Last check er por 3 ghonta na gele abar network call korbe na. */
    fun shouldAutoCheckNow(ctx: Context): Boolean {
        if (!isAutoCheckEnabled(ctx) || repo().isBlank()) return false
        val last = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last > 3L * 60 * 60 * 1000
    }

    fun saveResult(ctx: Context, r: CheckResult) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        when (r) {
            is CheckResult.Available -> {
                e.putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                e.putLong(KEY_AVAIL_CODE, r.info.versionCode)
                e.putString(KEY_AVAIL_NAME, r.info.versionName)
            }
            is CheckResult.UpToDate -> {
                e.putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                e.putLong(KEY_AVAIL_CODE, 0L)
            }
            is CheckResult.Error -> Unit
        }
        e.apply()
    }

    /** Main screen banner er text, na thakle null. */
    fun pendingUpdateName(ctx: Context): String? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val code = p.getLong(KEY_AVAIL_CODE, 0L)
        return if (code > currentVersionCode(ctx)) p.getString(KEY_AVAIL_NAME, "new version") else null
    }

    // ---------------------------------------------------------------- check

    /** Blocking: background thread e call korun. */
    fun checkLatest(ctx: Context): CheckResult {
        val repo = repo()
        if (repo.isBlank()) {
            return CheckResult.Error(
                "Update source set nai. GitHub Actions diye build kora APK use korun."
            )
        }
        return try {
            val conn = URL("https://api.github.com/repos/$repo/releases/latest")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "AutoEnhance-Updater")

            when (val code = conn.responseCode) {
                404 -> return CheckResult.Error(
                    "Kono release paoa jayni. Repo ki Public? Actions build ki green hoyeche?"
                )
                403, 429 -> return CheckResult.Error(
                    "GitHub request limit hoye geche. Kichukkhon pore abar try korun."
                )
                !in 200..299 -> return CheckResult.Error("Server error ($code)")
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(body)
            val tag = j.optString("tag_name")
            val remoteCode = Regex("(\\d+)\\z").find(tag)?.value?.toLongOrNull()
                ?: return CheckResult.Error("Release tag bujhte parlam na: $tag")
            val name = j.optString("name").ifBlank { tag }

            var apkUrl = ""
            var size = 0L
            val assets = j.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url")
                        size = a.optLong("size")
                        break
                    }
                }
            }
            if (apkUrl.isBlank()) return CheckResult.Error("Release e kono APK file nai.")

            if (remoteCode > currentVersionCode(ctx)) {
                CheckResult.Available(Info(remoteCode, name, j.optString("body"), apkUrl, size))
            } else {
                CheckResult.UpToDate(name)
            }
        } catch (e: Exception) {
            CheckResult.Error("Internet/connection problem: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- download

    /** Blocking. Progress 0..100. */
    fun download(ctx: Context, info: Info, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "update-${info.versionCode}.apk")

        val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "AutoEnhance-Updater")
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("Download fail (${conn.responseCode})")
        }
        val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else info.size
        var done = 0L
        var lastPct = -1
        conn.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        }
        if (info.size > 0 && out.length() != info.size) {
            out.delete()
            throw IllegalStateException("Download adhura theke geche, abar try korun.")
        }
        return out
    }

    /** null = thik ache. Noile error message. "SIGNATURE" = key mile ni. */
    fun verify(ctx: Context, file: File): String? {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: return "Download kora APK kharap."
        if (archive.packageName != ctx.packageName) return "Eta ei app er APK na."
        if (archive.longVersionCode <= currentVersionCode(ctx)) return "Ei version already install kora ache."

        val current = pm.getPackageInfo(ctx.packageName, flags)
        val a = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        val b = current.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        if (a == null || b == null || a != b) return "SIGNATURE"
        return null
    }

    // ---------------------------------------------------------------- install

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun install(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }
}
