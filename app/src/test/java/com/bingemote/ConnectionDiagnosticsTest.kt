package com.bingemote
import org.junit.Assert.*
import org.junit.Test
class ConnectionDiagnosticsTest {
    @Test fun rejectedPairingWinsEvenWhenBothEndpointsFail() {
        assertEquals("PC rejected this phone's pairing", ConnectionDiagnostics.title(true,true,true,false,"192.168.1.2"))
    }
    @Test fun addressIsShownWithoutGuessingPcIsOff() {
        assertEquals("No connection at 192.168.1.2", ConnectionDiagnostics.title(false,false,true,false,"192.168.1.2"))
    }
    @Test fun onlyExceptionTypeAndCodeNotMessageAreExposed() {
        val e=HelperClient.ConnectTimeout("secret text",java.net.UnknownHostException("credential"))
        assertEquals("UnknownHostException",ConnectionDiagnostics.helperFailure(e))
        assertEquals("HTTP 403: pairing token rejected",ConnectionDiagnostics.helperFailure(HelperClient.BadToken("credential",403)))
        assertEquals("HTTP 503",ConnectionDiagnostics.failureKind(Vlc.VlcError("credential",503)))
    }
}
