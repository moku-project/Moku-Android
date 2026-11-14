package dev.moku.mobile.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import dev.moku.mobile.BuildConfig
import dev.moku.mobile.R
import androidx.core.app.NotificationCompat
import dev.moku.mobile.ui.components.MokuToast
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.IOException

data class AppRelease(val version: String, val notes: String, val pageUrl: String, val apkUrl: String, val apkSize: Long)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Downloading(val release: AppRelease, val fraction: Float) : UpdateState
    data class Installing(val release: AppRelease) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Updates from GitHub releases: picks the APK for this phone's CPU and hands it to Android's installer. Only an
 * APK signed with the same key installs, so data carries over.
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val RELEASES = "https://api.github.com/repos/moku-project/Moku-Android/releases/latest"
    private const val PREFS = "moku_updates"
    private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 4202

    private val network: NetworkHelper by injectLazy()
    private val json: Json by injectLazy()

    /** Debug builds are a separate package signed with another key, so a release could never update them. */
    val enabled: Boolean = !BuildConfig.DEBUG

    private val _available = MutableStateFlow<AppRelease?>(null)
    val available: StateFlow<AppRelease?> = _available

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    fun lastCheckedAt(context: Context): Long? =
        prefs(context).getLong("checkedAt", 0L).takeIf { it > 0 }

    /** Background check, at most twice a day. */
    suspend fun checkQuietly(context: Context) {
        if (!enabled) return
        val last = lastCheckedAt(context) ?: 0L
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS) {
            if (_available.value == null) _available.value = cachedRelease(context)
            return
        }
        runCatching { check(context) }.onFailure { Log.i(TAG, "update check failed: ${it.message}") }
    }

    suspend fun check(context: Context): AppRelease? = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Checking
        try {
            val release = fetchLatest()?.takeIf { isNewer(it.version, BuildConfig.VERSION_NAME) }
            prefs(context).edit().putLong("checkedAt", System.currentTimeMillis()).putString("release", release?.let(::encode)).apply()
            _available.value = release
            _state.value = UpdateState.Idle
            release
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: "Couldn't check for updates")
            throw e
        }
    }

    /** Whether Android will let Moku hand over an APK; if not, [openInstallPermission] shows the switch. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    suspend fun install(context: Context, release: AppRelease) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        try {
            val apk = download(app, release)
            _state.value = UpdateState.Installing(release)
            commit(app, apk)
        } catch (e: Exception) {
            Log.w(TAG, "update failed", e)
            _state.value = UpdateState.Failed(e.message ?: "The update didn't download")
        }
    }

    private fun fetchLatest(): AppRelease? {
        val request = Request.Builder().url(RELEASES).header("Accept", "application/vnd.github+json").build()
        network.client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("GitHub returned HTTP ${response.code}")
            val root = json.parseToJsonElement(response.body.string()).jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
            val assets = root["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
            val apks = assets.filter { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true }
            fun named(variant: String) = apks.firstOrNull { a ->
                val name = a["name"]!!.jsonPrimitive.content
                name.endsWith("-$variant.apk") || name.endsWith("-$variant-release.apk")
            }
            // Releases ship one APK per ABI plus a universal one; the matching ABI is a fraction of the download.
            // A lone APK is taken as universal; with several and no match, guessing could pick another CPU's.
            val asset = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { named(it) } ?: named("universal") ?: apks.singleOrNull() ?: return null
            return AppRelease(
                version = tag.removePrefix("v"),
                notes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
                pageUrl = root["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                apkUrl = asset["browser_download_url"]!!.jsonPrimitive.content,
                apkSize = asset["size"]?.jsonPrimitive?.longOrNull ?: -1L,
            )
        }
    }

    private fun download(context: Context, release: AppRelease): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "moku-${release.version}.apk")
        if (target.length() > 0 && target.length() == release.apkSize) return target
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, target.name + ".part")
        network.client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
            val total = response.body.contentLength().takeIf { it > 0 } ?: release.apkSize
            var read = 0L
            _state.value = UpdateState.Downloading(release, 0f)
            response.body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        if (total > 0) _state.value = UpdateState.Downloading(release, read.toFloat() / total)
                    }
                }
            }
            if (release.apkSize > 0 && read != release.apkSize) throw IOException("The download was cut short")
        }
        if (!part.renameTo(target)) throw IOException("Couldn't save the update")
        return target
    }

    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("moku.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context, id,
                Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    internal fun onInstallResult(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                // Android only lets Moku open the prompt itself while it's on screen; otherwise the notification does.
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                notifyConfirm(context, confirm)
            }
            // On success Android replaces this process, so there's nothing to do here.
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val reason = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Update cancelled"
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "This update isn't signed like the installed app, so Android refused it"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough space to install the update"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This update doesn't support this phone"
                    else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The update didn't install"
                }
                _state.value = UpdateState.Failed(reason)
                MokuToast.show(reason, long = true)
            }
        }
    }

    private fun notifyConfirm(context: Context, confirm: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(context, 0, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ph_download_simple_bold)
            .setContentTitle("Moku update ready")
            .setContentText("Tap to install it")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    internal fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun encode(r: AppRelease) = org.json.JSONObject()
        .put("version", r.version).put("notes", r.notes).put("pageUrl", r.pageUrl).put("apkUrl", r.apkUrl).put("apkSize", r.apkSize)
        .toString()

    private fun cachedRelease(context: Context): AppRelease? = runCatching {
        val o = org.json.JSONObject(prefs(context).getString("release", null) ?: return null)
        AppRelease(o.getString("version"), o.getString("notes"), o.getString("pageUrl"), o.getString("apkUrl"), o.getLong("apkSize"))
    }.getOrNull()?.takeIf { isNewer(it.version, BuildConfig.VERSION_NAME) }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AppUpdater.onInstallResult(context, intent)
}
