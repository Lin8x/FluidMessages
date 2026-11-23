package org.fluidmessages

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
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
internal data class MoneyAsset(val name: String, val address: Regex, val explorer: String, val link: (String, BigDecimal, String) -> String)

private fun note(text: String, separator: String) =
    if (text.isBlank()) "" else separator + "message=" + URLEncoder.encode(text, "UTF-8").replace("+", "%20")

/** Exact whole units for a token with this many decimals: 20.5 USDC → 20500000. */
internal fun baseUnits(amount: BigDecimal, decimals: Int): String = amount.movePointRight(decimals).toBigIntegerExact().toString()

internal val moneyAssets = listOf(
    MoneyAsset("USDC on Solana", Regex("[1-9A-HJ-NP-Za-km-z]{32,44}"), "https://solscan.io/tx/") { to, amount, memo ->
        "solana:$to?amount=${amount.toPlainString()}&spl-token=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" + note(memo, "&") },
    MoneyAsset("SOL on Solana", Regex("[1-9A-HJ-NP-Za-km-z]{32,44}"), "https://solscan.io/tx/") { to, amount, memo ->
        "solana:$to?amount=${amount.toPlainString()}" + note(memo, "&") },
    MoneyAsset("USDC on Ethereum", Regex("0x[0-9a-fA-F]{40}"), "https://etherscan.io/tx/") { to, amount, _ ->
        "ethereum:0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48@1/transfer?address=$to&uint256=${baseUnits(amount, 6)}" },
    MoneyAsset("USDC on Base", Regex("0x[0-9a-fA-F]{40}"), "https://basescan.org/tx/") { to, amount, _ ->
        "ethereum:0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913@8453/transfer?address=$to&uint256=${baseUnits(amount, 6)}" },
    MoneyAsset("ETH on Ethereum", Regex("0x[0-9a-fA-F]{40}"), "https://etherscan.io/tx/") { to, amount, _ ->
        "ethereum:$to@1?value=${baseUnits(amount, 18)}" },
    MoneyAsset("BTC on Bitcoin", Regex("bc1[02-9ac-hj-np-z]{11,87}|[13][1-9A-HJ-NP-Za-km-z]{25,34}"), "https://mempool.space/tx/") { to, amount, memo ->
        "bitcoin:$to?amount=${amount.toPlainString()}" + note(memo, "&") },
)

internal data class MoneyMessage(val sent: Boolean, val amount: String, val asset: String, val note: String, val link: String)

private val moneyPattern = Regex("""^💸 (Payment request|Sent): ([0-9]+(?:\.[0-9]+)?) (.+?)\n(?:For: (.+)\n)?(?:(?:Pay|Transaction): (\S+))?""")
private val paymentLink = Regex("""^(solana|ethereum|bitcoin):[^\s]+$""")
private val transactionLink = Regex("""^https://(solscan\.io|etherscan\.io|basescan\.org|mempool\.space)/tx/[A-Za-z0-9]+$""")

internal fun parseMoney(text: String): MoneyMessage? {
    val match = moneyPattern.find(text + "\n") ?: return null
    val (kind, amount, asset, note, link) = match.destructured
    return MoneyMessage(kind == "Sent", amount, asset, note, link)
}

