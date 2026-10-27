package com.streambox.nativeapp.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.streambox.nativeapp.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** In-app updates (0.80): the rolling latest-native prerelease IS the update
    channel - one startup popup, one button, download and straight to the
    package installer. The package installs IN PLACE (frozen versionCode +
    the one preview keystore), so there is never an uninstall step.

    Trust rules (the download is an APK - this is the sharp edge):
    - only the GitHub release hosts are ever contacted, over TLS, and the
      download URL from the API response is re-checked against that list
    - the downloaded bytes MUST match the sha256 the release API reports
      before the installer is ever shown; a mismatch deletes the file
    - the hand-off is a FileProvider content URI (never a file URI)

    Storage rule: the APK file never piles up. The installer needs the file
    alive while it works and the install kills this process, so leftovers
    are wiped on every boot and before every download. */
object Updater {
    private const val API =
        "https://api.github.com/repos/dazoarmando29/streambox/releases/tags/latest-native"
    private const val ASSET = "streambox-native.apk"
    private const val DIR = "updates"
    private val HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    data class Release(
        val version: String,
        val digest: String,
        val size: Long,
        val url: String,
        val publishedAt: String,
    )

    /** The published build when it is a REAL update for this install.
        With a versioned release (0.80+ notes carry "versionName: X") only a
        strictly newer build is ever offered - never a same-version rebuild
        and never a downgrade. Old notes without a version fall back to byte
        identity: different bytes = different build = offer. */
    fun check(ctx: Context): Release? {
        return try {
            val o = JSONObject(http(API))
            val assets = o.optJSONArray("assets") ?: return null
            var a: JSONObject? = null
            for (i in 0 until assets.length()) {
                val c = assets.optJSONObject(i) ?: continue
                if (c.optString("name") == ASSET) {
                    a = c
                    break
                }
            }
            if (a == null) return null
            val digest = a.optString("digest", "")
            val url = a.optString("browser_download_url", "")
            if (!digest.startsWith("sha256:") || url.isEmpty() || !allowed(url)) return null
            val rel = Release(
                version = Regex("versionName:\\s*([0-9.]+)").find(o.optString("body"))?.groupValues?.get(1) ?: "",
                digest = digest,
                size = a.optLong("size"),
                url = url,
                publishedAt = o.optString("published_at", ""),
            )
            if (rel.version.isNotEmpty()) {
                if (compareVersions(rel.version, BuildConfig.VERSION_NAME) > 0) rel else null
            } else {
                val self = selfDigest(ctx) ?: return null
                if (rel.digest != self) rel else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Streams the release APK into app storage. The file is only ever
        returned when its sha256 matches the published digest - the caller
        may hand it to the installer and nothing else. */
    fun download(ctx: Context, rel: Release, onProgress: (Int) -> Unit): File? {
        return try {
            if (!allowed(rel.url)) return null
            cleanLeftovers(ctx)
            val dir = File(ctx.filesDir, DIR)
            dir.mkdirs()
            val out = File(dir, ASSET)
            val md = MessageDigest.getInstance("SHA-256")
            val conn = URL(rel.url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 30000
                conn.readTimeout = 60000
                conn.setRequestProperty("User-Agent", "StreamBoxNative")
                if (conn.responseCode !in 200..299) return null
                val total = if (rel.size > 0) rel.size else conn.contentLength.toLong()
                conn.inputStream.use { ins ->
                    java.io.FileOutputStream(out).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                            md.update(buf, 0, n)
                            got += n
                            if (total > 0) {
                                onProgress(((got * 100) / total).toInt().coerceIn(0, 100))
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (hex != rel.digest.removePrefix("sha256:")) {
                out.delete()
                return null
            }
            onProgress(100)
            out
        } catch (e: Exception) {
            null
        }
    }

    /** Straight to the system package installer - it updates the app in
        place over the current one (frozen versionCode, one signing key). */
    fun install(ctx: Context, file: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** API 26+: the per-app Install unknown apps gate. False means the user
        must grant it first - openInstallPermission walks them there. */
    fun installsAllowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + ctx.packageName),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        } catch (e: Exception) {
        }
    }

    /** Wipe leftover update files - called on every boot and before every
        download so the APK never accumulates in storage. */
    fun cleanLeftovers(ctx: Context) {
        try {
            val dir = File(ctx.filesDir, DIR)
            dir.listFiles()?.forEach { if (it.isFile) it.delete() }
        } catch (e: Exception) {
        }
    }

    /** The published date on the family clock (PH) - house date rule. */
    fun prettyDate(iso: String): String = try {
        val inS = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        inS.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val d = inS.parse(iso) ?: return iso.take(10)
        java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Manila") }
            .format(d)
    } catch (e: Exception) {
        iso.take(10)
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrNull(i) ?: 0) - (pb.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return 0
    }

    private var selfCache: String? = null

    private fun selfDigest(ctx: Context): String? {
        selfCache?.let { return it }
        return try {
            val hex = fileDigest(File(ctx.applicationInfo.sourceDir))
            selfCache = hex
            hex
        } catch (e: Exception) {
            null
        }
    }

    private fun fileDigest(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun allowed(url: String): Boolean = try {
        val u = URL(url)
        u.protocol == "https" && HOSTS.contains(u.host.lowercase())
    } catch (e: Exception) {
        false
    }

    private fun http(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", "StreamBoxNative")
            conn.requestMethod = "GET"
            if (conn.responseCode !in 200..299) throw IllegalStateException("http " + conn.responseCode)
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
