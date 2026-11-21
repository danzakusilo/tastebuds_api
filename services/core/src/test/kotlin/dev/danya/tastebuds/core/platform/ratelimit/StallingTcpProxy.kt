package dev.danya.tastebuds.core.platform.ratelimit

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Loopback TCP proxy to [upstreamHost]:[upstreamPort] for fail-open tests. [stall] stops forwarding in both
 * directions and keeps every socket open (a Redis that hangs on an open connection); [close] closes every socket
 * (a dropped connection). Never pause the shared Redis container instead: other tests use it.
 */
internal class StallingTcpProxy(
    private val upstreamHost: String,
    private val upstreamPort: Int,
) : AutoCloseable {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    private val sockets = CopyOnWriteArrayList<Socket>()

    @Volatile
    private var stalled = false

    val port: Int = server.localPort

    init {
        Thread.ofVirtual().start { acceptLoop() }
    }

    fun stall() {
        stalled = true
    }

    override fun close() {
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
    }

    private fun acceptLoop() {
        try {
            while (true) {
                val client = server.accept()
                sockets += client
                val upstream = Socket(upstreamHost, upstreamPort)
                sockets += upstream
                Thread.ofVirtual().start { pump(client.getInputStream(), upstream.getOutputStream()) }
                Thread.ofVirtual().start { pump(upstream.getInputStream(), client.getOutputStream()) }
            }
        } catch (_: IOException) {
            // The server socket was closed.
        }
    }

    private fun pump(
        from: InputStream,
        to: OutputStream,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val read = from.read(buffer)
                if (read < 0) return
                if (!stalled) {
                    to.write(buffer, 0, read)
                    to.flush()
                }
            }
        } catch (_: IOException) {
            // Expected when close() closes the sockets.
        }
    }

    private companion object {
        const val BACKLOG = 50
        const val BUFFER_SIZE = 8 * 1024
    }
}