internal fun moneyText(request: Boolean, amount: BigDecimal, asset: MoneyAsset, memo: String, link: String): String {
    val forLine = if (memo.isBlank()) "" else "For: ${memo.replace('\n', ' ').take(120)}\n"
    return if (request) "💸 Payment request: ${amount.toPlainString()} ${asset.name}\n${forLine}Pay: $link\n" +
        "Sent with FluidMessages · the link opens your own wallet app"
    else ("💸 Sent: ${amount.toPlainString()} ${asset.name}\n$forLine" + if (link.isEmpty()) "" else "Transaction: $link").trim()
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
    Column(Modifier.widthIn(min = 190.dp).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(when {
            money.sent && outgoing -> "You sent"
            money.sent -> "Sent to you"
            outgoing -> "You requested"
            else -> "Requested from you"
        }, color = foreground.copy(alpha = .85f), fontSize = 12.sp)
        Text("${money.amount} ${money.asset.substringBefore(" on ")}", color = foreground, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        if (" on " in money.asset) Text("on " + money.asset.substringAfter(" on "), color = foreground.copy(alpha = .85f), fontSize = 13.sp)
        if (money.note.isNotBlank()) Text(money.note, color = foreground, fontSize = 15.sp)
        if (!money.sent && !outgoing && paymentLink.matches(money.link)) {
            FilledTonalButton(onClick = { noWallet = !openWallet(context, money.link) }) { Text("Pay with your wallet") }
            TextButton(onClick = { clipboard.setText(AnnotatedString(money.link)) }) { Text("Copy payment link", color = foreground) }
            if (noWallet) Text("No wallet app on this phone handles this link.", color = foreground, fontSize = 12.sp)
        }
        if (money.sent && transactionLink.matches(money.link)) {
            TextButton(onClick = { openWallet(context, money.link) }) { Text("View transaction", color = foreground) }
        }
    }
}

/** Request or send money from the attachment menu. post() adds the text to the composer's send. */
@Composable
internal fun MoneyDialog(dismiss: () -> Unit, post: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("fluid", Context.MODE_PRIVATE) }
    var request by remember { mutableStateOf(true) }
    var asset by remember { mutableStateOf(moneyAssets.first()) }
    var amount by remember { mutableStateOf("") }
    var wallet by remember { mutableStateOf(prefs.getString("wallet." + moneyAssets.first().name, "").orEmpty()) }
    var memo by remember { mutableStateOf("") }
    var transaction by remember { mutableStateOf("") }
    var assetMenu by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var paid by remember { mutableStateOf(false) }
    fun validated(): Pair<BigDecimal, String>? {
        val value = amount.trim().toBigDecimalOrNull()
        if (value == null || value.signum() <= 0 || value.scale() > 18 || !Regex("[0-9]+(\\.[0-9]+)?").matches(amount.trim())) {
            problem = "Enter an amount"; return null
        }
        val to = wallet.trim()
        if (!asset.address.matches(to)) { problem = "That doesn't look like a ${asset.name.substringAfter(" on ")} address"; return null }
        return value to asset.link(to, value, memo.trim())
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (request) "Request money" else "Send money") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(request, onClick = {
                        request = true; paid = false
                        wallet = prefs.getString("wallet." + asset.name, "").orEmpty()
                    }, label = { Text("Request") })
                    FilterChip(!request, onClick = { request = false; wallet = "" }, label = { Text("Send") })
                }
                Box {
                    OutlinedButton(onClick = { assetMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(asset.name) }
                    DropdownMenu(assetMenu, onDismissRequest = { assetMenu = false }) {
                        for (option in moneyAssets) DropdownMenuItem(text = { Text(option.name) }, onClick = {
                            asset = option; assetMenu = false
                            if (request) wallet = prefs.getString("wallet." + option.name, "").orEmpty()
                        })
                    }
                }
                OutlinedTextField(amount, { amount = it; problem = null }, label = { Text("Amount") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(wallet, { wallet = it; problem = null }, singleLine = true,
                    label = { Text(if (request) "Your wallet address" else "Their wallet address") })
                OutlinedTextField(memo, { memo = it.take(120) }, label = { Text("What it's for (optional)") }, singleLine = true)
                if (!request) {
                    OutlinedButton(onClick = {
                        val checked = validated() ?: return@OutlinedButton
                        paid = openWallet(context, checked.second)
                        if (!paid) problem = "No wallet app on this phone handles ${asset.name.substringAfter(" on ")} payment links."
                    }, modifier = Modifier.fillMaxWidth()) { Text("Open your wallet to pay") }
                    OutlinedTextField(transaction, { transaction = it.trim() }, singleLine = true,
                        label = { Text("Transaction ID after paying (optional)") })
                }
                Text("FluidMessages never holds money or keys. Crypto payments can't be reversed, so check the address.",
                    style = MaterialTheme.typography.bodySmall)
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val checked = validated() ?: return@TextButton
                if (!request && transaction.isNotEmpty() && !Regex("[A-Za-z0-9]{32,128}").matches(transaction)) {
                    problem = "Enter the transaction ID only"; return@TextButton
                }
                if (request) prefs.edit().putString("wallet." + asset.name, wallet.trim()).apply()
                val link = if (request) checked.second else if (transaction.isEmpty()) "" else asset.explorer + transaction
                post(moneyText(request, checked.first, asset, memo, link))
                dismiss()
            }) { Text(if (request) "Send request" else "Post that you paid") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
