package app.maestri.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import app.maestri.remote.BuildConfig
import app.maestri.remote.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The update feature: finds a newer published build, tells the user about it, and hands
 * the APK to the system installer.
 *
 * Rules that shaped this class:
 *  - the manifest and the APK come from a PUBLIC GitHub Releases URL: no token, no relay,
 *    no pairing — the update path never touches the user's host;
 *  - a check happens at process start and every [CHECK_INTERVAL_MS] while the app is in
 *    the foreground; there is no foreground service and no boot receiver;
 *  - every failure is silent: no toast, no error banner, no retry storm;
 *  - the APK is only handed to the installer after its SHA-256 matches the manifest;
 *  - the app never installs anything by itself: the system installer always decides.
 */
class UpdateController(
    context: Context,
    private val client: UpdateClient = UpdateClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val appContext = context.applicationContext
    private val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The newer build found, if any. */
    private val _release = MutableStateFlow<ReleaseInfo?>(null)
    val release: StateFlow<ReleaseInfo?> = _release.asStateFlow()

    /** Where the install flow stands. */
    private val _install = MutableStateFlow<InstallState>(InstallState.Idle)
    val install: StateFlow<InstallState> = _install.asStateFlow()

    /** Whether the in-app banner is showing (the user can dismiss it). */
    private val _bannerVisible = MutableStateFlow(false)
    val bannerVisible: StateFlow<Boolean> = _bannerVisible.asStateFlow()

    init {
        ensureChannel()
    }

    sealed interface InstallState {
        data object Idle : InstallState
        data class Downloading(val release: ReleaseInfo) : InstallState
        data class Ready(val release: ReleaseInfo, val file: File) : InstallState
        data class NeedsPermission(val release: ReleaseInfo, val file: File) : InstallState
        data class Failed(val release: ReleaseInfo) : InstallState
    }

    // -------------------------------------------------------------- checking

    /**
     * Starts the update watch: one check now, then one every [CHECK_INTERVAL_MS].
     * The check runs on this class's own IO scope — never on the main thread — and
     * every failure is silent. Idempotent: the UI may compose again, the watch starts once.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            while (true) {
                runCatching { check() }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /** One check. Silent on every failure — the app never nags about its own network. */
    fun check() {
        if (!hasNetwork()) {
            Log.d(TAG, "check: offline, skipped")
            return
        }
        scope.launch {
            val info = client.latest()
            if (info == null) {
                Log.d(TAG, "check: no manifest (offline or nothing published yet)")
                return@launch
            }
            if (!isNewer(info.versionCode, BuildConfig.VERSION_CODE)) {
                Log.d(TAG, "check: up to date (installed ${BuildConfig.VERSION_CODE})")
                return@launch
            }
            val known = _release.value?.versionCode
            _release.value = info
            Log.d(TAG, "check: newer build ${info.versionCode} (${info.versionName}) available")
            if (known != info.versionCode) {
                _bannerVisible.value = prefs.getInt(KEY_DISMISSED, -1) != info.versionCode
                notify(info)
            }
        }
    }

    /** Hides the banner for this version; the notification and the install path stay. */
    fun dismiss() {
        val info = _release.value ?: return
        prefs.edit().putInt(KEY_DISMISSED, info.versionCode).apply()
        _bannerVisible.value = false
    }

    // -------------------------------------------------------------- installing

    /**
     * Downloads the APK (unless it is already cached) and opens the system installer.
     * The digest is verified before the installer is ever started.
     */
    fun install() {
        val info = _release.value ?: return
        scope.launch { install(info) }
    }

    /**
     * The notification path: the app may have been killed since the check, so the
     * manifest is read again before the download starts.
     */
    fun installFromNotification() {
        Log.d(TAG, "install: asked by the notification")
        scope.launch {
            val info = _release.value ?: client.latest()?.also { _release.value = it } ?: return@launch
            if (!isNewer(info.versionCode, BuildConfig.VERSION_CODE)) return@launch
            install(info)
        }
    }

    private suspend fun install(info: ReleaseInfo) {
        val cached = apkFile(info)
        val usable = cached.isFile && cached.length() > 0 && client.verify(cached, info.sha256)
        if (usable) {
            Log.d(TAG, "install: cached APK matches the manifest digest")
            _install.value = InstallState.Ready(info, cached)
            openInstaller(cached)
            return
        }
        cached.delete()
        Log.d(TAG, "install: downloading ${info.versionName}")
        _install.value = InstallState.Downloading(info)
        cached.parentFile?.mkdirs()
        if (!client.download(info, cached)) {
            Log.d(TAG, "install: download or digest check failed, aborted")
            _install.value = InstallState.Failed(info)
            return
        }
        Log.d(TAG, "install: digest verified, opening the system installer")
        _install.value = InstallState.Ready(info, cached)
        openInstaller(cached)
    }

    /**
     * What the "Atualizar" button does: open the installer when the app is allowed to
     * install packages, otherwise send the user to the system screen that grants it.
     */
    fun onUpdatePressed() {
        if (_install.value is InstallState.NeedsPermission &&
            appContext.packageManager.canRequestPackageInstalls()
        ) {
            val file = (_install.value as InstallState.NeedsPermission).file
            _install.value = InstallState.Ready(_release.value ?: return, file)
            openInstaller(file)
            return
        }
        if (_install.value is InstallState.NeedsPermission) {
            openUnknownSourcesSettings()
            return
        }
        install()
    }

    /** The system screen where the user allows this app to install packages. */
    fun openUnknownSourcesSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
    }

    private fun openInstaller(file: File) {
        main.post {
            if (!appContext.packageManager.canRequestPackageInstalls()) {
                Log.d(TAG, "install: the system asks first for 'install unknown apps'")
                _install.value = InstallState.NeedsPermission(_release.value ?: return@post, file)
                return@post
            }
            val uri = runCatching {
                FileProvider.getUriForFile(appContext, appContext.packageName + ".fileprovider", file)
            }.getOrNull() ?: return@post
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { appContext.startActivity(intent) }
                .onSuccess { Log.d(TAG, "install: system installer opened") }
                .onFailure { Log.w(TAG, "install: installer refused: ${it.message}") }
        }
    }

    private fun apkFile(info: ReleaseInfo): File =
        File(File(appContext.cacheDir, "updates"), "maestri-remote-${info.versionCode}.apk")

    // -------------------------------------------------------------- notification

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Atualizações", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { setShowBadge(true) },
        )
    }

    private fun notify(info: ReleaseInfo) {
        if (Build.VERSION.SDK_INT >= 33 &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "notify: POST_NOTIFICATIONS not granted, banner only")
            return // honest denial: the in-app banner is the only surface left
        }
        if (prefs.getInt(KEY_NOTIFIED, -1) == info.versionCode) {
            Log.d(TAG, "notify: version ${info.versionCode} already announced")
            return
        }
        prefs.edit().putInt(KEY_NOTIFIED, info.versionCode).apply()
        val body = "Versão ${info.versionName}"
        val builder = NotificationCompat.Builder(appContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Nova versão disponível")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(installIntent(NOTIFICATION_ID))
            .addAction(
                android.R.drawable.stat_sys_download,
                "Atualizar",
                installIntent(NOTIFICATION_ID + 1),
            )
        runCatching { nm.notify(NOTIFICATION_ID, builder.build()) }
            .onFailure { Log.w(TAG, "notify failed: ${it.message}") }
            .onSuccess { Log.d(TAG, "notified version ${info.versionCode}") }
    }

    private fun installIntent(requestCode: Int): PendingIntent = PendingIntent.getActivity(
        appContext,
        requestCode,
        Intent(appContext, MainActivity::class.java)
            .putExtra(EXTRA_INSTALL, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // -------------------------------------------------------------- helpers

    private fun hasNetwork(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return runCatching {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(false)
    }

    companion object {
        /** Intent extra: the notification asks the app to install the pending update. */
        const val EXTRA_INSTALL = "maestri.update.install"

        const val CHANNEL = "updates"
        const val CHECK_INTERVAL_MS = 30 * 60 * 1000L
        private const val TAG = "QAUpdate"
        private const val NOTIFICATION_ID = 900
        private const val PREFS = "remote.update"
        private const val KEY_DISMISSED = "dismissedVersion"
        private const val KEY_NOTIFIED = "notifiedVersion"
    }
}
