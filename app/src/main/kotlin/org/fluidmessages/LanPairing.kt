package org.fluidmessages

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.Inet4Address
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Find my computer: FluidMessages Web on the same Wi-Fi, found without typing an address, and connected with one
 * click on the computer.
 *
 * FluidMessages Web answers multicast DNS for `_fluidmessages._tcp` while its HTTPS port listens on the local network
 * (server/lan_announce.py), with its name, port, addresses and certificate fingerprint. This phone finds it with
 * Android's own service discovery (NsdManager), connects trusting only that fingerprint (CompanionTls), and asks to
 * connect with its model name and a random secret it keeps (server/nearby_pair.py). Both screens show the same six
 * digits, made from that secret and the certificate this phone actually connected to, so someone in the middle with
 * another certificate would show different digits. Clicking Connect on the computer makes the pairing token, which
 * this phone collects with its secret. The parts without Android are pure and unit tested (LanPairingTest).
 */
object LanPairing {
    const val SERVICE_TYPE = "_fluidmessages._tcp."

    /** A computer found on the Wi-Fi: where to try, in order, and the fingerprint to trust it by. */
    data class Computer(val key: String, val name: String, val origins: List<String>, val fingerprint: String)

    /** The six digits both screens show, as "123 456" (nearby_pair.code). */
    @JvmStatic
    fun code(fingerprint: String, secret: String): String {
        val text = "fluidmessages-pair:" + fingerprint.replace(":", "").lowercase() + ":" + secret
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
        val number = ((digest[0].toLong() and 0xff) shl 24 or ((digest[1].toLong() and 0xff) shl 16) or
            ((digest[2].toLong() and 0xff) shl 8) or (digest[3].toLong() and 0xff)) % 1_000_000
        val digits = "%06d".format(number)
        return digits.substring(0, 3) + " " + digits.substring(3)
    }

    /** A random secret this phone keeps while it asks: 48 hex digits. */
    @JvmStatic
    fun secret(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Whether [ip] is an IPv4 address on a home or private network (the only kind tried). */
    @JvmStatic
    fun privateIpv4(ip: String): Boolean {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return false
        val numbers = parts.map { part -> if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return false else part.toInt() }
        if (numbers.any { it > 255 }) return false
        val (a, b) = numbers
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127)
    }

    /**
     * The https:// addresses to try for a found computer: the one Android resolved first, then the ones it lists
     * itself (TXT `a`), private IPv4 addresses only, at most six.
     */
    @JvmStatic
    fun origins(resolved: String?, listed: String?, port: Int): List<String> {
        if (port !in 1..65535) return emptyList()
        val ips = mutableListOf<String>()
        for (ip in listOfNotNull(resolved) + listed.orEmpty().split(',')) {
            val trimmed = ip.trim()
            if (privateIpv4(trimmed) && trimmed !in ips) ips += trimmed
        }
        return ips.take(6).map { "https://$it:$port" }
    }

    /** What this phone is called on the computer's screen: like "Pixel 8" or "Samsung SM-S918B". */
    @JvmStatic
    fun phoneName(manufacturer: String?, model: String?): String {
        val made = manufacturer.orEmpty().trim().replaceFirstChar { it.uppercaseChar() }
        val name = model.orEmpty().trim()
        val shown = when {
            name.isEmpty() -> made
            made.isEmpty() || name.startsWith(made, ignoreCase = true) || made.equals("Google", ignoreCase = true) -> name
            else -> "$made $name"
        }
        return shown.filter { !it.isISOControl() }.take(40).ifBlank { "An Android phone" }
    }

    /** A found service as a Computer, or null without a usable fingerprint or address. */
    @JvmStatic
    fun computer(serviceName: String, txt: Map<String, String>, resolved: String?, port: Int): Computer? {
        val fingerprint = CompanionPairing.normalize(txt["fp"].orEmpty()) ?: return null
        val origins = origins(resolved, txt["a"], port)
        if (origins.isEmpty()) return null
        val name = txt["name"].orEmpty().filter { !it.isISOControl() }.trim().take(50).ifEmpty { serviceName.take(50) }
        return Computer(fingerprint, name.ifEmpty { "Computer" }, origins, fingerprint)
    }

    /** The answer to asking: the request id, or why not. */
    data class Asked(val origin: String?, val id: String?, val problem: String?)

