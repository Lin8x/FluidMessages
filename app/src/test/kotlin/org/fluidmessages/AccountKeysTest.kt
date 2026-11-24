package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Several accounts per service on the phone: ids, chat keys, servers, and merge items match FluidMessages Web. */
class AccountKeysTest {
    @Test fun idsAreTheServiceAndPublicAddress() {
        assertEquals("matrix:@alice:alpha.example.test", AccountKeys.id("matrix", mapOf("user_id" to "@alice:alpha.example.test", "access_token" to "x")))
        assertEquals("email:me@example.test", AccountKeys.id("email", mapOf("address" to "Me@Example.test")))
        assertEquals("irc:Alice@irc.libera.chat", AccountKeys.id("irc", mapOf("nick" to "Alice", "server" to "IRC.Libera.Chat")))
        assertEquals("", AccountKeys.identity("irc", mapOf("server" to "irc.example.test")))
    }

    @Test fun tagsMatchTheServer() {
        // The same values as accounts.tag in server/accounts.py.
        assertEquals("4ab31948", AccountKeys.tag("matrix:@bob:beta.test"))
        assertEquals("fbb02de9", AccountKeys.tag("irc:fluidtest@irc.beta.test"))
        assertEquals("4cd5cc41", AccountKeys.tag("email:me@example.test"))
    }

    @Test fun aSecondAccountsChatKeepsTheFirstOnesKey() {
        val second = AccountKeys.threadAddress("maya", "matrix:@bob:beta.test")
        assertEquals("maya~4ab31948", second)
        assertEquals("maya", AccountKeys.baseAddress(second))
        assertEquals("maya", AccountKeys.baseAddress("maya"))
        assertTrue(AccountKeys.tagged(second))
        assertFalse(AccountKeys.tagged("maya"))
        assertFalse(AccountKeys.tagged("+15550100"))
        // Tagging again replaces the tag rather than stacking another one.
        assertEquals("maya~fbb02de9", AccountKeys.threadAddress(second, "irc:fluidtest@irc.beta.test"))
    }

    @Test fun viaOnlyWithMoreThanOneAccount() {
        assertEquals("", AccountKeys.via("matrix:@alice:alpha.example.test", "@alice:alpha.example.test", 1))
        assertEquals("via @alice:alpha.example.test", AccountKeys.via("matrix:@alice:alpha.example.test", "", 2))
        assertEquals("", AccountKeys.via("", "", 3))
    }

    @Test fun serversAreNamedAndListChannelsFirst() {
        assertEquals("Libera.Chat", AccountKeys.serverName("irc:alice@irc.libera.chat"))
        assertEquals("alpha.example.test", AccountKeys.serverName("matrix:@alice:alpha.example.test"))
        assertEquals("example.test", AccountKeys.serverName("xmpp:alice@example.test"))
        val rows = listOf("jordan" to false, "#radio" to true, "maya" to false, "#garden" to true)
        assertEquals(listOf("#radio", "#garden", "jordan", "maya"), AccountKeys.serverOrder(rows) { it.second }.map { it.first })
        assertEquals("Channels", AccountKeys.section("irc", true))
        assertEquals("Rooms", AccountKeys.section("matrix", true))
        assertEquals("Direct messages", AccountKeys.section("xmpp", false))
    }

    @Test fun perAccountMergeKeys() {
        assertEquals("account:matrix:@alice:alpha.example.test", AccountKeys.mergeKey("matrix", "@alice:alpha.example.test"))
        assertEquals("matrix" to "@alice:alpha.example.test", AccountKeys.perAccount("account:matrix:@alice:alpha.example.test"))
        assertEquals(null, AccountKeys.perAccount("account:matrix"))
        assertEquals(setOf("matrix"), AccountKeys.perAccountServices(listOf("account:matrix", "account:matrix:@a:b.test", "look:theme")))
    }
}
