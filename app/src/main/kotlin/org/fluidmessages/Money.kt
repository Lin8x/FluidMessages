package org.fluidmessages

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.net.URLEncoder

/**
 * Money requests and payments, sent as readable text that any app shows and FluidMessages turns into a card.
 * Paying always happens in the person's own wallet app through a standard payment link (Solana Pay, EIP-681,
 * BIP 21). FluidMessages never holds money, keys, or seed phrases, and never sends a transaction itself.
 * The browser companion uses the same format (companion.js).
 */
internal data class MoneyAsset(
    val name: String, val address: Regex, val explorer: String,
    /** Builds the payment link from the address, amount, note, and (for regular money) the currency. */
    val build: (to: String, amount: BigDecimal, memo: String, currency: String) -> String,
    /** Regular money through a payment service; the message names it "USD via PayPal". */
    val currencies: List<String> = emptyList(),
    val addressLabel: String = "wallet address",
    /** Added by you, not checked by FluidMessages. Cards for it show a warning. */
    val custom: Boolean = false,
) {
    val fiat: Boolean get() = currencies.isNotEmpty()
    /** The name used in messages: "XMR on Monero", or "USD via PayPal" for regular money. */
    fun label(currency: String) = if (fiat) "$currency $name" else name
    fun link(to: String, amount: BigDecimal, memo: String, currency: String = currencies.firstOrNull().orEmpty()) = build(to, amount, memo, currency)
}

private fun note(text: String, separator: String, key: String = "message") =
    if (text.isBlank()) "" else separator + key + "=" + enc(text)

