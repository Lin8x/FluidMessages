package org.fluidmessages

/**
 * Email account ports, the same on the phone (DirectEmail), FluidMessages Web (companion.js), and the companion
 * (accounts.py). An account saved without ports uses the defaults, so older accounts connect as before. TLS is always
 * on: SMTP on 465 and IMAP on any port but 143 use TLS from the start, while SMTP on any other port (such as 587) and
 * IMAP on 143 must upgrade with STARTTLS. Nothing falls back to plain text.
 */
internal object EmailPorts {
    const val IMAP = 993
    const val SMTP = 465
    const val IMAP_KEY = "imap_port"
    const val SMTP_KEY = "smtp_port"

    const val IMAP_HINT = "993 uses TLS from the start. 143 upgrades with STARTTLS."
    const val SMTP_HINT = "465 uses TLS from the start. Other ports, like 587, upgrade with STARTTLS."

    @JvmStatic fun valid(port: Int) = port in 1..65535

    /** A typed port, or null when it isn't a whole number from 1 to 65535. */
    @JvmStatic fun parse(text: String): Int? = text.trim().takeIf { it.length in 1..5 && it.all(Char::isDigit) }?.toInt()?.takeIf(::valid)

    @JvmStatic fun imapStartTls(port: Int) = port == 143

    @JvmStatic fun smtpStartTls(port: Int) = port != 465

    /** The port to use for a saved value: the default when it's missing or out of range. */
    @JvmStatic fun orDefault(saved: Int, default: Int) = if (valid(saved)) saved else default
}
