package org.fluidmessages

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Nearby: messages between FluidMessages phones on the same Wi-Fi, with no internet. Phones find each other with
 * Android's network service discovery (mDNS, service type [NearbyWire.SERVICE_TYPE]) and talk over TCP.
 *
 * Every connection starts with a Noise_XX_25519_ChaChaPoly_SHA256 handshake (Noise.kt) using each phone's static key
 * from pairing, so both sides prove who they are and each connection has its own keys (forward secrecy). A phone
 * only keeps a connection with someone you've added; anyone else is dropped during the handshake. The one exception
 * is the "Add nearby person" page: while it's open on both phones they connect, show the same six-digit safety code,
 * and nothing is sent until each person taps It matches.
 *
 * Messages wait in the shared outbox (DirectProtocols) until the person's phone is connected, then go oldest first.
 * The other phone stores each one once (by sender and message id) and confirms it; only a confirmed message reads
 * "Sent nearby", and an unconfirmed one is sent again on the next connection.
 *
 * Bluetooth and Wi-Fi Direct are later steps. The identity (private key, name, id) is the protocol's account, sealed
 * with the Android Keystore like other accounts and never in backups. People you add are its links (directRoutes):
 * their public key, name, and id.
 */
internal class DirectNearby(private val signature: String) : DirectProtocols.DirectClient {
    override fun toString() = signature
    override fun persistent() = true

