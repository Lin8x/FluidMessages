package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Combining this phone's settings with FluidMessages Web's: union, true conflicts, and applying the choices. */
class SyncMergeTest {
    @Test fun firstPairingUnionsWhatOnlyOneSideHas() {
        val phone = mapOf("folder:+15550101" to "pinned", "sticker:sticker-1" to "1", "fun:games" to "off")
        val web = mapOf("folder:+15550101" to "pinned", "folder:+15550102" to "archived", "gif:Kittens" to "https://gifs.example.net/{query}")
        val result = SyncMerge.merge(phone, web, baseline = null)
        assertTrue(result.conflicts.isEmpty())
        // Pinned on both stays pinned; each side gets what only the other had.
        assertEquals(mapOf("folder:+15550102" to "archived", "gif:Kittens" to "https://gifs.example.net/{query}"), result.applyPhone)
        assertEquals(mapOf("fun:games" to "off", "sticker:sticker-1" to "1"), result.applyWeb)
        assertEquals(phone + web, result.baseline)
    }

    @Test fun firstPairingTakesTheWiderFolderSet() {
        val result = SyncMerge.merge(mapOf("folder:+15550101" to "pinned"), mapOf("folder:+15550101" to "archived+pinned"), null)
        assertTrue(result.conflicts.isEmpty())
        assertEquals(mapOf("folder:+15550101" to "archived+pinned"), result.applyPhone)
    }

    @Test fun differentValuesOnBothSidesAreConflicts() {
        val phone = mapOf("look:theme" to "midnight", "folder:+15550101" to "pinned", "timer:+15550103" to "3600")
        val web = mapOf("look:theme" to "ember", "folder:+15550101" to "archived", "timer:+15550103" to "60")
        val result = SyncMerge.merge(phone, web, null)
        assertEquals(listOf("folder:+15550101", "look:theme", "timer:+15550103"), result.conflicts.map { it.key })
        assertTrue(result.applyPhone.isEmpty() && result.applyWeb.isEmpty())
        // A conflict leaves the key out of the agreed baseline until someone chooses.
        assertTrue(result.baseline.isEmpty())
        assertEquals(SyncConflict("look:theme", "midnight", "ember"), result.conflicts[1])
    }

    @Test fun aChangeOnOneSideSinceTheLastSyncGoesToTheOther() {
        val baseline = mapOf("look:theme" to "ember", "folder:+15550101" to "pinned", "sticker:sticker-1" to "1")
        // The web unpinned a chat and changed the theme; the phone deleted a sticker.
        val phone = mapOf("look:theme" to "ember", "folder:+15550101" to "pinned")
        val web = mapOf("look:theme" to "love", "sticker:sticker-1" to "1")
        val result = SyncMerge.merge(phone, web, baseline)
        assertTrue(result.conflicts.isEmpty())
        assertEquals(mapOf("folder:+15550101" to null, "look:theme" to "love"), result.applyPhone)
        assertEquals(mapOf("sticker:sticker-1" to null), result.applyWeb)
        assertEquals(mapOf("look:theme" to "love"), result.baseline)
    }

    @Test fun bothChangedTheSameItemSinceTheLastSync() {
        val baseline = mapOf("timer:+15550103" to "3600")
        val result = SyncMerge.merge(mapOf("timer:+15550103" to "60"), mapOf("timer:+15550103" to "86400"), baseline)
        assertEquals(listOf(SyncConflict("timer:+15550103", "60", "86400")), result.conflicts)
        assertEquals(baseline, result.baseline)
        // Both went back to the default the same way: no conflict.
        assertTrue(SyncMerge.merge(emptyMap(), emptyMap(), baseline).let { it.conflicts.isEmpty() && it.baseline.isEmpty() })
    }

    @Test fun accountsConflictOnlyByAddressAndRememberTheChoice() {
        val phone = mapOf("account:matrix" to "@sam:matrix.example.org")
        val web = mapOf("account:matrix" to "@sam:chat.example.net")
        assertEquals(1, SyncMerge.merge(phone, web, null).conflicts.size)
        // On one side only: nothing is copied, since passwords never travel.
        SyncMerge.merge(phone, emptyMap(), null).let { assertTrue(it.applyWeb.isEmpty() && it.conflicts.isEmpty()) }
        val decided = mapOf("account:matrix" to SyncDecision("@sam:matrix.example.org", "@sam:chat.example.net", SyncMerge.PHONE))
        assertTrue(SyncMerge.merge(phone, web, null, decided).conflicts.isEmpty())
        // A different account asks again.
        assertEquals(1, SyncMerge.merge(mapOf("account:matrix" to "@alex:matrix.example.org"), web, null, decided).conflicts.size)
    }

