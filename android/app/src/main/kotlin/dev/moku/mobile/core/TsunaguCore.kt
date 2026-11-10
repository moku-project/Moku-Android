package dev.moku.mobile.core

import android.content.Context
import android.util.Log
import dev.moku.mobile.BuildConfig
import dev.moku.mobile.extension.RuntimeBootstrap
import dev.moku.tsunagu.embedded.Embedded
import io.grpc.InsecureServerCredentials
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.okhttp.OkHttpServerBuilder
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

private const val TAG = "TsunaguCore"

// getLoopbackAddress() is ::1 on Android; the Go core dials IPv4.
private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

data class CoreEndpoint(val baseUrl: String, val token: String)

// Any app on the device can reach loopback ports, so both servers require a per-launch token.
object TsunaguCore {
    private var host: Server? = null
    private var endpoint: CoreEndpoint? = null

    /** Starts the core if needed. Blocks while Tsunagu opens its database, so call it off the main thread. */
    @Synchronized
    fun start(context: Context): CoreEndpoint {
        endpoint?.let { return it }
        val app = context.applicationContext
        RuntimeBootstrap.ensure(app)

        val hostToken = randomToken()
        val server = OkHttpServerBuilder
            .forPort(InetSocketAddress(LOOPBACK, 0), InsecureServerCredentials.create())
            .executor(Executors.newCachedThreadPool())
            // Covers and long-strip pages arrive whole in one message.
            .maxInboundMessageSize(64 shl 20)
            .addService(ServerInterceptors.intercept(ExtensionHost(app), RequireToken(hostToken)))
            .build()
            .start()
        host = server

        val dataDir = File(app.filesDir, "tsunagu").apply { mkdirs() }
        try {
            Embedded.start(dataDir.absolutePath, "${LOOPBACK.hostAddress}:${server.port}", hostToken)
        } catch (e: Exception) {
            server.shutdownNow()
            host = null
            throw e
        }

        val started = CoreEndpoint(Embedded.baseURL(), Embedded.apiToken())
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "core at ${started.baseUrl} token=${started.token}")
        }
        endpoint = started
        return started
    }

    @Synchronized
    fun stop() {
        runCatching { Embedded.stop() }.onFailure { Log.w(TAG, "stopping core", it) }
        host?.shutdownNow()
        host = null
        endpoint = null
    }

    private fun randomToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private class RequireToken(token: String) : ServerInterceptor {
        private val expected = "Bearer $token".toByteArray()
        private val key = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

        override fun <Req, Resp> interceptCall(
            call: ServerCall<Req, Resp>,
            headers: Metadata,
            next: ServerCallHandler<Req, Resp>,
        ): ServerCall.Listener<Req> {
            val got = headers.get(key)?.toByteArray() ?: ByteArray(0)
            if (!MessageDigest.isEqual(got, expected)) {
                call.close(Status.UNAUTHENTICATED, Metadata())
                return object : ServerCall.Listener<Req>() {}
            }
            return next.startCall(call, headers)
        }
    }
}