    private lateinit var app: Context
    private lateinit var identity: Identity
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    @Volatile private var ownServiceName = ""
    private val workers = Executors.newCachedThreadPool { Thread(it, "fluid-nearby").apply { isDaemon = true } }
    private val resolver = Executors.newSingleThreadExecutor { Thread(it, "fluid-nearby-resolve").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "fluid-nearby-timer").apply { isDaemon = true } }
    private val handshakes = Semaphore(8)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val found = ConcurrentHashMap<String, Found>()
    private val acks = ConcurrentHashMap<String, CountDownLatch>()
    @Volatile private var pairingUntil = 0L

    class Identity(val keys: NoiseCrypto.KeyPair, val name: String, val id: String) {
        val card get() = NearbyWire.Card(keys.public, name, id)
    }

    private class Found(val host: InetAddress, val port: Int) {
        @Volatile var lastTry = 0L
        @Volatile var peer = ""
        @Volatile var self = false
    }

    /** Someone on the Add nearby person page who isn't added yet, with the safety code both phones show. */
    data class Candidate(val key: String, val name: String, val id: String, val code: String)

    override fun start(context: Context, account: JSONObject) {
        app = context.applicationContext
        identity = identityOf(account) ?: return
        running = true
        current = this
        workers.execute {
            try {
                val socket = try { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) } } catch (busy: IOException) { ServerSocket(0) }
                server = socket
                register(socket.localPort)
                discover()
                while (running) {
                    val client = try { socket.accept() } catch (closed: IOException) { break }
                    if (!handshakes.tryAcquire()) { client.close(); continue }
                    workers.execute { try { open(client, initiator = false, service = null) } finally { handshakes.release() } }
                }
            } catch (error: Exception) {
                status("Nearby couldn’t start on this network")
            }
        }
        timer.scheduleWithFixedDelay({ tick() }, 3, 15, TimeUnit.SECONDS)
        timer.scheduleWithFixedDelay({ ping() }, 25, 25, TimeUnit.SECONDS)
    }

    override fun stop() {
        running = false
        if (current === this) current = null
        try { registration?.let { nsd?.unregisterService(it) } } catch (ignored: Exception) {}
        try { discovery?.let { nsd?.stopServiceDiscovery(it) } } catch (ignored: Exception) {}
        try { server?.close() } catch (ignored: IOException) {}
        for (session in sessions.values) session.close()
        sessions.clear()
        acks.values.forEach { it.countDown() }
        timer.shutdownNow()
        resolver.shutdownNow()
        workers.shutdownNow()
    }

    override fun reconnectNow() { retryAll() }

    // Sending

    override fun send(context: Context, route: JSONObject, message: JSONObject, file: MediaSanitizer.Prepared?): JSONObject {
        if (file != null) throw IllegalStateException("Nearby sends words only for now. Photos are coming later.")
        val body = message.optString("body")
        if (body.toByteArray(Charsets.UTF_8).size > NearbyWire.MAX_TEXT_BYTES) throw IllegalArgumentException("That message is too long to send nearby")
        val peer = route.optString("target")
        val session = sessions[peer] ?: throw IOException("Not nearby")
        val wireId = NearbyWire.wireId(message.getString("id"))
        val latch = CountDownLatch(1)
        acks[wireId] = latch
        try {
            session.write(NearbyWire.Packet.Text(wireId, message.optLong("date"), body))
            // Sent only once the other phone says it has it; otherwise it waits and goes again next time.
            if (!latch.await(15, TimeUnit.SECONDS) || !running) throw IOException("No answer from their phone")
        } finally { acks.remove(wireId) }
        return JSONObject()
    }

    // Finding phones

    private fun register(port: Int) {
        val manager = app.getSystemService(NsdManager::class.java) ?: return
        nsd = manager
        val info = NsdServiceInfo().apply {
            // A random name each time, so the network doesn't learn who you are from it.
            serviceName = "FluidMessages " + NearbyWire.newId().take(6)
            serviceType = NearbyWire.SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { ownServiceName = info.serviceName }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { status("Nearby couldn’t announce this phone") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        registration = listener
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun discover() {
        val manager = nsd ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { status("Nearby can’t look for phones on this network") }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!running || info.serviceName == ownServiceName || !info.serviceType.contains("_fluidnearby")) return
                resolver.execute { resolve(info) }
            }
            override fun onServiceLost(info: NsdServiceInfo) { found.remove(info.serviceName) }
        }
        discovery = listener
        manager.discoverServices(NearbyWire.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /** One resolve at a time: older Android versions refuse a second while one is running. */
    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        val manager = nsd ?: return
        val done = CountDownLatch(1)
        manager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { done.countDown() }
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host = resolved.host
                val ours = resolved.serviceName == ownServiceName || host in ownAddresses() && resolved.port == server?.localPort
                if (host != null && !ours) {
                    val entry = Found(host, resolved.port)
                    found[resolved.serviceName] = entry
                    workers.execute { connect(resolved.serviceName, entry) }
                }
                done.countDown()
            }
        })
        try { done.await(10, TimeUnit.SECONDS) } catch (stop: InterruptedException) {}
    }

    private fun ownAddresses(): Set<InetAddress> = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.toSet()
    } catch (error: Exception) { emptySet() }

    /** Connects to phones it has found but isn't talking to, and tries again now and then. */
    private fun tick() {
        if (!running) return
        val now = System.currentTimeMillis()
        for ((name, entry) in found) {
            if (entry.self || entry.peer.isNotEmpty() && sessions.containsKey(entry.peer)) continue
            if (now - entry.lastTry < RETRY_MS) continue
            workers.execute { connect(name, entry) }
        }
        if (pairingUntil in 1 until now) stopPairing()
    }

    private fun retryAll() {
        for (entry in found.values) entry.lastTry = 0
        workers.execute { tick() }
    }

    private fun connect(service: String?, entry: Found) {
        if (!running || entry.self || entry.peer.isNotEmpty() && sessions.containsKey(entry.peer)) return
        entry.lastTry = System.currentTimeMillis()
        connectTo(InetSocketAddress(entry.host, entry.port), service, entry)
    }

    /** "Connect by address", for networks that block finding phones, or two emulators through adb forwarding. */
    fun connectToAddress(host: String, port: Int) {
        workers.execute { connectTo(InetSocketAddress(host, port), null, null) }
    }

    private fun connectTo(address: InetSocketAddress, service: String?, entry: Found?) {
        if (!handshakes.tryAcquire()) return
        try {
            val socket = Socket()
            try { socket.connect(address, 5000) } catch (error: IOException) { socket.close(); return }
            open(socket, initiator = true, service = entry)
        } finally { handshakes.release() }
    }

    // Connections

    /** Runs the handshake on a new connection, then reads from it until it closes. */
    private fun open(socket: Socket, initiator: Boolean, service: Found?) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 10_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val handshake = NoiseHandshake(NoisePattern.XX, initiator, NearbyWire.PROLOGUE, identity.keys)
            val hello = NearbyWire.encode(NearbyWire.Packet.Hello(identity.name, identity.id))
            val theirs: NearbyWire.Packet
            if (initiator) {
                output.write(NearbyWire.frame(handshake.writeMessage(ByteArray(0))))
                output.flush()
                theirs = NearbyWire.decode(handshake.readMessage(readFrame(input)))
                // Decide before saying who this phone is, so someone you haven't added never learns your key.
                if (!welcome(handshake.remoteStatic!!, service)) { socket.close(); return }
                output.write(NearbyWire.frame(handshake.writeMessage(hello)))
                output.flush()
            } else {
                handshake.readMessage(readFrame(input))
                output.write(NearbyWire.frame(handshake.writeMessage(hello)))
                output.flush()
                theirs = NearbyWire.decode(handshake.readMessage(readFrame(input)))
                if (!welcome(handshake.remoteStatic!!, null)) { socket.close(); return }
            }
            val greeting = theirs as? NearbyWire.Packet.Hello ?: throw IOException("Expected a hello")
            val peer = NoiseCrypto.hex(handshake.remoteStatic!!)
            val session = Session(socket, output, handshake.transport(), peer, greeting.name, greeting.id,
                starter = if (initiator) identity.card.keyHex else peer, code = NearbyWire.safetyCode(handshake.handshakeHash))
            service?.peer = peer
            socket.soTimeout = 70_000
            if (!adopt(session)) return
            read(session, input)
        } catch (error: Exception) {
            // A failed or refused handshake just closes; nothing about it is kept.
            try { socket.close() } catch (ignored: IOException) {}
        }
    }

    private fun readFrame(input: InputStream): ByteArray = NearbyWire.readFrame(input) ?: throw IOException("Closed")

    /** Whether to go on with someone: a person you've added, or anyone while Add nearby person is open. Never yourself. */
    private fun welcome(remote: ByteArray, service: Found?): Boolean {
        val key = NoiseCrypto.hex(remote)
        if (key == identity.card.keyHex) { service?.self = true; return false }
        return contactRoute(app, key) != null || pairing()
    }

    private fun pairing() = System.currentTimeMillis() < pairingUntil

    /** Keeps one connection per person, the same one on both phones (NearbyWire.replaces). */
    private fun adopt(session: Session): Boolean {
        synchronized(sessions) {
            val existing = sessions[session.peer]
            if (existing != null && existing.alive && !NearbyWire.replaces(existing.starter, session.starter)) {
                session.close()
                return false
            }
            sessions[session.peer] = session
            existing?.close()
        }
        if (contactRoute(app, session.peer) != null) arrived(session.peer)
        return true
    }

    /** Someone you've added is connected: their waiting messages go now. */
    private fun arrived(peer: String) {
        app.getSharedPreferences("fluid", Context.MODE_PRIVATE).edit().putLong(SEEN + peer, System.currentTimeMillis()).apply()
        workers.execute { synchronized(flushLock) { DirectProtocols.flush(app) } }
    }

    private fun read(session: Session, input: InputStream) {
        try {
            while (running && session.alive) {
                val frame = NearbyWire.readFrame(input) ?: break
                when (val packet = NearbyWire.decode(session.transport.decrypt(frame))) {
                    is NearbyWire.Packet.Text -> received(session, packet)
                    is NearbyWire.Packet.Ack -> acks[packet.id]?.countDown()
                    NearbyWire.Packet.Ping, is NearbyWire.Packet.Hello -> {}
                }
            }
        } catch (error: Exception) {
            // Out of range, closed, or a message that didn't decrypt: the connection ends and is found again later.
        } finally {
            session.close()
            sessions.remove(session.peer, session)
        }
    }

    private fun received(session: Session, packet: NearbyWire.Packet.Text) {
        val route = contactRoute(app, session.peer)
        val id = NearbyWire.incomingId(session.peer, packet.id)
        val stored = route != null && ProtocolStore.get(app).find(id) != null
        val receipt = NearbyWire.receive(stored, route != null)
        if (receipt.store && route != null) {
            DirectProtocols.incoming(app, DirectProtocols.record(route, id, packet.text, false,
                NearbyWire.incomingDate(packet.date, System.currentTimeMillis()), ""))
        }
        if (receipt.ack) session.write(NearbyWire.Packet.Ack(packet.id))
    }

    private fun ping() {
        for (session in sessions.values) try { session.write(NearbyWire.Packet.Ping) } catch (error: Exception) { session.close() }
    }

    private class Session(val socket: Socket, private val output: OutputStream, val transport: NoiseTransport, val peer: String,
                          val name: String, val id: String, val starter: String, val code: String) {
        @Volatile var alive = true

        fun write(packet: NearbyWire.Packet) {
            synchronized(this) {
                if (!alive) throw IOException("Closed")
                output.write(NearbyWire.frame(transport.encrypt(NearbyWire.encode(packet))))
                output.flush()
            }
        }

        fun close() {
            alive = false
            try { socket.close() } catch (ignored: IOException) {}
        }
    }

    // For the Nearby pages

    fun nearbyNow(key: String) = sessions[key]?.alive == true

    /** "Add nearby person": connects to anyone on the page for [PAIRING_MS], showing each one's safety code. */
    fun startPairing() {
        val already = pairing()
        pairingUntil = System.currentTimeMillis() + PAIRING_MS
        if (!already) retryAll()
    }

    fun stopPairing() {
        pairingUntil = 0
        for (session in sessions.values) if (contactRoute(app, session.peer) == null) session.close()
    }

    fun candidates(): List<Candidate> = sessions.values.filter { it.alive && contactRoute(app, it.peer) == null }
        .map { Candidate(it.peer, it.name, it.id, it.code) }.sortedBy { it.name.lowercase() }

    /** A person was just added: their phone may be connected for pairing already, or found and waiting. */
    fun added(key: String) {
        if (sessions.containsKey(key)) arrived(key) else retryAll()
    }

    /** This phone's addresses on the local network, for Connect by address. */
    fun addresses(): List<String> {
        val port = server?.localPort ?: return emptyList()
        return try {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>().map { "${it.hostAddress}:$port" }
        } catch (error: Exception) { emptyList() }
    }

    fun searching(): Boolean = running && server != null

    /** A problem with this network, shown on the Nearby page; "" when all is well. */
    @Volatile var problem = ""
        private set

    private fun status(text: String) { problem = text }

    companion object {
        const val PORT = 47610
        private const val RETRY_MS = 15_000L
        const val PAIRING_MS = 5 * 60_000L
        private const val SEEN = "nearby.seen."
        private val flushLock = Any()

        @Volatile @JvmStatic var current: DirectNearby? = null

        fun identityOf(account: JSONObject?): Identity? {
            if (account == null) return null
            return try {
                val private = NoiseCrypto.hex(account.getString("private"))
                Identity(NoiseCrypto.KeyPair(private, NoiseCrypto.hex(account.getString("public"))),
                    account.getString("name"), account.getString("id"))
            } catch (error: Exception) { null }
        }

        @JvmStatic fun identity(context: Context): Identity? = identityOf(DirectProtocols.account(context, MessageProtocol.NEARBY))

        /** Sets your Nearby name, making your keys the first time. Saved sealed, like other accounts. */
        @JvmStatic fun setUp(context: Context, name: String, discoverable: Boolean) {
            val clean = NearbyWire.cleanName(name) ?: throw IllegalArgumentException("Enter a name, up to ${NearbyWire.MAX_NAME} letters")
            val existing = DirectProtocols.account(context, MessageProtocol.NEARBY)
            val account = if (identityOf(existing) != null) existing!! else {
                val keys = NoiseCrypto.generateKeyPair()
                JSONObject().put("private", NoiseCrypto.hex(keys.private)).put("public", NoiseCrypto.hex(keys.public))
                    .put("id", NearbyWire.newId())
            }
            account.put("name", clean)
            DirectProtocols.saveAccount(context, MessageProtocol.NEARBY, account, discoverable)
        }

        @JvmStatic fun setDiscoverable(context: Context, on: Boolean) {
            val account = DirectProtocols.account(context, MessageProtocol.NEARBY) ?: return
            DirectProtocols.saveAccount(context, MessageProtocol.NEARBY, account, on)
        }

        /** People you've added, as their links. */
        @JvmStatic fun contacts(context: Context): List<JSONObject> {
            val routes = DirectProtocols.routes(DirectProtocols.prefs(context))
            return (0 until routes.length()).mapNotNull { routes.optJSONObject(it) }
                .filter { MessageProtocol.parse(it.optString("protocol")) == MessageProtocol.NEARBY }
        }

        @JvmStatic fun contactRoute(context: Context, key: String): JSONObject? =
            contacts(context).firstOrNull { it.optString("target").equals(key, ignoreCase = true) }

        /** Adds someone from their code (scanned, typed, or after matching safety codes). Returns their chat's link. */
        @JvmStatic fun add(context: Context, card: NearbyWire.Card): JSONObject {
            val mine = identity(context) ?: throw IllegalStateException("Set your Nearby name first")
            if (card.keyHex == mine.card.keyHex) throw IllegalArgumentException("That’s your own code")
            contactRoute(context, card.keyHex)?.let { return it }
            val route = DirectProtocols.linkQuietly(context, MessageProtocol.NEARBY, card.keyHex, card.name, false,
                JSONObject().put("nearby_id", card.id))
            current?.added(card.keyHex)
            return route
        }

        @JvmStatic fun remove(context: Context, address: String) {
            DirectProtocols.unlink(context, address, MessageProtocol.NEARBY)
            MmsTransport.changed(context)
        }

        @JvmStatic fun lastSeen(context: Context, key: String): Long =
            context.getSharedPreferences("fluid", Context.MODE_PRIVATE).getLong(SEEN + key, 0)
    }
}
