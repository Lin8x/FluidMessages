package org.fluidmessages

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.MessageDigest
import java.security.SecureRandom

/*
 * Rock paper scissors over text, fair even though one person goes first:
 *
 *  1. The starter locks in a pick and sends only a seal: a hash of the game, the pick, and a secret.
 *  2. The other person picks in the open (with FluidMessages, or by replying "paper" from any app).
 *  3. The starter reveals the pick and the secret. FluidMessages checks them against the seal, so the starter
 *     can't change their pick after seeing the other one.
 *
 * People on other apps can't check the seal themselves; the message tells them so.
 */

internal enum class Hand(val word: String, val emoji: String) {
    ROCK("rock", "✊"), PAPER("paper", "✋"), SCISSORS("scissors", "✌️");

    fun beats(other: Hand) = this == ROCK && other == SCISSORS || this == PAPER && other == ROCK || this == SCISSORS && other == PAPER
    val title get() = word.replaceFirstChar { it.uppercaseChar() }

    companion object { fun of(word: String) = entries.firstOrNull { it.word == word } }
}

private const val RPS_TITLE = "✊✋✌️ Rock paper scissors"
private val rpsPattern = Regex("""fm-rps:([a-z0-9]{6,12}):(seal|pick|open):([a-z0-9]{1,64})(?::([a-f0-9]{16,64}))?\s*$""")
private val handReply = Regex("""(?i)^\s*(?:✊|✋|✌️|✌)?\s*(rock|paper|scissors)\s*(?:✊|✋|✌️|✌)?\s*[.!]*\s*$""")

internal fun seal(id: String, hand: Hand, secret: String): String =
    MessageDigest.getInstance("SHA-256").digest("$id:${hand.word}:$secret".toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

internal data class RpsMessage(val id: String, val stage: String, val value: String, val secret: String?)

internal fun parseRps(text: String): RpsMessage? {
    if (!text.startsWith(RPS_TITLE) || text.length > 1000) return null
    val match = rpsPattern.find(text) ?: return null
    return RpsMessage(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4].ifEmpty { null })
}

/** Starts a game: keeps the pick and its secret on this phone, and returns the message to send. */
internal fun startRps(context: Context, hand: Hand): String {
    val id = newId()
    val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    context.getSharedPreferences("games", Context.MODE_PRIVATE).edit().putString("rps.$id", "${hand.word}:$secret").apply()
    return "$RPS_TITLE · I've locked in my pick. Your turn: reply rock, paper, or scissors.\n" +
        "FluidMessages seals my pick so I can't change it. On other apps, you'll see my pick after you reply.\n" +
        "fm-rps:$id:seal:${seal(id, hand, secret)}"
}

private fun rpsPickText(id: String, hand: Hand) = "$RPS_TITLE · I picked ${hand.word} ${hand.emoji}.\nfm-rps:$id:pick:${hand.word}"

private fun rpsOpenText(id: String, mine: Hand, theirs: Hand, secret: String): String {
    val result = when {
        mine == theirs -> "We both picked ${mine.word}: it's a tie!"
        mine.beats(theirs) -> "${mine.title} beats ${theirs.word}: I win!"
        else -> "${theirs.title} beats ${mine.word}: you win!"
    }
    return "$RPS_TITLE · I picked ${mine.word} ${mine.emoji}. $result\nfm-rps:$id:open:${mine.word}:$secret"
}

/** Where one message stands in its game. */
internal data class RpsView(
    val id: String, val sealedByMe: Boolean, val latest: Boolean,
    /** The second player's pick, once made. */
    val answer: Hand?, val revealed: Hand?,
    /** False when a reveal doesn't match the seal. */
    val honest: Boolean,
    val reply: Boolean, val rejected: Boolean,
)

private class RpsTrack(val id: String, val sealedByMe: Boolean, val hash: String) {
    var answer: Hand? = null
    var revealed: Hand? = null
    var honest = true
    var last = ""
}