    @Test fun severalAccountsConflictOnlyWhenTheSameAccountIsOnBothSides() {
        // A newer web: one item per account, plus the older first-account item for older phones.
        val web = mapOf("account:matrix" to "@alice:alpha.example.test", "account:matrix:@alice:alpha.example.test" to "web",
            "account:matrix:@alice:beta.example.test" to "web")
        // This phone connects a third account: no conflict, even though the older items differ.
        val other = mapOf("account:matrix" to "@sam:gamma.example.test", "account:matrix:@sam:gamma.example.test" to "phone")
        SyncMerge.merge(other, web, null).let { assertTrue(it.conflicts.isEmpty() && it.applyPhone.isEmpty() && it.applyWeb.isEmpty()) }
        // The phone connects the same account as the web: that one account asks who connects.
        val same = mapOf("account:matrix" to "@alice:beta.example.test", "account:matrix:@alice:beta.example.test" to "phone")
        assertEquals(listOf(SyncConflict("account:matrix:@alice:beta.example.test", "phone", "web")), SyncMerge.merge(same, web, null).conflicts)
        // An older web lists only the first-account item, which still works the old way.
        assertEquals(listOf("account:matrix"), SyncMerge.merge(same, mapOf("account:matrix" to "@alice:alpha.example.test"), null).conflicts.map { it.key })
        assertEquals("Matrix · @alice:beta.example.test", SyncMerge.describe("account:matrix:@alice:beta.example.test") { it }.second)
        assertEquals("Connects from this phone", SyncMerge.valueLabel("account:matrix:@alice:beta.example.test", "phone"))
    }

    @Test fun keysOutOfScopeKeepTheirBaseline() {
        val baseline = mapOf("look:theme" to "ember")
        val result = SyncMerge.merge(mapOf("look:theme" to "midnight"), mapOf("look:theme" to "love"), baseline) { SyncMerge.group(it) != "look" }
        assertTrue(result.conflicts.isEmpty() && result.applyPhone.isEmpty() && result.applyWeb.isEmpty())
        assertEquals(baseline, result.baseline)
    }

    @Test fun choicesApplyPerConflictOrForAll() {
        val conflicts = listOf(SyncConflict("look:theme", "midnight", "ember"), SyncConflict("folder:+15550101", "pinned", null))
        assertEquals(mapOf("look:theme" to "phone", "folder:+15550101" to "phone"), SyncMerge.all(conflicts, SyncMerge.PHONE))
        val choices = mapOf("look:theme" to SyncMerge.WEB, "folder:+15550101" to SyncMerge.PHONE)
        assertEquals(mapOf("look:theme" to "ember", "folder:+15550101" to "pinned"), SyncMerge.chosen(conflicts, choices))
        // Only the web's picks change this phone.
        assertEquals(mapOf("look:theme" to "ember"), SyncMerge.phoneChanges(conflicts, choices))
        assertEquals(mapOf("look:theme" to "ember", "folder:+15550101" to null),
            SyncMerge.phoneChanges(conflicts, SyncMerge.all(conflicts, SyncMerge.WEB)))
        // Not chosen means the phone.
        assertEquals(mapOf("look:theme" to "midnight", "folder:+15550101" to "pinned"), SyncMerge.chosen(conflicts, emptyMap()))
    }

    @Test fun afterChoosingBothSidesMergeCleanly() {
        val phone = mutableMapOf("look:theme" to "midnight")
        val web = mutableMapOf("look:theme" to "ember")
        val first = SyncMerge.merge(phone, web, null)
        val choices = SyncMerge.all(first.conflicts, SyncMerge.WEB)
        phone.putAll(SyncMerge.phoneChanges(first.conflicts, choices).mapValues { it.value!! })
        val baseline = first.baseline + SyncMerge.chosen(first.conflicts, choices).mapValues { it.value!! }
        val next = SyncMerge.merge(phone, web, baseline)
        assertTrue(next.conflicts.isEmpty() && next.applyPhone.isEmpty() && next.applyWeb.isEmpty())
    }

