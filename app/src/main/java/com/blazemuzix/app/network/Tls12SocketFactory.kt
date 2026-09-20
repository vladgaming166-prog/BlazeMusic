package com.blazemuzix.app.network

import android.os.Build
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Android 4.4 supports TLS 1.2 but does not enable it by default, while every
 * modern music API requires it. This factory forces TLS 1.2 on API < 21 only.
 */
class Tls12SocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
        patch(delegate.createSocket(s, host, port, autoClose))

    override fun createSocket(host: String?, port: Int): Socket = patch(delegate.createSocket(host, port))

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        patch(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress?, port: Int): Socket = patch(delegate.createSocket(host, port))

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        patch(delegate.createSocket(address, port, localAddress, localPort))

    private fun patch(socket: Socket): Socket {
        if (socket is SSLSocket) {
            val supported = socket.supportedProtocols
            val wanted = arrayOf("TLSv1.2", "TLSv1.1", "TLSv1").filter { it in supported }
            if (wanted.isNotEmpty()) socket.enabledProtocols = wanted.toTypedArray()
        }
        return socket
    }

    companion object {
        @Volatile
        private var installed = false

        fun installIfNeeded() {
            if (installed || Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) return
            synchronized(this) {
                if (installed) return
                try {
                    val context = SSLContext.getInstance("TLSv1.2")
                    context.init(null, null, null)
                    HttpsURLConnection.setDefaultSSLSocketFactory(Tls12SocketFactory(context.socketFactory))
                } catch (_: Exception) {
                    // Fall back to platform defaults; requests may fail with a clear network error.
                }
                installed = true
            }
        }
    }
}
