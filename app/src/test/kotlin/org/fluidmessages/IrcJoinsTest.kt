package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Join requirements on the phone's own IRC connection. Fictional server texts; the same ones are in scripts/test-local. */
class IrcJoinsTest {
    @Test fun signedInOnlyChannelsAreRecognized() {
        assertTrue(IrcJoins.needsSignIn("477", "Cannot join channel (+r) - you need to be identified with services"))
        assertTrue(IrcJoins.needsSignIn("477", "You need a registered nick to join that channel."))
        assertTrue(IrcJoins.needsSignIn("473", "Cannot join channel (+r) - you need to be identified with services"))
        assertTrue(IrcJoins.needsSignIn("474", "You must be logged in to a services account to join this channel"))
        assertFalse(IrcJoins.needsSignIn("477", "Channel doesn't support modes"))
        assertFalse(IrcJoins.needsSignIn("473", "Cannot join channel (+i)"))
        assertFalse(IrcJoins.needsSignIn("474", "Cannot join channel (+b) - you are banned"))
        assertFalse(IrcJoins.needsSignIn("475", "Cannot join channel (+k) - registered users know the key"))
        assertFalse(IrcJoins.needsSignIn("001", "Welcome, you must be registered"))
    }

    @Test fun aChannelToJoinFirstIsFound() {
        assertEquals("#help", IrcJoins.prerequisite("473", "Cannot join channel #chat (You must join #help first)", "#chat"))
        assertEquals("#lobby", IrcJoins.prerequisite("520", "Please join #lobby before #chat.", "#chat"))
        assertEquals("#rules", IrcJoins.prerequisite("477", "You need to be in #rules to join this channel", "#chat"))
        assertEquals("#lobby", IrcJoins.prerequisite("474", "join #lobby first!", "#Chat"))
        // Not a requirement: an invite-only or banned reply, a pointer to a help channel, or the channel itself.
        assertNull(IrcJoins.prerequisite("473", "Cannot join channel (+i)", "#chat"))
        assertNull(IrcJoins.prerequisite("474", "Cannot join channel (+b) - you are banned. Appeal in #appeals", "#chat"))
        assertNull(IrcJoins.prerequisite("473", "You must join #chat first", "#CHAT"))
        assertNull(IrcJoins.prerequisite("PRIVMSG", "You must join #help first", "#chat"))
    }

    @Test fun joinOrderPutsPrerequisitesFirstWithoutLoops() {
        val needs = mapOf("#chat" to listOf("#lobby"), "#lobby" to listOf("#gate"), "#a" to listOf("#b"), "#b" to listOf("#a"))
        val first: (String) -> List<String> = { needs[it.lowercase()].orEmpty() }
        assertEquals(listOf("#gate", "#lobby", "#chat", "#news"), IrcJoins.order(listOf("#chat", "#news", "#lobby"), first))
        assertEquals(listOf("#b", "#a"), IrcJoins.order(listOf("#a"), first))
    }

    @Test fun theRememberedListAddsAndRemoves() {
        var saved = IrcJoins.remember("", "#chat", "#lobby")
        saved = IrcJoins.remember(saved, "#Chat", "#LOBBY")
        saved = IrcJoins.remember(saved, "#chat", "#chat")
        assertEquals(listOf("#lobby"), IrcJoins.firstFor(saved, "#CHAT"))
        assertEquals(emptyList<String>(), IrcJoins.firstFor(IrcJoins.forget(saved, "#chat", "#Lobby"), "#chat"))
        assertEquals(emptyList<String>(), IrcJoins.firstFor("not json", "#chat"))
        assertEquals(listOf("#chat"), IrcJoins.neededBy("#lobby", mapOf("#chat" to listOf("#lobby"), "#news" to emptyList())))
        assertEquals("#lobby is needed to stay in #chat on this network.", IrcJoins.neededText("#lobby", listOf("#chat")))
    }

    @Test fun refusalsArePlainWordsWithWhatToDo() {
        assertEquals("#pond only lets in people who are signed in to their nickname. Sign in with NickServ, then try again. "
            + "Type your password in the NickServ card, or save it as Password in the IRC account settings.",
            IrcChannels.joinError("477", "#pond", "Harbor Net", "You need to be identified"))
        assertTrue(IrcChannels.joinError("477", "#pond", "Harbor Net", "", true).contains("signing in didn’t work"))
        assertTrue(IrcChannels.joinError("475", "#pond", "Harbor Net", "Cannot join channel (+k)").contains("password"))
        assertTrue(IrcChannels.joinError("473", "#pond", "Harbor Net", "Cannot join channel (+i)").contains("invite"))
        assertEquals("Ask", IrcRules.errorText("999", "", "Ask").take(3))
        assertTrue(IrcRules.errorText("482", "#pond", "You're not channel operator").contains("moderators"))
        assertEquals("Harbor Net · network messages", IrcIntro.serverChatTitle("Harbor Net"))
        assertEquals("Harbor Net · network messages", IrcIntro.serverChatTitle("Harbor Net · network messages"))
        assertNull(IrcIntro.forLink(null, false))
    }
}
