package org.fluidmessages

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Connect through Tor: which connections use Tor, and that nothing goes direct while Tor is on but not ready. */
class TorRouteTest {
    private val internet = listOf(TorRoute.Use.CHAT, TorRoute.Use.COMPANION, TorRoute.Use.BACKUP, TorRoute.Use.PREVIEW, TorRoute.Use.UPDATES)

    @Test fun offChangesNothing() {
        for (use in TorRoute.Use.values()) assertEquals(TorRoute.Way.DIRECT, TorRoute.decide(false, false, use, false, false))
    }

    @Test fun onAndReadySendsInternetConnectionsThroughTor() {
        for (use in internet) assertEquals(use.name, TorRoute.Way.TOR, TorRoute.decide(true, true, use, false, false))
    }

    @Test fun onButNotReadyNeverGoesDirect() {
        for (use in internet) {
            val way = TorRoute.decide(true, false, use, false, false)
            assertEquals(use.name, TorRoute.Way.WAIT, way)
            try {
                TorRoute.proxyFor(way)
                fail("A connection went ahead without Tor")
            } catch (expected: IOException) {
                assertEquals(TorRoute.NOT_READY, expected.message)
            }
        }
    }

    @Test fun throughTorIsSocksOnOrbotsPort() {
        val proxy = TorRoute.proxyFor(TorRoute.Way.TOR)
        assertEquals(Proxy.Type.SOCKS, proxy.type())
        val address = proxy.address() as InetSocketAddress
        assertEquals("127.0.0.1", address.hostString)
        assertEquals(9050, address.port)
        assertEquals(Proxy.NO_PROXY, TorRoute.proxyFor(TorRoute.Way.DIRECT))
    }

    @Test fun callsAreOffUnlessAllowedToConnectDirectly() {
        assertEquals(TorRoute.Way.BLOCKED, TorRoute.decide(true, true, TorRoute.Use.CALL, false, false))
        assertEquals(TorRoute.Way.DIRECT, TorRoute.decide(true, true, TorRoute.Use.CALL, false, true))
        try {
            TorRoute.proxyFor(TorRoute.Way.BLOCKED)
            fail("A call went ahead while held")
        } catch (expected: IOException) {
            assertEquals(TorRoute.CALLS_HELD, expected.message)
        }
    }

    @Test fun yourOwnNetworkConnectsDirectly() {
        for (host in listOf("192.168.1.20", "10.0.0.5", "172.20.1.1", "127.0.0.1", "100.101.102.103", "169.254.3.4", "[fd12::1]", "::1",
            "fe80::1", "nas", "fluid.local", "box.lan", "pi.home.arpa", "host.tail1234.ts.net", "fluid.internal")) {
            assertTrue(host, TorRoute.localHost(host))
            assertEquals(host, TorRoute.Way.DIRECT, TorRoute.decide(true, false, TorRoute.Use.COMPANION, TorRoute.localHost(host), false))
        }
        for (host in listOf("matrix.example.org", "irc.libera.chat", "8.8.8.8", "172.32.0.1", "100.128.0.1", "2001:db8::1",
            "abcdefghijklmnop.onion", "fluid.example.com", null)) {
            assertFalse(host.toString(), TorRoute.localHost(host))
        }
    }

    @Test fun ipv4OnlyParsesDottedAddresses() {
        assertEquals(listOf(192, 168, 0, 1), TorRoute.ipv4("192.168.0.1")!!.toList())
        assertNull(TorRoute.ipv4("192.168.0"))
        assertNull(TorRoute.ipv4("256.1.1.1"))
        assertNull(TorRoute.ipv4("example.org"))
    }

    @Test fun statusLine() {
        assertEquals(TorRoute.Status.OFF, TorRoute.status(false, true, "ON", true))
        assertEquals(TorRoute.Status.CONNECTING, TorRoute.status(true, false, "STARTING", true))
        assertEquals(TorRoute.Status.CONNECTED, TorRoute.status(true, true, "", true))
        assertEquals(TorRoute.Status.NOT_RUNNING, TorRoute.status(true, false, "OFF", true))
        assertEquals(TorRoute.Status.NO_ORBOT, TorRoute.status(true, false, "", false))
        assertEquals("Connecting to Tor…", TorRoute.words(TorRoute.Status.CONNECTING))
        assertEquals("Connected through Tor", TorRoute.words(TorRoute.Status.CONNECTED))
        assertTrue(TorRoute.words(TorRoute.Status.NOT_RUNNING).startsWith("Tor isn't running"))
    }

    @Test fun readinessIsASocks5Greeting() {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val thread = Thread {
                server.accept().use { client ->
                    val greeting = ByteArray(3)
                    client.getInputStream().read(greeting)
                    if (greeting.toList() == listOf<Byte>(5, 1, 0)) client.getOutputStream().write(byteArrayOf(5, 0))
                }
            }
            thread.start()
            assertTrue(TorRoute.socksAnswers("127.0.0.1", server.localPort, 2000))
            thread.join(2000)
        }
        // Nothing listening: not ready.
        val closed = ServerSocket(0).use { it.localPort }
        assertFalse(TorRoute.socksAnswers("127.0.0.1", closed, 500))
    }

    @Test fun ircRefusalsThroughTorSaySo() {
        val refused = IrcRules.explain("ERROR", "Closing Link: You are banned from this server (Tor exit node)", "Libera.Chat", "kit", false, true)!!
        assertTrue(refused.fatal)
        assertTrue(refused.text.contains("through Tor"))
        assertTrue(refused.text.contains("SASL"))
        val sasl = IrcRules.explain("ERROR", "SASL authentication is required to connect from this network", "Libera.Chat", "kit", true, true)!!
        assertTrue(sasl.text.contains("Check the account name and password"))
        // Without Tor, the earlier words stay.
        assertFalse(IrcRules.explain("ERROR", "You are banned (K-lined)", "Libera.Chat", "kit", false)!!.text.contains("Tor"))
        // A plain drop isn't blamed on Tor.
        assertEquals("The connection to Libera.Chat dropped.", IrcRules.explain("ERROR", "Ping timeout", "Libera.Chat", "kit", false, true)!!.text)
    }
}
