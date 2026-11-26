package org.fluidmessages

import android.content.SharedPreferences
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Every connection to FluidMessages Web opens here (CloudBackup, SyncJob, GatewayClient, SyncMergeDevice).
 *
 * With a certificate fingerprint saved next to the address ("serverPin", from the QR code or typed in), this phone
 * trusts exactly that certificate for FluidMessages Web and nothing else: no certificate authority, and only at the
 * saved address. Without one, https:// uses Android's normal trust (for your own domain) and http:// stays plain,
 * with its warning. If the certificate ever changes, the connection stops, "serverPinChanged" is set so Settings can
 * say so, and nothing falls back to a weaker check. Other connections in the app are untouched.
 */
object CompanionTls {
    const val PIN = "serverPin"
    const val CHANGED = "serverPinChanged"

    const val CHANGED_MESSAGE = "FluidMessages Web’s certificate changed, so this phone stopped connecting to keep your " +
        "messages safe. If you reinstalled or reset FluidMessages Web, pair again: scan its QR code. If you didn’t, " +
        "someone may be trying to listen in."

    /** Thrown when the certificate isn't the pinned one. */
    class CertificateChanged : CertificateException("FluidMessages Web's certificate changed")

    /** Trusts one certificate, by its SHA-256 fingerprint. [changed] runs when another one shows up. */
    class PinnedTrust(private val pin: String, private val changed: Runnable?) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("Not for clients")

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (!CompanionPairing.pinMatches(chain?.firstOrNull()?.encoded, pin)) {
                changed?.run()
                throw CertificateChanged()
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Accepts the pinned certificate at the saved address's host, whatever name or IP address the certificate lists. */
    class PinnedHost(private val origin: String, private val pin: String) : HostnameVerifier {
        override fun verify(hostname: String?, session: SSLSession?): Boolean {
            if (!CompanionPairing.sameHost(origin, hostname)) return false
            val peer = try { session?.peerCertificates?.firstOrNull() } catch (error: Exception) { null }
            return CompanionPairing.pinMatches(peer?.encoded, pin)
        }
    }

    @Volatile private var factory: Pair<String, SSLSocketFactory>? = null

    /** The saved pin's factory is kept, so connections can resume TLS sessions; a QR code's addresses get their own. */
    private fun socketFactory(pin: String, changed: Runnable?, keep: Boolean): SSLSocketFactory {
        if (keep) factory?.let { (cached, made) -> if (cached == pin) return made }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(PinnedTrust(pin, changed)), null)
        return context.socketFactory.also { if (keep) factory = pin to it }
    }

    /** Opens [url] on the saved FluidMessages Web, pinned when a fingerprint is saved. */
    @JvmStatic
    fun open(prefs: SharedPreferences, url: String): HttpURLConnection {
        val pin = prefs.getString(PIN, "").orEmpty()
        return open(url, prefs.getString("server", "").orEmpty(), pin.ifEmpty { null }, {
            if (!prefs.getBoolean(CHANGED, false)) prefs.edit().putBoolean(CHANGED, true).apply()
        }, true)
    }

    /**
     * Opens [url] at [origin], trusting only the certificate with fingerprint [pin] when there is one. Used before
     * saving too, to try each address from a QR code.
     */
    @JvmStatic
    fun open(url: String, origin: String, pin: String?, changed: Runnable?): HttpURLConnection = open(url, origin, pin, changed, false)

    private fun open(url: String, origin: String, pin: String?, changed: Runnable?, keep: Boolean): HttpURLConnection {
        // Direct on your own network, through Tor on the internet when Connect through Tor is on (TorRoute).
        val connection = TorRoute.openOwn(URL(url)) as HttpURLConnection
        if (pin != null) {
            // A pin with an http:// address can't be checked, so it never connects in the clear instead.
            if (connection !is HttpsURLConnection) throw java.io.IOException("A certificate fingerprint needs an https:// address")
            connection.sslSocketFactory = socketFactory(pin, changed, keep)
            connection.hostnameVerifier = PinnedHost(origin, pin)
        }
        return connection
    }

    /** [probe]'s answers besides an HTTP status: nothing answered, or a different certificate answered. */
    const val UNREACHABLE = -1
    const val WRONG_CERTIFICATE = -2

    /**
     * Asks FluidMessages Web at [origin] for its backup list with [token], before anything is saved (scanning a QR
     * code). Returns the HTTP status (200 when it works), [UNREACHABLE], or [WRONG_CERTIFICATE].
     */
    @JvmStatic
    fun probe(origin: String, pin: String?, token: String): Int {
        val connection = try { open("$origin/api/backups", origin, pin, null) } catch (error: Exception) { return UNREACHABLE }
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 6000
            connection.readTimeout = 8000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.responseCode
        } catch (error: Exception) {
            if (changed(error) || (pin != null && error is javax.net.ssl.SSLPeerUnverifiedException)) WRONG_CERTIFICATE else UNREACHABLE
        } finally { connection.disconnect() }
    }

    /** Whether [error], or what caused it, is the certificate changing. */
    @JvmStatic
    fun changed(error: Throwable?): Boolean {
        var cause = error
        var depth = 0
        while (cause != null && depth++ < 10) {
            if (cause is CertificateChanged) return true
            cause = cause.cause
        }
        return false
    }
}
