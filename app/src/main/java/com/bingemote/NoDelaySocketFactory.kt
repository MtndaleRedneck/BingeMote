package com.bingemote

import java.net.Socket
import javax.net.SocketFactory

/**
 * OkHttp never sets TCP_NODELAY (verified: zero occurrences in the okhttp-4.12.0
 * sources), so a stock client's sockets run with Nagle on - small mouse frames get
 * held behind an unACKed segment and arrive at the PC in bursts, which reads as a
 * jerky cursor. Wrap the default factory and disable Nagle on every socket it makes.
 * OkHttp creates sockets unconnected and connects them later, so the option must be
 * set here in createSocket(), not after connect.
 */
class NoDelaySocketFactory : SocketFactory() {
    private val inner = SocketFactory.getDefault()

    private fun noDelay(s: Socket): Socket {
        s.tcpNoDelay = true
        return s
    }

    override fun createSocket(): Socket = noDelay(inner.createSocket())
    override fun createSocket(host: String?, port: Int): Socket = noDelay(inner.createSocket(host, port))
    override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): Socket =
        noDelay(inner.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: java.net.InetAddress?, port: Int): Socket = noDelay(inner.createSocket(host, port))
    override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): Socket =
        noDelay(inner.createSocket(address, port, localAddress, localPort))
}