    /** What the computer said while this phone waits: waiting, approved (with [token]), declined, or gone. */
    data class Answer(val state: String, val token: String?)

    private fun post(origin: String, fingerprint: String, path: String, body: JSONObject): Pair<Int, JSONObject?> {
        val connection = CompanionTls.open(origin + path, origin, fingerprint, null)
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 8000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) }.orEmpty()
            status to (try { JSONObject(text) } catch (error: Exception) { null })
        } finally { connection.disconnect() }
    }

    /** Asks [computer] to connect, trying its addresses in order. Off the main thread. */
    @JvmStatic
    fun ask(computer: Computer, phone: String, secret: String): Asked {
        var problem = "Couldn’t reach ${computer.name}. Check that this phone is on the same Wi-Fi."
        for (origin in computer.origins) {
            try {
                val (status, answer) = post(origin, computer.fingerprint, "/api/pair/hello", JSONObject().put("name", phone).put("secret", secret))
                val id = answer?.optString("id").orEmpty()
                if (status == 200 && id.isNotEmpty()) return Asked(origin, id, null)
                problem = answer?.optString("error")?.ifEmpty { null } ?: "${computer.name} didn’t answer the way FluidMessages Web does."
                if (status in 400..499) return Asked(null, null, problem)
            } catch (error: Exception) {
                if (CompanionTls.changed(error) || error is javax.net.ssl.SSLPeerUnverifiedException)
                    problem = "${computer.name} answered with a different certificate than it announced, so this phone didn’t connect."
            }
        }
        return Asked(null, null, problem)
    }

    /** Checks whether the computer said Connect. Null when it can't be reached right now. Off the main thread. */
    @JvmStatic
    fun check(origin: String, fingerprint: String, id: String, secret: String): Answer? = try {
        val (status, answer) = post(origin, fingerprint, "/api/pair/wait", JSONObject().put("id", id).put("secret", secret))
        if (status != 200 || answer == null) null
        else Answer(answer.optString("state", "gone"), answer.optString("token").ifEmpty { null })
    } catch (error: Exception) { null }

    /**
     * Android's service discovery for FluidMessages Web, while a page shows it. [changed] gets the computers found so
     * far on the main thread, each time the list changes. Resolves one at a time, which older Android versions need.
     */
    class Finder(context: Context, private val changed: (List<Computer>) -> Unit) {
        private val nsd = context.getSystemService(NsdManager::class.java)
        private val main = Handler(Looper.getMainLooper())
        private val found = LinkedHashMap<String, Computer>()
        private val byService = HashMap<String, String>()
        private val queue = ArrayDeque<NsdServiceInfo>()
        private var resolving = false
        @Volatile private var running = false

        private val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { running = false }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { main.post { if (running) { queue.addLast(info); next() } } }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    val key = byService.remove(info.serviceName) ?: return@post
                    if (byService.values.none { it == key } && found.remove(key) != null) changed(found.values.toList())
                }
            }
        }

        fun start() {
            if (running || nsd == null) return
            running = true
            try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) } catch (error: Exception) { running = false }
        }

        fun stop() {
            if (!running) return
            running = false
            queue.clear()
            try { nsd?.stopServiceDiscovery(discovery) } catch (error: Exception) {}
        }

        @Suppress("DEPRECATION")
        private fun next() {
            if (resolving || !running) return
            val info = queue.removeFirstOrNull() ?: return
            resolving = true
            try {
                nsd?.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        main.post { resolving = false; next() }
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val txt = serviceInfo.attributes.orEmpty().mapValues { (_, value) -> value?.toString(StandardCharsets.UTF_8).orEmpty() }
                        val host = (serviceInfo.host as? Inet4Address)?.hostAddress
                        val computer = computer(serviceInfo.serviceName.orEmpty(), txt, host, serviceInfo.port)
                        main.post {
                            resolving = false
                            if (running && computer != null) {
                                byService[serviceInfo.serviceName.orEmpty()] = computer.key
                                val before = found[computer.key]
                                found[computer.key] = computer
                                if (before != computer) changed(found.values.toList())
                            }
                            next()
                        }
                    }
                })
            } catch (error: Exception) {
                resolving = false
                main.post { next() }
            }
        }
    }
}