internal fun trackRps(lines: List<ChatLine>): Map<String, RpsView> {
    val games = linkedMapOf<String, RpsTrack>()
    val views = mutableMapOf<String, RpsView>()
    fun view(track: RpsTrack, reply: Boolean = false, rejected: Boolean = false) =
        RpsView(track.id, track.sealedByMe, false, track.answer, track.revealed, track.honest, reply, rejected)
    for (line in lines) {
        if (line.attachment != null) continue
        val message = parseRps(line.text)
        if (message != null) {
            val known = games[message.id]
            when (message.stage) {
                "seal" -> if (known == null) {
                    val track = RpsTrack(message.id, line.outgoing, message.value)
                    games[message.id] = track; track.last = line.id; views[line.id] = view(track)
                }
                "pick" -> {
                    val hand = Hand.of(message.value)
                    if (known == null || hand == null || known.answer != null || line.outgoing == known.sealedByMe) {
                        known?.let { views[line.id] = view(it, rejected = true) }
                    } else { known.answer = hand; known.last = line.id; views[line.id] = view(known) }
                }
                "open" -> {
                    val hand = Hand.of(message.value)
                    if (known == null || hand == null || known.answer == null || known.revealed != null || line.outgoing != known.sealedByMe || message.secret == null) {
                        known?.let { views[line.id] = view(it, rejected = true) }
                    } else {
                        known.revealed = hand
                        known.honest = seal(known.id, hand, message.secret) == known.hash
                        known.last = line.id; views[line.id] = view(known)
                    }
                }
            }
            continue
        }
        // From another app: "paper" answers the newest game still waiting on that person.
        val word = handReply.matchEntire(line.text)?.groupValues?.get(1)?.lowercase() ?: continue
        val waiting = games.values.lastOrNull { it.answer == null && it.sealedByMe != line.outgoing } ?: continue
        waiting.answer = Hand.of(word); waiting.last = line.id
        views[line.id] = view(waiting, reply = true)
    }
    val latest = games.values.associate { it.last to it.id }
    return views.mapValues { (lineId, view) -> if (latest[lineId] == view.id && !view.rejected) view.copy(latest = true) else view }
}

@Composable
internal fun RpsCard(view: RpsView, foreground: Color, respond: ((String) -> Unit)?) {
    val context = LocalContext.current
    val saved = remember(view.id) { context.getSharedPreferences("games", Context.MODE_PRIVATE).getString("rps.${view.id}", null) }
    val myHand = saved?.substringBefore(':')?.let(Hand::of)
    Column(Modifier.widthIn(min = 220.dp).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("ROCK PAPER SCISSORS", color = foreground.copy(alpha = .75f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        if (view.rejected) { Text("This message doesn't fit the game, so it was skipped.", color = foreground, fontSize = 14.sp); return@Column }
        val mine = if (view.sealedByMe) view.revealed ?: myHand else view.answer
        val theirs = if (view.sealedByMe) view.answer else view.revealed
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HandTile("You", mine?.emoji ?: "❔", foreground)
            Text("vs", color = foreground.copy(alpha = .7f))
            HandTile("Them", theirs?.emoji ?: if (!view.sealedByMe && view.revealed == null) "🔒" else "❔", foreground)
        }
        val status = when {
            view.revealed != null && !view.honest -> "⚠️ Their reveal doesn't match the pick they sealed."
            view.revealed != null -> {
                val a = mine!!; val b = theirs!!
                when { a == b -> "It's a tie!"; a.beats(b) -> "You win! 🎉"; else -> "They win" }
            }
            view.answer == null -> if (view.sealedByMe) "Your pick is sealed · waiting for them" else "They sealed a pick. Choose yours."
            view.sealedByMe -> "They picked. Reveal yours to finish."
            else -> "Waiting for them to reveal"
        }
        Text(status, color = foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (respond != null && view.latest) when {
            view.answer == null && !view.sealedByMe -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (hand in Hand.entries) FilledTonalButton(onClick = { respond(rpsPickText(view.id, hand)) }) { Text(hand.emoji + " " + hand.title, fontSize = 13.sp) }
            }
            view.answer != null && view.revealed == null && view.sealedByMe -> {
                val secret = saved?.substringAfter(':')
                if (myHand != null && secret != null) FilledTonalButton(onClick = { respond(rpsOpenText(view.id, myHand, view.answer, secret)) }) { Text("Reveal my pick") }
                else Text("Your pick was made on another device. Reveal it from there.", color = foreground, fontSize = 13.sp)
            }
            view.revealed != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Play again:", color = foreground, fontSize = 13.sp)
                for (hand in Hand.entries) TextButton(onClick = { respond(startRps(context, hand)) }) { Text(hand.emoji, fontSize = 20.sp) }
            }
        }
    }
}

@Composable
private fun HandTile(label: String, emoji: String, foreground: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(foreground.copy(alpha = .15f)), contentAlignment = Alignment.Center) {
            Text(emoji, fontSize = 28.sp)
        }
        Text(label, color = foreground.copy(alpha = .8f), fontSize = 11.sp)
    }
}
