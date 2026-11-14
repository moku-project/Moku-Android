package dev.moku.mobile.remote

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/** Opens tracker sign-in in a browser tab, not a WebView, since users are trusting it with their password. */
object TrackerAuthLauncher {
    fun launch(context: Context, authUrl: String) {
        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(authUrl))
    }
}
