package dev.moku.mobile.remote

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens a [Tracker.authUrl] in a real browser tab — required rather than the app's own
 * WebView because these are third-party (AniList/MAL) login pages the user must trust with
 * their real credentials. See [RemoteTrackingRepository]'s kdoc for what happens after: MAL's
 * redirect lands on the *server's* callback route and needs no app-side handling beyond
 * polling `getTrackers()`; AniList's implicit-grant redirect has nowhere to go but back to
 * the user, who copies the resulting URL/token and pastes it into [RemoteTrackingRepository.login].
 */
object TrackerAuthLauncher {
    fun launch(context: Context, authUrl: String) {
        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(authUrl))
    }
}
