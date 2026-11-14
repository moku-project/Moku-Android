package dev.moku.mobile.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

suspend fun BackendPageImage.fetch(client: OkHttpClient): Response {
    open?.let { return it() }
    val response = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(imageUrl).headers(headers.toHeaders()).build()).execute()
    }
    if (!response.isSuccessful) {
        response.close()
        throw IOException("HTTP ${response.code}")
    }
    return response
}

fun Response.imageExtension(): String = when (body.contentType()?.subtype) {
    "png" -> "png"
    "webp" -> "webp"
    "gif" -> "gif"
    "avif" -> "avif"
    else -> "jpg"
}