private fun enc(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

/** Exact whole units for a token with this many decimals: 20.5 USDC → 20500000. */
internal fun baseUnits(amount: BigDecimal, decimals: Int): String = amount.movePointRight(decimals).toBigIntegerExact().toString()

private val solana = Regex("[1-9A-HJ-NP-Za-km-z]{32,44}")
private val evm = Regex("0x[0-9a-fA-F]{40}")
private val handle = Regex("[A-Za-z0-9._-]{1,64}")

/** Built-in ways to pay. Monero comes first and is the default. Every one opens the payer's own app. */
internal val builtInMoney = listOf(
    MoneyAsset("XMR on Monero", Regex("[48][0-9AB][1-9A-HJ-NP-Za-km-z]{93}|4[0-9AB][1-9A-HJ-NP-Za-km-z]{104}"), "https://xmrchain.net/tx/",
        { to, amount, memo, _ -> "monero:$to?tx_amount=${amount.toPlainString()}" + note(memo, "&", "tx_description") }),
    MoneyAsset("BTC on Bitcoin", Regex("bc1[02-9ac-hj-np-z]{11,87}|[13][1-9A-HJ-NP-Za-km-z]{25,34}"), "https://mempool.space/tx/",
        { to, amount, memo, _ -> "bitcoin:$to?amount=${amount.toPlainString()}" + note(memo, "&") }),
    // A Lightning address (name@wallet.example) or LNURL; the payer's wallet asks for the amount it shows here.
    MoneyAsset("BTC on Lightning", Regex("[A-Za-z0-9._-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|(?i)lnurl1[02-9ac-hj-np-z]{20,}"), "",
        { to, _, _, _ -> "lightning:$to" }, addressLabel = "Lightning address"),
    MoneyAsset("USDC on Solana", solana, "https://solscan.io/tx/", { to, amount, memo, _ ->
        "solana:$to?amount=${amount.toPlainString()}&spl-token=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" + note(memo, "&") }),
    MoneyAsset("SOL on Solana", solana, "https://solscan.io/tx/", { to, amount, memo, _ -> "solana:$to?amount=${amount.toPlainString()}" + note(memo, "&") }),
    MoneyAsset("USDC on Ethereum", evm, "https://etherscan.io/tx/", { to, amount, _, _ ->
        "ethereum:0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48@1/transfer?address=$to&uint256=${baseUnits(amount, 6)}" }),
    MoneyAsset("USDC on Base", evm, "https://basescan.org/tx/", { to, amount, _, _ ->
        "ethereum:0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913@8453/transfer?address=$to&uint256=${baseUnits(amount, 6)}" }),
    MoneyAsset("ETH on Ethereum", evm, "https://etherscan.io/tx/", { to, amount, _, _ -> "ethereum:$to@1?value=${baseUnits(amount, 18)}" }),
    // Regular money, through each service's public payment link.
    MoneyAsset("via PayPal", handle, "", { to, amount, _, currency -> "https://paypal.me/$to/${amount.toPlainString()}$currency" },
        listOf("USD", "EUR", "GBP", "CAD", "AUD", "JPY", "CHF", "MXN", "BRL"), "PayPal.me username"),
    MoneyAsset("via Venmo", handle, "", { to, amount, memo, _ ->
        "https://venmo.com/?txn=pay&recipients=$to&amount=${amount.toPlainString()}" + note(memo, "&", "note") }, listOf("USD"), "Venmo username"),
    MoneyAsset("via Cash App", Regex("\\$?[A-Za-z][A-Za-z0-9_-]{0,19}"), "", { to, amount, _, _ ->
        "https://cash.app/$" + to.removePrefix("$") + "/" + amount.toPlainString() }, listOf("USD", "GBP"), "\$cashtag"),
    MoneyAsset("via Revolut", handle, "", { to, amount, _, currency -> "https://revolut.me/$to?amount=${amount.toPlainString()}&currency=$currency" },
        listOf("EUR", "GBP", "USD", "CHF", "PLN", "SEK", "NOK", "DKK"), "Revolut username"),
    // UPI and payto: are open standards: UPI for India's banks and apps, payto: (RFC 8905) for bank transfers by IBAN.
    MoneyAsset("via UPI", Regex("[A-Za-z0-9._-]{2,256}@[A-Za-z]{2,64}"), "", { to, amount, memo, _ ->
        "upi://pay?pa=$to&am=${amount.toPlainString()}&cu=INR" + note(memo, "&", "tn") }, listOf("INR"), "UPI ID"),
    MoneyAsset("via Bank transfer", Regex("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}"), "", { to, amount, memo, currency ->
        "payto://iban/${to.uppercase()}?amount=$currency:${amount.toPlainString()}" + note(memo, "&") },
        listOf("EUR", "GBP", "CHF", "PLN", "SEK", "DKK", "NOK", "CZK", "HUF", "RON"), "IBAN"),
)

/** Ways to pay you added yourself: a name and a payment link with {address}, {amount}, and {note} in it. */
internal object CustomMoney {
    fun read(prefs: android.content.SharedPreferences): List<MoneyAsset> = runCatching {
        val array = org.json.JSONArray(prefs.getString("money.custom", "[]"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val template = item.optString("template")
            MoneyAsset(item.optString("name"), Regex(".{3,256}"), "", { to, amount, memo, _ ->
                template.replace("{address}", to).replace("{amount}", amount.toPlainString()).replace("{note}", enc(memo))
            }, custom = true, addressLabel = "address")
        }.filter { it.name.isNotBlank() }
    }.getOrDefault(emptyList())

    @JvmStatic fun add(prefs: android.content.SharedPreferences, name: String, template: String) {
        val array = org.json.JSONArray(prefs.getString("money.custom", "[]"))
        array.put(org.json.JSONObject().put("name", name.trim()).put("template", template.trim()))
        prefs.edit().putString("money.custom", array.toString()).apply()
    }

    @JvmStatic fun remove(prefs: android.content.SharedPreferences, name: String) {
        val array = org.json.JSONArray(prefs.getString("money.custom", "[]"))
        val kept = org.json.JSONArray()
        for (index in 0 until array.length()) array.optJSONObject(index)?.takeIf { it.optString("name") != name }?.let(kept::put)
        prefs.edit().putString("money.custom", kept.toString()).apply()
    }

    @JvmStatic fun names(prefs: android.content.SharedPreferences): List<String> = read(prefs).map { it.name }

    /** A payment link for a custom method must be a link an app can open, never javascript: or a file. */
    @JvmStatic fun validTemplate(template: String) = Regex("^[a-z][a-z0-9+.-]{1,30}:\\S*\\{address\\}\\S*$").matches(template.trim()) &&
        !template.trim().lowercase().let { it.startsWith("javascript:") || it.startsWith("file:") || it.startsWith("content:") || it.startsWith("intent:") }
}

internal fun moneyAssets(prefs: android.content.SharedPreferences): List<MoneyAsset> = builtInMoney + CustomMoney.read(prefs)


/** The method a new request starts with: the last one you used, else Monero. */
internal fun defaultMoney(prefs: android.content.SharedPreferences): MoneyAsset {
    val all = moneyAssets(prefs)
    val saved = prefs.getString("money.default", "")
    return all.firstOrNull { it.name == saved } ?: builtInMoney.first()
}

internal data class MoneyMessage(val sent: Boolean, val amount: String, val asset: String, val note: String, val link: String) {
    /** Not one of FluidMessages' built-in ways to pay, so nobody has checked how its link works. */
    val unverified: Boolean get() = builtInMoney.none { it.name.equals(asset, true) || it.fiat && asset.endsWith(" " + it.name, true) }
}

private val moneyPattern = Regex("""^💸 (Payment request|Sent): ([0-9]+(?:\.[0-9]+)?) (.+?)\n(?:For: (.+)\n)?(?:(?:Pay|Transaction): (\S+))?""")
private val paymentLink = Regex("""^((solana|ethereum|bitcoin|monero|lightning|upi):[^\s]+|payto://iban/[^\s]+|https://(paypal\.me|venmo\.com|cash\.app|revolut\.me)/[^\s]+)$""")
private val anyLink = Regex("""^[a-z][a-z0-9+.-]{1,30}:\S+$""")
private val transactionLink = Regex("""^https://(solscan\.io|etherscan\.io|basescan\.org|mempool\.space|xmrchain\.net)/tx/[A-Za-z0-9]+$""")

internal fun parseMoney(text: String): MoneyMessage? {
    val match = moneyPattern.find(text + "\n") ?: return null
    val (kind, amount, asset, note, link) = match.destructured
    return MoneyMessage(kind == "Sent", amount, asset, note, link)
}

internal fun moneyText(request: Boolean, amount: BigDecimal, assetLabel: String, memo: String, link: String): String {
    val forLine = if (memo.isBlank()) "" else "For: ${memo.replace('\n', ' ').take(120)}\n"
    return if (request) "💸 Payment request: ${amount.toPlainString()} $assetLabel\n${forLine}Pay: $link\n" +
        "Sent with FluidMessages · the link opens your own payment app"
    else ("💸 Sent: ${amount.toPlainString()} $assetLabel\n$forLine" + if (link.isEmpty()) "" else "Transaction: $link").trim()
}

private fun openWallet(context: Context, link: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (error: ActivityNotFoundException) { false }

/** The card inside a message bubble. Only received requests offer to pay, and only through a payment link. */
@Composable
internal fun MoneyCard(money: MoneyMessage, outgoing: Boolean, foreground: Color) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var noWallet by remember { mutableStateOf(false) }
    val viaService = " via " in money.asset
    val unit = if (viaService) money.asset.substringBefore(" via ") else money.asset.substringBefore(" on ")
    val network = when {
        viaService -> "via " + money.asset.substringAfter(" via ").replaceFirstChar { it.uppercase() }
        " on " in money.asset -> "on " + money.asset.substringAfter(" on ")
        else -> ""
    }
    Column(Modifier.widthIn(min = 200.dp).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(when {
            money.sent && outgoing -> "You sent"
            money.sent -> "Sent to you"
            outgoing -> "You requested"
            else -> "Requested from you"
        }, color = foreground.copy(alpha = .85f), fontSize = 12.sp)
        Text("${money.amount} $unit", color = foreground, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        if (network.isNotEmpty()) Text(network, color = foreground.copy(alpha = .85f), fontSize = 13.sp)
        if (money.note.isNotBlank()) Text(money.note, color = foreground, fontSize = 15.sp)
        val payable = if (money.unverified) anyLink.matches(money.link) && !money.link.startsWith("javascript:", true)
            else paymentLink.matches(money.link)
        if (!money.sent && !outgoing && payable) {
            FilledTonalButton(onClick = { noWallet = !openWallet(context, money.link) }) {
                Text(if (viaService) "Pay with " + money.asset.substringAfter(" via ").replaceFirstChar { it.uppercase() } else "Pay with your wallet")
            }
            TextButton(onClick = { clipboard.setText(AnnotatedString(money.link)) }) { Text("Copy payment link", color = foreground) }
            if (noWallet) Text("No app on this phone handles this link.", color = foreground, fontSize = 12.sp)
        }
        if (money.sent && transactionLink.matches(money.link)) {
            TextButton(onClick = { openWallet(context, money.link) }) { Text("View transaction", color = foreground) }
        }
        // A method FluidMessages doesn't know: say so plainly, inside the card, before anyone pays.
        if (money.unverified) UnverifiedNotice(foreground, outgoing)
    }
}

@Composable
private fun UnverifiedNotice(foreground: Color, outgoing: Boolean) {
    Row(Modifier.padding(top = 4.dp).fillMaxWidth()
        .background(Color(0xFFFFB020).copy(alpha = .22f), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
        .padding(horizontal = 9.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("⚠\uFE0E", color = foreground, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(if (outgoing) "Custom payment method. FluidMessages can't check its link, so they'll see a warning too."
            else "Custom payment method. FluidMessages can't check where this link sends money. Pay only if you trust the sender and the app it opens.",
            color = foreground, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

/** How a way to pay looks in the picker: a friendly name, a short detail, and a colored badge. */
internal data class MoneyLook(val title: String, val detail: String, val badge: String, val color: Color)

internal fun moneyLook(asset: MoneyAsset): MoneyLook = when (asset.name) {
    "XMR on Monero" -> MoneyLook("Monero", "XMR · private by default", "ɱ", Color(0xFFFF6600))
    "BTC on Bitcoin" -> MoneyLook("Bitcoin", "BTC", "₿", Color(0xFFF7931A))
    "BTC on Lightning" -> MoneyLook("Lightning", "Fast, tiny-fee bitcoin", "⚡", Color(0xFF7B3FE4))
    "USDC on Solana" -> MoneyLook("USDC", "on Solana", "$", Color(0xFF2775CA))
    "SOL on Solana" -> MoneyLook("Solana", "SOL", "◎", Color(0xFF9945FF))
    "USDC on Ethereum" -> MoneyLook("USDC", "on Ethereum", "$", Color(0xFF2775CA))
    "USDC on Base" -> MoneyLook("USDC", "on Base", "$", Color(0xFF0052FF))
    "ETH on Ethereum" -> MoneyLook("Ethereum", "ETH", "Ξ", Color(0xFF627EEA))
    "via PayPal" -> MoneyLook("PayPal", "PayPal.me link", "P", Color(0xFF003087))
    "via Venmo" -> MoneyLook("Venmo", "US dollars", "V", Color(0xFF008CFF))
    "via Cash App" -> MoneyLook("Cash App", "\$cashtag", "$", Color(0xFF00C244))
    "via Revolut" -> MoneyLook("Revolut", "Revolut.me link", "R", Color(0xFF191C1F))
    "via UPI" -> MoneyLook("UPI", "Indian rupees", "₹", Color(0xFF097939))
    "via Bank transfer" -> MoneyLook("Bank transfer", "IBAN · payto: link", "⌂", Color(0xFF5856D6))
    else -> MoneyLook(asset.name.removePrefix("via "), "Added by you", asset.name.take(1).uppercase(), Color(0xFF8E8E93))
}

@Composable
private fun MoneyBadge(look: MoneyLook, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).background(look.color, androidx.compose.foundation.shape.CircleShape), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Text(look.badge, color = Color.White, fontSize = (size.value * .45f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MoneyTile(asset: MoneyAsset, modifier: Modifier, pick: () -> Unit) {
    val look = moneyLook(asset)
    Column(modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClickLabel = look.title, onClick = pick)
        .padding(horizontal = 8.dp, vertical = 12.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        MoneyBadge(look, 38.dp)
        Text(look.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
        Text(look.detail, fontSize = 11.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/**
 * Request or send money from the attachment menu, in two steps: pick how (Monero first), then fill in only
 * what that method needs. post() adds the text to the composer's send.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoneyDialog(dismiss: () -> Unit, post: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("fluid", Context.MODE_PRIVATE) }
    var assets by remember { mutableStateOf(moneyAssets(prefs)) }
    var request by remember { mutableStateOf(true) }
    var chosen by remember { mutableStateOf<MoneyAsset?>(null) }
    var adding by remember { mutableStateOf(false) }
    if (adding) { CustomMoneyDialog({ adding = false }) { added -> adding = false; assets = moneyAssets(prefs); chosen = added }; return }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 18.dp)
            .padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (chosen != null) TextButton(onClick = { chosen = null }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("‹ All methods") }
                Spacer(Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(request, { request = true }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Request") }
                    SegmentedButton(!request, { request = false }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Send") }
                }
            }
            val asset = chosen
            if (asset == null) {
                Text(if (request) "How do you want to be paid?" else "How are you paying?", style = MaterialTheme.typography.titleLarge)
                val usual = defaultMoney(prefs)
                // The usual method sits on top as one big button, so most requests are a single tap away.
                Row(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer).clickable { chosen = usual }.padding(14.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MoneyBadge(moneyLook(usual), 44.dp)
                    Column(Modifier.weight(1f)) {
                        Text("Your usual", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .8f))
                        Text(moneyLook(usual).title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text("›", fontSize = 26.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                for ((title, group) in listOf("Crypto" to assets.filter { !it.fiat && !it.custom }, "Payment apps and banks" to assets.filter { it.fiat },
                    "Added by you" to assets.filter { it.custom })) {
                    if (group.isEmpty()) continue
                    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (row in group.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (item in row) MoneyTile(item, Modifier.weight(1f)) { chosen = item }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Add your own way to pay") }
                Text("FluidMessages never holds money, keys, or card numbers. Payment links open the payer's own app.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else MoneyForm(asset, request, prefs, { dismiss() }) { text -> post(text); dismiss() }
        }
    }
}

@Composable
private fun MoneyForm(asset: MoneyAsset, request: Boolean, prefs: android.content.SharedPreferences, close: () -> Unit, post: (String) -> Unit) {
    val context = LocalContext.current
    val look = moneyLook(asset)
    var currency by remember(asset) { mutableStateOf(prefs.getString("money.currency." + asset.name, null) ?: asset.currencies.firstOrNull().orEmpty()) }
    var amount by remember(asset) { mutableStateOf("") }
    var wallet by remember(asset, request) { mutableStateOf(if (request) prefs.getString("wallet." + asset.name, "").orEmpty() else "") }
    var memo by remember(asset) { mutableStateOf("") }
    var transaction by remember(asset) { mutableStateOf("") }
    var problem by remember(asset) { mutableStateOf<String?>(null) }
    fun validated(): Pair<BigDecimal, String>? {
        val value = amount.trim().toBigDecimalOrNull()
        if (value == null || value.signum() <= 0 || value.scale() > 18 || !Regex("[0-9]+(\\.[0-9]+)?").matches(amount.trim())) {
            problem = "Enter an amount"; return null
        }
        if (asset.fiat && value.scale() > 2) { problem = "Use at most two decimal places"; return null }
        val to = wallet.trim()
        if (!asset.address.matches(to)) { problem = "That doesn't look like a ${asset.addressLabel}"; return null }
        return value to asset.link(to, value, memo.trim(), currency)
    }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MoneyBadge(look, 46.dp)
        Column {
            Text((if (request) "Request with " else "Pay with ") + look.title, style = MaterialTheme.typography.titleLarge)
            Text(look.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (asset.currencies.size > 1) Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (option in asset.currencies) FilterChip(currency == option, { currency = option }, label = { Text(option) })
    }
    OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' }.take(24); problem = null }, Modifier.fillMaxWidth(),
        label = { Text("Amount") }, singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
        suffix = { Text(if (asset.fiat) currency else asset.name.substringBefore(" on ").substringBefore(" via ")) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    OutlinedTextField(wallet, { wallet = it; problem = null }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(if (request) "Your ${asset.addressLabel}" else "Their ${asset.addressLabel}") },
        supportingText = if (request && wallet.isNotEmpty()) ({ Text("Remembered for next time") }) else null)
    OutlinedTextField(memo, { memo = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("What it's for (optional)") }, singleLine = true)
    if (!request) {
        OutlinedButton(onClick = {
            val checked = validated() ?: return@OutlinedButton
            if (!openWallet(context, checked.second)) problem = "No app on this phone opens ${look.title} payment links."
        }, modifier = Modifier.fillMaxWidth()) { Text("Open ${if (asset.fiat) look.title else "your wallet"} to pay") }
        if (!asset.fiat && asset.explorer.isNotEmpty()) OutlinedTextField(transaction, { transaction = it.trim() }, Modifier.fillMaxWidth(),
            singleLine = true, label = { Text("Transaction ID after paying (optional)") })
    }
    if (asset.custom) UnverifiedNotice(MaterialTheme.colorScheme.onSurface, true)
    Text(if (asset.fiat) "The payment happens in ${look.title}. FluidMessages never holds money."
        else "Crypto payments can't be reversed, so check the address.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = close, modifier = Modifier.weight(1f)) { Text("Cancel") }
        Button(onClick = {
            val checked = validated() ?: return@Button
            if (!request && transaction.isNotEmpty() && !Regex("[A-Za-z0-9]{32,128}").matches(transaction)) {
                problem = "Enter the transaction ID only"; return@Button
            }
            val edit = prefs.edit().putString("money.default", asset.name)
            if (asset.fiat) edit.putString("money.currency." + asset.name, currency)
            if (request) edit.putString("wallet." + asset.name, wallet.trim())
            edit.apply()
            val link = if (request) checked.second else if (transaction.isEmpty() || asset.explorer.isEmpty()) "" else asset.explorer + transaction
            post(moneyText(request, checked.first, asset.label(currency), memo, link))
        }, modifier = Modifier.weight(1f)) { Text(if (request) "Send request" else "Post that you paid") }
    }
}

/** Add a coin or payment service FluidMessages doesn't include, by its payment link. */
@Composable
internal fun CustomMoneyDialog(dismiss: () -> Unit, added: (MoneyAsset) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("fluid", Context.MODE_PRIVATE) }
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Add a way to pay") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Any coin or service with a payment link works. Put {address}, {amount}, and {note} where they go.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(name, { name = it.take(40); problem = null }, singleLine = true, label = { Text("Name") },
                    placeholder = { Text("DOGE on Dogecoin") })
                OutlinedTextField(template, { template = it.trim(); problem = null }, singleLine = true, label = { Text("Payment link") },
                    placeholder = { Text("dogecoin:{address}?amount={amount}") })
                UnverifiedNotice(MaterialTheme.colorScheme.onSurface, true)
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    name.isBlank() -> problem = "Enter a name"
                    builtInMoney.any { it.name.equals(name.trim(), true) } -> problem = "That one is built in already"
                    !CustomMoney.validTemplate(template) -> problem = "Enter a link like coin:{address}?amount={amount}"
                    else -> {
                        CustomMoney.add(prefs, name, template)
                        CustomMoney.read(prefs).lastOrNull { it.name == name.trim() }?.let(added)
                    }
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
