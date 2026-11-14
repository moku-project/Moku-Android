package dev.moku.mobile.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit

/** Asks once, the first time a download needs notifications; asking at launch only gets a reflexive no. */
object NotificationPermission {
    private const val PREFS = "moku_permissions"
    private const val KEY_ASKED = "notifications_asked"

    @Volatile private var launcher: ActivityResultLauncher<String>? = null

    fun register(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    }

    fun unregister() {
        launcher = null
    }

    fun askOnce(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED, false)) return
        val l = launcher ?: return
        prefs.edit { putBoolean(KEY_ASKED, true) }
        Handler(Looper.getMainLooper()).post { runCatching { l.launch(Manifest.permission.POST_NOTIFICATIONS) } }
    }
}
