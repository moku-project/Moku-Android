package dev.moku.mobile.extension

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.getOrNull

// Some extensions read SharedPreferences through Injekt.get<Application>() instead of taking a Context.
object RuntimeBootstrap {
    fun ensure(context: Context) {
        val app = context.applicationContext as Application
        if (Injekt.getOrNull<Application>() == null) {
            Injekt.addSingletonFactory { app }
        }
        if (Injekt.getOrNull<NetworkHelper>() == null) {
            Injekt.addSingletonFactory { NetworkHelper(app) }
        }
        if (Injekt.getOrNull<Json>() == null) {
            Injekt.addSingletonFactory { Json { ignoreUnknownKeys = true } }
        }
        if (Injekt.getOrNull<JavaScriptEngine>() == null) {
            Injekt.addSingletonFactory { JavaScriptEngine(app) }
        }
    }
}