    // The same scenario as scripts/test-local merge_items_test, on this phone's own merge.
    private val web = mapOf("look:theme" to "ember", "look:mode" to "dark", "pref:tabCounts" to "off", "pref:communitySort" to "name",
        "pref:announceSettings" to "off", "pref:inboxFilters" to "unread,inbox,-irc,favorites,groups,direct,texts,matrix,xmpp,email,archived,blocked",
        "favorite:+12025550141" to "on", "favorite:@rowan:example.org" to "on")
    private val phone = mapOf("look:theme" to "midnight", "pref:showCommunityChats" to "off", "pref:communitySort" to "joined",
        "favorite:+12025550142" to "on", "favorite:+12025550141" to "on", "folder:+12025550143" to "pinned")

    @Test fun firstPairingThenReconnectingAfterBothChangedWhileApart() {
        // First pairing (or an erased server, whose pair id is new): combined, and the two true differences ask.
        val first = SyncMerge.merge(phone, web, null)
        assertEquals(listOf("look:theme", "pref:communitySort"), first.conflicts.map { it.key })
        assertEquals(mapOf("favorite:+12025550142" to "on", "folder:+12025550143" to "pinned", "pref:showCommunityChats" to "off"), first.applyWeb)
        assertEquals(setOf("look:mode", "pref:tabCounts", "pref:announceSettings", "pref:inboxFilters", "favorite:@rowan:example.org"),
            first.applyPhone.keys)
        // Settled in the browser: the phone's theme, the web's sort.
        val choices = mapOf("look:theme" to SyncMerge.PHONE, "pref:communitySort" to SyncMerge.WEB)
        val phoneNow = (phone + first.applyPhone.mapValues { it.value!! } + SyncMerge.phoneChanges(first.conflicts, choices).mapValues { it.value!! }).toMutableMap()
        val webNow = (web + first.applyWeb.mapValues { it.value!! } + SyncMerge.chosen(first.conflicts, choices).mapValues { it.value!! }).toMutableMap()
        val baseline = first.baseline + SyncMerge.chosen(first.conflicts, choices).mapValues { it.value!! }
        assertEquals(phoneNow, webNow)
        SyncMerge.merge(phoneNow, webNow, baseline).let { assertTrue(it.conflicts.isEmpty() && it.applyPhone.isEmpty() && it.applyWeb.isEmpty()) }

        // Apart: the web changed the sort and unstarred someone; the phone changed the sort too, starred someone, and
        // turned community chats back on. Reconnecting carries the one-sided changes and asks about the sort.
        webNow["pref:communitySort"] = "joined"
        webNow.remove("favorite:+12025550142")
        phoneNow.remove("pref:communitySort")
        phoneNow["favorite:@sam:example.org"] = "on"
        phoneNow.remove("pref:showCommunityChats")
        val again = SyncMerge.merge(phoneNow, webNow, baseline)
        assertEquals(listOf(SyncConflict("pref:communitySort", null, "joined")), again.conflicts)
        assertEquals(mapOf("favorite:+12025550142" to null), again.applyPhone)
        assertEquals(mapOf("favorite:@sam:example.org" to "on", "pref:showCommunityChats" to null), again.applyWeb)
        assertEquals("name", again.baseline["pref:communitySort"])
    }

    @Test fun aReinstalledPhoneGetsEverythingAndDeletesNothing() {
        val result = SyncMerge.merge(emptyMap(), web, null)
        assertEquals(web, result.applyPhone)
        assertTrue(result.applyWeb.isEmpty() && result.conflicts.isEmpty())
    }

    @Test fun theWebsOwnSettingsLeaveInboxChoicesButNotFavorites() {
        val baseline = mapOf("pref:tabCounts" to "off")
        val result = SyncMerge.merge(mapOf("favorite:+12025550145" to "on"), mapOf("pref:tabCounts" to "off"), baseline) {
            SyncMerge.group(it) !in SyncMerge.settingsGroups
        }
        assertEquals(mapOf("favorite:+12025550145" to "on"), result.applyWeb)
        assertEquals(baseline + ("favorite:+12025550145" to "on"), result.baseline)
    }

