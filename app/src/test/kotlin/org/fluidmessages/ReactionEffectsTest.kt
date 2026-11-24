package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Which reaction is new, which animation it plays, and how the bubble moves. */
class ReactionEffectsTest {
    @Test fun everyBuiltInReactionHasItsOwnAnimation() {
        val animations = SmsReaction.entries.map { reactionAnimation(it.symbol) }
        assertTrue(animations.none { it == null })
        assertEquals(SmsReaction.entries.size, animations.toSet().size)
        assertEquals(ReactionAnimation.HEARTS, reactionAnimation("❤️"))
        assertEquals(ReactionAnimation.THUMB_POP, reactionAnimation("👍"))
        assertEquals(ReactionAnimation.SLOW_DROP, reactionAnimation("👎"))
        assertEquals(ReactionAnimation.HAHA, reactionAnimation("😆"))
        assertEquals(ReactionAnimation.EMPHASIS, reactionAnimation("‼️"))
        assertEquals(ReactionAnimation.TUMBLE, reactionAnimation("❓"))
        assertTrue(ReactionAnimation.entries.all { it.millis in 1000..1500 })
    }

    @Test fun variationSelectorsDoNotMatterButCustomEmojiGetNone() {
        assertEquals(ReactionAnimation.HEARTS, reactionAnimation("❤"))
        assertEquals(ReactionAnimation.EMPHASIS, reactionAnimation("‼"))
        assertNull(reactionAnimation("🎉"))
        assertNull(reactionAnimation("👍🏽"))
        assertNull(reactionAnimation(""))
    }

    @Test fun onlyAddedReactionsCountAsNew() {
        // Opening a chat or scrolling back: the same list again is nothing new.
        assertNull(addedReaction(listOf("👍", "❤️"), listOf("👍", "❤️")))
        assertNull(addedReaction(emptyList(), emptyList()))
        // Removals and reorders aren't additions.
        assertNull(addedReaction(listOf("👍", "❤️"), listOf("❤️")))
        assertNull(addedReaction(listOf("👍", "❤️"), listOf("❤️", "👍")))
        // The first reaction on a message, a second one, and a change from one to another.
        assertEquals("👍", addedReaction(emptyList(), listOf("👍")))
        assertEquals("😆", addedReaction(listOf("👍"), listOf("👍", "😆")))
        assertEquals("❤️", addedReaction(listOf("👍"), listOf("❤️")))
        // A second person in a group giving the same one.
        assertEquals("👍", addedReaction(listOf("👍"), listOf("👍", "👍")))
        // Custom emoji are still detected; they just keep the small pop.
        assertEquals("🎉", addedReaction(listOf("👍"), listOf("👍", "🎉")))
    }

    @Test fun bubbleMotionStartsAndEndsAtRest() {
        for (animation in ReactionAnimation.entries) {
            assertEquals(ReactionMotion(), reactionBubbleMotion(animation, 0f))
            assertEquals(ReactionMotion(), reactionBubbleMotion(animation, 1f))
            val samples = (1..19).map { reactionBubbleMotion(animation, it / 20f) }
            assertTrue("$animation should move", samples.any { it != ReactionMotion() })
            assertTrue("$animation stays subtle", samples.all {
                abs(it.scale - 1f) <= .1f && abs(it.rotation) <= 8f && abs(it.dx) <= 8f && abs(it.dy) <= 8f
            })
        }
        assertEquals(ReactionMotion(), reactionBubbleMotion(null, .5f))
        assertNotEquals(reactionBubbleMotion(ReactionAnimation.EMPHASIS, .05f), reactionBubbleMotion(ReactionAnimation.HAHA, .05f))
    }
}
