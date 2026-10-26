package com.bingemote

import org.junit.Assert.assertTrue
import org.junit.Test

/** The mouse WebSocket must ship with Nagle off: every socket the factory makes
 * reports TCP_NODELAY set (OkHttp connects the socket later, so this is the only
 * place the option can be applied). */
class NoDelaySocketFactoryTest {
    @Test
    fun createdSocketsHaveNoDelay() {
        NoDelaySocketFactory().createSocket().use { s ->
            assertTrue("factory socket missing TCP_NODELAY", s.tcpNoDelay)
        }
    }
}