    @Test fun newerGroupsOnlyWhenBothSidesUnderstandThem() {
        // An older FluidMessages Web answers only "mute_until"; one from before that, nothing.
        assertEquals(setOf("muteuntil"), SyncMerge.sharedGroups(null, true))
        assertEquals(emptySet<String>(), SyncMerge.sharedGroups(null, false))
        assertEquals(setOf("muteuntil", "pref", "favorite"), SyncMerge.sharedGroups(listOf("muteuntil", "pref", "favorite", "later"), true))
        // Message requests: listed in "items", or the first FluidMessages Web with requests answering "requests": 1.
        assertEquals(setOf("muteuntil", "request", "requests"), SyncMerge.sharedGroups(null, true, requests = true))
        assertEquals(setOf("muteuntil", "pref", "request", "requests"),
            SyncMerge.sharedGroups(listOf("muteuntil", "pref", "request", "requests"), true))
        assertEquals(setOf("muteuntil", "pref", "favorite", "request", "requests"),
            SyncMerge.sharedGroups(listOf("muteuntil", "pref", "favorite"), true, requests = true))
        assertTrue(!SyncMerge.understood("request:+12025550101", setOf("muteuntil", "pref", "favorite")))
        val shared = setOf("muteuntil")
        assertTrue(SyncMerge.understood("look:theme", shared) && SyncMerge.understood("muteuntil:+12025550101", shared))
        assertTrue(!SyncMerge.understood("pref:tabCounts", shared) && !SyncMerge.understood("favorite:+12025550101", shared))
    }

    @Test fun favoritesAreNumbersOrAddressesOnBothSides() {
        assertEquals("+12025550141", SyncMerge.favoriteAddress("+1 (202) 555-0141"))
        assertEquals("2025550141", SyncMerge.favoriteAddress(" (202) 555-0141 "))
        assertEquals("@rowan:example.org", SyncMerge.favoriteAddress("@rowan:example.org"))
        assertEquals("12", SyncMerge.favoriteAddress("12"))
        // A starred contact is every number it has; a chat is its address.
        val reach = mapOf("contact:7" to listOf("(202) 555-0141", "+1 202 555 0142"), "chat:@rowan:example.org" to listOf("@rowan:example.org"),
            "demo:Maya Chen" to emptyList())
        assertEquals(setOf("2025550141", "+12025550142", "@rowan:example.org"), FavoriteMerge.items(reach))
        // Unstarred on the web by one of its numbers: the contact goes. Starred there: found, or the address itself.
        val next = FavoriteMerge.apply(reach, mapOf("+12025550142" to false, "+12025550199" to true, "@rowan:example.org" to true)) { "chat:$it" }
        assertEquals(listOf("chat:@rowan:example.org", "demo:Maya Chen", "chat:+12025550199"), next)
    }

    @Test fun messagingItemsReadAsWords() {
        assertEquals("Messaging" to "Counts on tabs", SyncMerge.describe("pref:tabCounts") { it })
        assertEquals("Off", SyncMerge.valueLabel("pref:tabCounts", "off"))
        assertEquals("Date joined", SyncMerge.valueLabel("pref:communitySort", "joined"))
        assertEquals("Recent activity", SyncMerge.valueLabel("pref:communitySort", null))
        assertEquals("Unread, Inbox, Groups", SyncMerge.valueLabel("pref:inboxFilters", "unread,inbox,-favorites,groups"))
        assertEquals("Favorites" to "Rowan Hale", SyncMerge.describe("favorite:@rowan:example.org") { "Rowan Hale" })
        assertEquals("Favorite", SyncMerge.valueLabel("favorite:@rowan:example.org", "on"))
    }

    @Test fun foldersAndValuesReadAsWords() {
        assertEquals("pinned", SyncMerge.folderValue(setOf("pinned")))
        assertEquals("archived+pinned", SyncMerge.folderValue(listOf("pinned", "archived")))
        assertEquals(null, SyncMerge.folderValue(emptySet()))
        assertEquals("Pinned, Archived", SyncMerge.valueLabel("folder:+15550101", "archived+pinned"))
        assertEquals("Inbox", SyncMerge.valueLabel("folder:+15550101", null))
        assertEquals("Midnight", SyncMerge.valueLabel("look:theme", "midnight"))
        assertEquals("Fluid", SyncMerge.valueLabel("look:theme", null))
        assertEquals("Chat folders" to "Maya Chen", SyncMerge.describe("folder:+15550101") { "Maya Chen" })
    }
}
