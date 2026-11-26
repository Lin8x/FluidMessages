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

    @Test fun torInsideTheAppUsesItsOwnLocalPort() {
        val address = TorRoute.proxyFor(TorRoute.Way.TOR, 39123, TorRoute.NOT_READY).address() as InetSocketAddress
        assertEquals("127.0.0.1", address.hostString)
        assertEquals(39123, address.port)
        try {
            TorRoute.proxyFor(TorRoute.Way.WAIT, 39123, TorRoute.ORBOT_NOT_READY)
            fail("A connection went ahead without Tor")
        } catch (expected: IOException) {
            assertEquals(TorRoute.ORBOT_NOT_READY, expected.message)
        }
        // Its own settings: random ports, on 127.0.0.1 only.
        assertTrue(BuiltInTor.TORRC.contains("SOCKSPort 127.0.0.1:auto"))
        assertTrue(BuiltInTor.TORRC.contains("HTTPTunnelPort 127.0.0.1:auto"))
        assertFalse(BuiltInTor.TORRC.contains("0.0.0.0"))
    }

    @Test fun aConnectionsOwnChoiceChangesTheRoute() {
        // Tor off, but one IRC network chose Tor in chat details: only that network goes through Tor.
        var overrides = TorRoute.withChoice(emptySet(), listOf("irc.libera.chat"), true, false)
        assertEquals(setOf("+irc.libera.chat"), overrides)
        assertTrue(TorRoute.wants(false, overrides, "IRC.Libera.Chat."))
        assertFalse(TorRoute.wants(false, overrides, "matrix.example.org"))
        assertEquals(TorRoute.Way.TOR, TorRoute.decide(TorRoute.wants(false, overrides, "irc.libera.chat"), true, TorRoute.Use.CHAT, false, false))
        assertEquals(TorRoute.Way.WAIT, TorRoute.decide(TorRoute.wants(false, overrides, "irc.libera.chat"), false, TorRoute.Use.CHAT, false, false))
        assertEquals(TorRoute.Way.DIRECT, TorRoute.decide(TorRoute.wants(false, overrides, "matrix.example.org"), true, TorRoute.Use.CHAT, false, false))
        assertTrue(TorRoute.anyThrough(overrides))
        // Tor on for everything, but one mailbox (both its servers) turned it off.
        overrides = TorRoute.withChoice(emptySet(), listOf("imap.example.org", "smtp.example.org"), false, true)
        assertEquals(setOf("-imap.example.org", "-smtp.example.org"), overrides)
        assertFalse(TorRoute.wants(true, overrides, "imap.example.org"))
        assertTrue(TorRoute.wants(true, overrides, "irc.libera.chat"))
        assertEquals(TorRoute.Way.DIRECT, TorRoute.decide(TorRoute.wants(true, overrides, "smtp.example.org"), false, TorRoute.Use.CHAT, false, false))
        assertFalse(TorRoute.anyThrough(overrides))
        // Choosing what the setting already does removes the entry, so the connection follows the setting again.
        assertEquals(emptySet<String>(), TorRoute.withChoice(overrides, listOf("imap.example.org", "smtp.example.org"), true, true))
        // No host, or no choices: the setting.
        assertTrue(TorRoute.wants(true, null, "x.example"))
        assertFalse(TorRoute.wants(false, setOf("+"), ""))
    }

    @Test fun bootstrapProgressFromTorsEvents() {
        assertEquals(45, BuiltInTor.bootstrapPercent("NOTICE BOOTSTRAP PROGRESS=45 TAG=loading_descriptors SUMMARY=\"Loading relay descriptors\""))
        assertEquals(100, BuiltInTor.bootstrapPercent("NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\""))
        assertEquals(-1, BuiltInTor.bootstrapPercent("NOTICE CIRCUIT_ESTABLISHED"))
        assertEquals(-1, BuiltInTor.bootstrapPercent(null))
    }

    @Test fun torStopsOnlyWhenNothingNeedsIt() {
        val idle = BuiltInTor.IDLE
        assertTrue(BuiltInTor.canStop(false, 0, 0, true))
        assertFalse(BuiltInTor.canStop(true, idle, 0, false))
        assertFalse(BuiltInTor.canStop(true, 0, idle, false))
        assertTrue(BuiltInTor.canStop(true, idle, idle, false))
        assertFalse(BuiltInTor.canStop(true, idle, idle, true))
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
        // Tor inside the app.
        assertEquals(TorRoute.Status.OFF, TorRoute.builtInStatus(false, BuiltInTor.Phase.ON, true))
        assertEquals(TorRoute.Status.CONNECTED, TorRoute.builtInStatus(true, BuiltInTor.Phase.ON, true))
        assertEquals(TorRoute.Status.STARTING, TorRoute.builtInStatus(true, BuiltInTor.Phase.STARTING, false))
        assertEquals(TorRoute.Status.WAITING, TorRoute.builtInStatus(true, BuiltInTor.Phase.OFF, false))
        assertEquals(TorRoute.Status.CANT_START, TorRoute.builtInStatus(true, BuiltInTor.Phase.UNSUPPORTED, false))
        assertEquals("Starting Tor… 45%", TorRoute.words(TorRoute.Status.STARTING, 45))
        assertEquals("Starting Tor…", TorRoute.words(TorRoute.Status.STARTING, 0))
        assertEquals("Starting Tor… 99%", TorRoute.words(TorRoute.Status.STARTING, 100))
        assertEquals("Tor starts when a connection needs it", TorRoute.words(TorRoute.Status.WAITING))
        assertTrue(TorRoute.words(TorRoute.Status.CANT_START).contains("Orbot app instead"))
        for (status in TorRoute.Status.values()) assertTrue(status.name, TorRoute.words(status).isNotEmpty())
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
