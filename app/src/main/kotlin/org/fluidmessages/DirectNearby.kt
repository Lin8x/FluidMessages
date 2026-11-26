package org.fluidmessages

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Nearby: messages between FluidMessages phones close by, with no internet. Phones find each other on the same Wi-Fi
 * with Android's network service discovery (mDNS, [NearbyWire.SERVICE_TYPE]) and talk over TCP, or over Bluetooth
 * Classic (RFCOMM, [BLUETOOTH_UUID]) when it's allowed. Wi-Fi Direct is a later step.
 *
 * Every connection starts with a Noise_XX_25519_ChaChaPoly_SHA256 handshake (Noise.kt) with each phone's static key,
 * so both sides prove who they are and each connection has its own keys. Two kinds of connection:
 * - **With someone you've added**: one-to-one messages ([NearbyWire.Packet.Text], confirmed with an Ack), plus the
 *   community envelopes below.
 * - **With anyone else nearby**, only while you've joined a channel or Help pass messages along is on: envelopes only
 *   (NearbyMesh): channel and room messages, and one-to-one messages sealed to someone else that this phone carries
 *   without being able to read. A one-to-one message is never taken from someone you haven't added.
 * While Add nearby person is open on both phones, they also connect and show a safety code (NearbyWire.safetyCode).
 *
 * A one-to-one message waits in the shared outbox (DirectProtocols) until the person's phone is connected, and is
 * sent oldest first. While it waits, a sealed copy also travels from phone to phone ([NearbyRelay]: 8 hops, 7 days,
 * each phone keeps it once), so it can reach them through others. Their phone stores each message once, however it
 * arrives. Only a message their phone confirmed itself reads "Sent nearby".
 *
 * The identity (X25519 key, signing key, name, id) is the protocol's account, sealed with the Android Keystore like
 * other accounts and never in backups. People and channels you add are its links (directRoutes); room keys are
 * sealed in the app's settings (Privacy.seal).
 */
internal class DirectNearby(private val signature: String) : DirectProtocols.DirectClient {
    override fun toString() = signature
    override fun persistent() = true

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var identity: Identity
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var bluetoothServer: BluetoothServerSocket? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var bluetoothFound: BroadcastReceiver? = null
    @Volatile private var ownServiceName = ""
    private val workers = Executors.newCachedThreadPool { Thread(it, "fluid-nearby").apply { isDaemon = true } }
    private val resolver = Executors.newSingleThreadExecutor { Thread(it, "fluid-nearby-resolve").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "fluid-nearby-timer").apply { isDaemon = true } }
    private val handshakes = Semaphore(8)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val found = ConcurrentHashMap<String, Found>()
    private val radios = ConcurrentHashMap<String, Radio>()
    private val acks = ConcurrentHashMap<String, CountDownLatch>()
    @Volatile private var pairingUntil = 0L
    private val store by lazy { NearbyRelayStore.get(app) }

    class Identity(val keys: NoiseCrypto.KeyPair, val name: String, val id: String, val sign: NearbySign.Keys?) {
        val card get() = NearbyWire.Card(keys.public, name, id)
    }

    private class Found(val host: InetAddress, val port: Int) {
        @Volatile var lastTry = 0L
        @Volatile var peer = ""
        @Volatile var self = false
    }

    /** A Bluetooth device to try: who it turned out to be, and when to try again. */
    private class Radio(val device: BluetoothDevice) {
        @Volatile var peer = ""
        @Volatile var nextTry = 0L
        @Volatile var failures = 0
    }

    /** A connection: TCP on Wi-Fi or RFCOMM on Bluetooth, as two streams. */
    private class Link(val input: InputStream, val output: OutputStream, val kind: String, private val closer: () -> Unit,
                       val setTimeout: (Int) -> Unit = {}) {
        fun close() { try { closer() } catch (ignored: Exception) {} }
    }

    /** Someone on the Add nearby person page who isn't added yet, with the safety code both phones show. */
    data class Candidate(val key: String, val name: String, val id: String, val code: String)

    override fun start(context: Context, account: JSONObject) {
        app = context.applicationContext
        prefs = app.getSharedPreferences("fluid", Context.MODE_PRIVATE)
        current = this
        // The encryption has to be there before anything listens: otherwise Nearby says so and stays off.
        val missing = NoiseCrypto.selfCheck()
        if (missing.isNotEmpty()) { problem = missing; return }
        identity = identityOf(account) ?: return
        running = true
        workers.execute {
            try {
                val socket = try { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) } }
                    catch (busy: IOException) { ServerSocket(0) }
                server = socket
                register(socket.localPort)
                discover()
                while (running) {
                    val client = try { socket.accept() } catch (closed: IOException) { break }
                    if (!handshakes.tryAcquire()) { client.close(); continue }
                    workers.execute { try { open(tcp(client), initiator = false) } finally { handshakes.release() } }
                }
            } catch (error: Exception) {
                status("Nearby couldn’t start on this network")
            }
        }
        startBluetooth()
        timer.scheduleWithFixedDelay({ tick() }, 3, 15, TimeUnit.SECONDS)
        timer.scheduleWithFixedDelay({ ping() }, 25, 25, TimeUnit.SECONDS)
        timer.scheduleWithFixedDelay({ try { store.prune(System.currentTimeMillis()) } catch (ignored: Exception) {} }, 10, 3600, TimeUnit.SECONDS)
    }

    override fun stop() {
        running = false
        if (current === this) current = null
        try { registration?.let { nsd?.unregisterService(it) } } catch (ignored: Exception) {}
        try { discovery?.let { nsd?.stopServiceDiscovery(it) } } catch (ignored: Exception) {}
        try { bluetoothFound?.let(app::unregisterReceiver) } catch (ignored: Exception) {}
        try { server?.close() } catch (ignored: IOException) {}
        try { bluetoothServer?.close() } catch (ignored: IOException) {}
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
        if (!running) throw IOException("Nearby isn’t running")
        val body = message.optString("body")
        if (route.optBoolean("group")) return shareInChannel(route, message, body)
        if (body.toByteArray(Charsets.UTF_8).size > NearbyWire.MAX_TEXT_BYTES) throw IllegalArgumentException("That message is too long to send nearby")
        val peer = route.optString("target")
        val wireId = NearbyWire.wireId(message.getString("id"))
        val text = NearbyWire.Packet.Text(wireId, message.optLong("date"), body)
        val session = sessions[peer]?.takeIf { it.contact }
        if (session == null) {
            // Not in range: a sealed copy goes with phones nearby, and the message keeps waiting for them too.
            passAlongSealed(message.getString("id"), peer, text)
            throw IOException("Not nearby")
        }
        val latch = CountDownLatch(1)
        acks[wireId] = latch
        try {
            session.write(text)
            // Sent only once the other phone says it has it; otherwise it waits and goes again next time.
            if (!latch.await(15, TimeUnit.SECONDS) || !running) throw IOException("No answer from their phone")
        } finally { acks.remove(wireId) }
        return JSONObject()
    }

    private fun passAlongSealed(localId: String, peer: String, text: NearbyWire.Packet.Text) {
        if (body(text).length > NearbyMesh.MAX_WORDS) return
        try {
            if (store.byOrigin(localId) != null) return
            val envelope = NearbyMesh.directMessage(NearbyMesh.newId(), System.currentTimeMillis(), identity.keys, NoiseCrypto.hex(peer), text)
            store.put(envelope, envelope.hops, own = true, origin = localId)
            spread(envelope, null)
        } catch (error: Exception) {}
    }

    private fun body(text: NearbyWire.Packet.Text) = text.text

    /** A channel or room message: kept here, handed to every phone connected now, and to each one met later. */
    private fun shareInChannel(route: JSONObject, message: JSONObject, words: String): JSONObject {
        val sign = identity.sign ?: throw IllegalStateException("Open the Nearby page once, then try again")
        val tag = route.optString("target").removePrefix(CHANNEL_PREFIX)
        val id = NearbyMesh.newId()
        val now = System.currentTimeMillis()
        val envelope = if (route.optBoolean("room")) {
            val key = roomKey(app, tag) ?: throw IllegalStateException("Join this room again with its passphrase")
            NearbyMesh.roomMessage(id, now, key, sign, identity.name, words)
        } else NearbyMesh.channelMessage(id, now, route.optString("channel_name"), sign, identity.name, words)
        store.put(envelope, envelope.hops, own = true, origin = message.getString("id"))
        val reached = spread(envelope, null)
        return JSONObject().put("status", NearbyText.shared(reached)).put("nearby_envelope", id)
    }

    /** Hands [envelope] to every connected phone but [except]; returns how many. */
    private fun spread(envelope: Envelope, except: Session?): Int {
        val next = NearbyRelay.next(envelope) ?: return 0
        val packet = NearbyWire.Packet.Carry(NearbyMesh.encode(next))
        var count = 0
        for (session in sessions.values) {
            if (session === except || !session.alive || !session.sent.add(envelope.id)) continue
            try { session.write(packet); count++ } catch (error: Exception) { session.close() }
        }
        return count
    }

    // Finding phones on Wi-Fi

    private fun tcp(socket: Socket): Link {
        socket.tcpNoDelay = true
        return Link(BufferedInputStream(socket.getInputStream()), socket.getOutputStream(), "Wi-Fi", { socket.close() },
            { socket.soTimeout = it })
    }

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
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { status("Nearby couldn’t announce this phone on this Wi-Fi") }
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
            override fun onStartDiscoveryFailed(type: String, code: Int) { status("Nearby can’t look for phones on this Wi-Fi") }
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
                    workers.execute { connect(entry) }
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
        for (entry in found.values) {
            if (entry.self || entry.peer.isNotEmpty() && sessions.containsKey(entry.peer)) continue
            if (now - entry.lastTry < RETRY_MS) continue
            workers.execute { connect(entry) }
        }
        tickBluetooth(now)
        if (pairingUntil in 1 until now) stopPairing()
    }

    private fun retryAll() {
        for (entry in found.values) entry.lastTry = 0
        for (radio in radios.values) radio.nextTry = 0
        workers.execute { tick() }
    }

    private fun connect(entry: Found) {
        if (!running || entry.self || entry.peer.isNotEmpty() && sessions.containsKey(entry.peer)) return
        entry.lastTry = System.currentTimeMillis()
        connectTo(InetSocketAddress(entry.host, entry.port), entry)
    }

    /** "Connect by address", for networks that block finding phones, or two emulators through adb forwarding. */
    fun connectToAddress(host: String, port: Int) {
        workers.execute { connectTo(InetSocketAddress(host, port), null) }
    }

    private fun connectTo(address: InetSocketAddress, entry: Found?) {
        if (!handshakes.tryAcquire()) return
        try {
            val socket = Socket()
            try { socket.connect(address, 5000) } catch (error: IOException) { socket.close(); return }
            val peer = open(tcp(socket), initiator = true)
            if (entry != null) { if (peer == SELF) entry.self = true else if (peer != null) entry.peer = peer }
        } finally { handshakes.release() }
    }

    // Bluetooth

    private fun adapter(): BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter

    private fun bluetoothWanted() = bluetoothOn(prefs) && bluetoothAllowed(app) && adapter()?.isEnabled == true

    @SuppressLint("MissingPermission")
    private fun startBluetooth() {
        if (!bluetoothWanted()) return
        val adapter = adapter() ?: return
        workers.execute {
            try {
                // Insecure RFCOMM: no Android pairing prompt. Noise does the proving and the locking.
                val listening = adapter.listenUsingInsecureRfcommWithServiceRecord("FluidMessages Nearby", BLUETOOTH_UUID)
                bluetoothServer = listening
                while (running) {
                    val socket = try { listening.accept() } catch (closed: IOException) { break }
                    if (!handshakes.tryAcquire()) { socket.close(); continue }
                    workers.execute {
                        try {
                            val peer = open(rfcomm(socket), initiator = false)
                            if (peer != null && peer != SELF) remember(socket.remoteDevice, peer)
                        } finally { handshakes.release() }
                    }
                }
            } catch (error: Exception) { status("Nearby couldn’t use Bluetooth") }
        }
        for (device in try { adapter.bondedDevices } catch (error: SecurityException) { emptySet() }) radios.putIfAbsent(device.address, Radio(device))
        for (address in rememberedRadios()) runCatching { adapter.getRemoteDevice(address) }.getOrNull()?.let { radios.putIfAbsent(address, Radio(it)) }
        if (app.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                    radios.putIfAbsent(device.address, Radio(device))
                }
            }
            bluetoothFound = receiver
            app.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND), Context.RECEIVER_EXPORTED)
        }
    }

    /** Use Bluetooth too, or its permission, changed: starts or stops listening without dropping Wi-Fi connections. */
    fun bluetoothChanged() {
        if (!running) return
        if (bluetoothWanted()) { if (bluetoothServer == null) startBluetooth() }
        else {
            try { bluetoothServer?.close() } catch (ignored: IOException) {}
            bluetoothServer = null
            try { bluetoothFound?.let(app::unregisterReceiver) } catch (ignored: Exception) {}
            bluetoothFound = null
            for (session in sessions.values) if (session.link.kind == "Bluetooth") session.close()
        }
    }

    private fun rfcomm(socket: BluetoothSocket) = Link(socket.inputStream, socket.outputStream, "Bluetooth", { socket.close() })

    @Volatile private var lastScan = 0L

    @SuppressLint("MissingPermission")
    private fun tickBluetooth(now: Long) {
        if (!bluetoothWanted()) return
        val adapter = adapter() ?: return
        // A scan every few minutes finds phones you haven't met over Bluetooth yet; it pauses while connecting.
        if (bluetoothFound != null && now - lastScan > SCAN_MS) {
            lastScan = now
            try { adapter.startDiscovery() } catch (error: SecurityException) {}
        }
        for (radio in radios.values) {
            if (radio.peer.isNotEmpty() && sessions.containsKey(radio.peer)) continue
            if (now < radio.nextTry) continue
            radio.nextTry = now + RETRY_MS
            workers.execute { connectRadio(adapter, radio) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectRadio(adapter: BluetoothAdapter, radio: Radio) {
        if (!running || !handshakes.tryAcquire()) return
        try {
            val socket = radio.device.createInsecureRfcommSocketToServiceRecord(BLUETOOTH_UUID)
            try {
                try { adapter.cancelDiscovery() } catch (ignored: SecurityException) {}
                socket.connect()
            } catch (error: IOException) {
                socket.close()
                // Headphones and other devices without FluidMessages: wait longer each time, up to an hour.
                radio.failures++
                radio.nextTry = System.currentTimeMillis() + minOf(3_600_000L, 60_000L shl minOf(radio.failures, 6))
                return
            }
            radio.failures = 0
            val peer = open(rfcomm(socket), initiator = true)
            if (peer != null && peer != SELF) remember(radio.device, peer)
        } catch (error: Exception) {
        } finally { handshakes.release() }
    }

    /** Bluetooth addresses of phones met before, so they're tried first next time. Kept on this phone only. */
    private fun remember(device: BluetoothDevice, peer: String) {
        radios.getOrPut(device.address) { Radio(device) }.peer = peer
        val known = rememberedRadios().toMutableList()
        if (device.address in known) return
        known.add(0, device.address)
        prefs.edit().putString(RADIOS, JSONArray(known.take(50)).toString()).apply()
    }

    private fun rememberedRadios(): List<String> = try {
        val array = JSONArray(prefs.getString(RADIOS, "[]"))
        (0 until array.length()).map(array::getString)
    } catch (error: Exception) { emptyList() }

    // Connections

    /**
     * Runs the handshake on a new connection, then reads from it until it closes. Returns the other phone's key once
     * it's known (or [SELF]); null when the handshake didn't finish.
     */
    private fun open(link: Link, initiator: Boolean): String? {
        // Bluetooth streams have no read timeout, so a stalled handshake is closed by the timer.
        var adopted = false
        val guard = timer.schedule({ if (!adopted) link.close() }, 12, TimeUnit.SECONDS)
        var peer: String? = null
        try {
            link.setTimeout(10_000)
            val handshake = NoiseHandshake(NoisePattern.XX, initiator, NearbyWire.PROLOGUE, identity.keys)
            val hello = NearbyWire.encode(NearbyWire.Packet.Hello(identity.name, identity.id,
                identity.sign?.public?.let(NoiseCrypto::hex).orEmpty(), pairing()))
            val theirs: NearbyWire.Packet
            if (initiator) {
                link.output.write(NearbyWire.frame(handshake.writeMessage(ByteArray(0))))
                link.output.flush()
                theirs = NearbyWire.decode(handshake.readMessage(readFrame(link.input)))
                peer = NoiseCrypto.hex(handshake.remoteStatic!!)
                // Decide before saying who this phone is, so a phone you haven't let in never learns your key.
                if (!welcome(peer)) { link.close(); return if (peer == identity.card.keyHex) SELF else peer }
                link.output.write(NearbyWire.frame(handshake.writeMessage(hello)))
                link.output.flush()
            } else {
                handshake.readMessage(readFrame(link.input))
                link.output.write(NearbyWire.frame(handshake.writeMessage(hello)))
                link.output.flush()
                theirs = NearbyWire.decode(handshake.readMessage(readFrame(link.input)))
                peer = NoiseCrypto.hex(handshake.remoteStatic!!)
                if (!welcome(peer)) { link.close(); return if (peer == identity.card.keyHex) SELF else peer }
            }
            val greeting = theirs as? NearbyWire.Packet.Hello ?: throw IOException("Expected a hello")
            val session = Session(link, handshake.transport(), peer, greeting.name, greeting.id,
                starter = if (initiator) identity.card.keyHex else peer, code = NearbyWire.safetyCode(handshake.handshakeHash),
                signKey = greeting.signKey, known = { contactRoute(app, peer) != null })
            session.pairing = greeting.pairing
            link.setTimeout(70_000)
            adopted = true
            guard.cancel(false)
            if (!adopt(session)) return peer
            read(session)
            return peer
        } catch (error: Exception) {
            // A failed or refused handshake just closes; nothing about it is kept.
            link.close()
            return peer
        }
    }

    private fun readFrame(input: InputStream): ByteArray = NearbyWire.readFrame(input) ?: throw IOException("Closed")

    /**
     * Whether to go on with someone: a person you've added; anyone while Add nearby person is open; and anyone, for
     * channels and passing messages along only, while those are on (a few at a time). Never yourself.
     */
    private fun welcome(key: String): Boolean {
        if (key == identity.card.keyHex) return false
        if (contactRoute(app, key) != null || pairing()) return true
        return community(app) && sessions.values.count { !it.contact } < MAX_COMMUNITY
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
        if (session.contact) arrived(session)
        // Each side says what it has, and gets what it's missing.
        workers.execute {
            try { session.write(NearbyWire.Packet.Have(store.items().take(NearbyRelay.INVENTORY).map { it.id })) }
            catch (error: Exception) { session.close() }
        }
        return true
    }

    /** Someone you've added is connected: their waiting messages go now. */
    private fun arrived(session: Session) {
        learnSigningKey(app, session.peer, session.signKey)
        prefs.edit().putLong(SEEN + session.peer, System.currentTimeMillis()).apply()
        workers.execute { synchronized(flushLock) { DirectProtocols.flush(app) } }
    }

    private fun read(session: Session) {
        try {
            while (running && session.alive) {
                val frame = NearbyWire.readFrame(session.link.input) ?: break
                when (val packet = NearbyWire.decode(session.transport.decrypt(frame))) {
                    is NearbyWire.Packet.Text -> received(session, packet)
                    is NearbyWire.Packet.Ack -> acks[packet.id]?.countDown()
                    is NearbyWire.Packet.Mode -> session.pairing = packet.pairing
                    is NearbyWire.Packet.Have -> handOver(session, packet.ids.toSet())
                    is NearbyWire.Packet.Carry -> carried(session, packet.envelope)
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
        // One-to-one messages come only from people you've added, never over a connection kept for channels.
        val route = contactRoute(app, session.peer)
        if (store(session.peer, route, packet)) session.write(NearbyWire.Packet.Ack(packet.id))
    }

    /** Stores a one-to-one message once; true when it should be confirmed. */
    private fun store(peer: String, route: JSONObject?, packet: NearbyWire.Packet.Text): Boolean {
        val id = NearbyWire.incomingId(peer, packet.id)
        val stored = route != null && ProtocolStore.get(app).find(id) != null
        val receipt = NearbyWire.receive(stored, route != null)
        if (receipt.store && route != null) {
            DirectProtocols.incoming(app, DirectProtocols.record(route, id, packet.text, false,
                NearbyWire.incomingDate(packet.date, System.currentTimeMillis()), ""))
        }
        return receipt.ack
    }

    private fun handOver(session: Session, theirs: Set<String>) {
        session.sent.addAll(theirs)
        val hops = store.hops()
        for (id in NearbyRelay.toSend(store.items(), theirs, hops)) {
            if (!session.alive || !running) return
            val envelope = store.get(id)?.let(NearbyRelay::next) ?: continue
            if (!session.sent.add(id)) continue
            session.write(NearbyWire.Packet.Carry(NearbyMesh.encode(envelope)))
        }
    }

    private fun carried(session: Session, bytes: ByteArray) {
        val envelope = try { NearbyMesh.decode(bytes) } catch (bad: IOException) { return }
        session.sent.add(envelope.id)
        val now = System.currentTimeMillis()
        val mine = envelope.kind == Envelope.DIRECT && envelope.tag == NearbySeal.toTag(identity.keys.public)
        val intake = NearbyRelay.intake(envelope, now, store.has(envelope.id), helping(prefs), mine, session.received++)
        if (!intake.keep) return
        when (envelope.kind) {
            Envelope.DIRECT -> if (mine) {
                NearbyMesh.openDirect(envelope, identity.keys)?.let { direct -> store(direct.sender, contactRoute(app, direct.sender), direct.text) }
            }
            Envelope.CHANNEL -> {
                // A message that isn't signed by its sender is dropped, never shown or passed on.
                val words = NearbyMesh.channelWords(envelope) ?: return
                showInChannel(envelope, envelope.name, words, envelope.sender)
            }
            Envelope.ROOM -> roomKey(app, envelope.tag)?.let { key ->
                NearbyMesh.openRoom(envelope, key)?.let { showInChannel(envelope, it.name, it.words, it.sender) }
            }
        }
        store.put(envelope, if (intake.passOn) envelope.hops else 0, own = false)
        if (intake.passOn) spread(envelope, session)
    }

    private fun showInChannel(envelope: Envelope, name: String, words: String, sender: ByteArray) {
        val route = channelRoute(app, envelope.tag) ?: return
        // Your own words coming back from another phone are already in the chat.
        if (identity.sign?.public?.contentEquals(sender) == true) return
        val author = contactBySigningKey(app, NoiseCrypto.hex(sender))?.optString("title") ?: (name + " · " + NearbySign.mark(sender))
        DirectProtocols.incoming(app, DirectProtocols.record(route, "nearby-ch-" + envelope.id, words, false,
            NearbyWire.incomingDate(envelope.created, System.currentTimeMillis()), author))
    }

    private fun ping() {
        for (session in sessions.values) try { session.write(NearbyWire.Packet.Ping) } catch (error: Exception) { session.close() }
    }

    private class Session(val link: Link, val transport: NoiseTransport, val peer: String, val name: String, val id: String,
                          val starter: String, val code: String, val signKey: String, private val known: () -> Boolean) {
        /** Someone you've added, checked each time: adding them on Add nearby person keeps this same connection. */
        val contact: Boolean get() = known()
        @Volatile var alive = true
        @Volatile var pairing = false
        /** Envelopes this phone already has or was given, so none is sent to it twice. */
        val sent: MutableSet<String> = ConcurrentHashMap.newKeySet()
        @Volatile var received = 0

        fun write(packet: NearbyWire.Packet) {
            synchronized(this) {
                if (!alive) throw IOException("Closed")
                link.output.write(NearbyWire.frame(transport.encrypt(NearbyWire.encode(packet))))
                link.output.flush()
            }
        }

        fun close() {
            alive = false
            link.close()
        }
    }

    // For the Nearby pages

    fun nearbyNow(key: String) = sessions[key]?.alive == true

    /** How many phones are connected now, and how many of them are people you've added. */
    fun connected(): Pair<Int, Int> = sessions.values.count { it.alive } to sessions.values.count { it.alive && it.contact }

    /** "Add nearby person": connects to anyone on the page for [PAIRING_MS], showing each one's safety code. */
    fun startPairing() {
        val already = pairing()
        pairingUntil = System.currentTimeMillis() + PAIRING_MS
        if (!already) {
            for (session in sessions.values) try { session.write(NearbyWire.Packet.Mode(true)) } catch (ignored: Exception) {}
            retryAll()
        }
    }

    fun stopPairing() {
        pairingUntil = 0
        for (session in sessions.values) {
            if (contactRoute(app, session.peer) == null && !community(app)) session.close()
            else try { session.write(NearbyWire.Packet.Mode(false)) } catch (ignored: Exception) {}
        }
    }

    /** People on their own Add nearby person page too, not added yet. Phones only here for channels aren't listed. */
    fun candidates(): List<Candidate> = sessions.values.filter { it.alive && it.pairing && contactRoute(app, it.peer) == null }
        .map { Candidate(it.peer, it.name, it.id, it.code) }.sortedBy { it.name.lowercase() }

    /** A person was just added: their phone may be connected for pairing already, or found and waiting. */
    fun added(key: String) {
        // Already connected (Add nearby person): the same connection now carries messages, and the safety code stays.
        val session = sessions[key]
        if (session != null && session.alive) arrived(session) else retryAll()
    }

    /** Settings for channels or passing messages along changed: open or drop connections with phones you haven't added. */
    fun communityChanged() {
        if (!community(app) && !pairing()) for (session in sessions.values) if (!session.contact) session.close()
        retryAll()
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

    fun usingBluetooth(): Boolean = running && bluetoothServer != null

    /** A problem with this network or phone, shown on the Nearby page; "" when all is well. */
    @Volatile var problem = ""
        private set

    private fun status(text: String) { problem = text }

    companion object {
        const val PORT = 47610
        /** Nearby's fixed Bluetooth service, the same on every phone. */
        @JvmField val BLUETOOTH_UUID: UUID = UUID.fromString("6f1c2a5e-7b0d-4e8a-9c3f-2d61b8a4e917")
        private const val RETRY_MS = 15_000L
        private const val SCAN_MS = 4 * 60_000L
        const val PAIRING_MS = 5 * 60_000L
        private const val MAX_COMMUNITY = 16
        private const val SEEN = "nearby.seen."
        private const val RADIOS = "nearby.radios"
        const val HELP = "nearby.help"
        const val BLUETOOTH = "nearby.bluetooth"
        private const val ROOMS = "nearby.rooms"
        const val CHANNEL_PREFIX = "channel:"
        private val flushLock = Any()

        @Volatile @JvmStatic var current: DirectNearby? = null

        /** "Help pass messages along": on unless turned off, whenever Nearby is on. */
        @JvmStatic fun helping(prefs: SharedPreferences) = prefs.getBoolean(HELP, true)

        @JvmStatic fun bluetoothOn(prefs: SharedPreferences) = prefs.getBoolean(BLUETOOTH, true)

        val BLUETOOTH_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE)

        @JvmStatic fun bluetoothAllowed(context: Context) =
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        /** Connections with phones you haven't added, for channels and passing messages along. */
        @JvmStatic fun community(context: Context): Boolean =
            helping(context.getSharedPreferences("fluid", Context.MODE_PRIVATE)) || channels(context).isNotEmpty()

        fun identityOf(account: JSONObject?): Identity? {
            if (account == null) return null
            return try {
                val private = NoiseCrypto.hex(account.getString("private"))
                val sign = if (account.has("sign_private")) NearbySign.Keys(NoiseCrypto.hex(account.getString("sign_private")),
                    NoiseCrypto.hex(account.getString("sign_public"))) else null
                Identity(NoiseCrypto.KeyPair(private, NoiseCrypto.hex(account.getString("public"))),
                    account.getString("name"), account.getString("id"), sign)
            } catch (error: Exception) { null }
        }

        @JvmStatic fun identity(context: Context): Identity? = identityOf(DirectProtocols.account(context, MessageProtocol.NEARBY))

        /** Sets your Nearby name, making your keys the first time. Saved sealed, like other accounts. */
        @JvmStatic fun setUp(context: Context, name: String, discoverable: Boolean) {
            val missing = NoiseCrypto.selfCheck()
            if (missing.isNotEmpty()) throw IllegalStateException(missing)
            val clean = NearbyWire.cleanName(name) ?: throw IllegalArgumentException("Enter a name, up to ${NearbyWire.MAX_NAME} letters")
            val existing = DirectProtocols.account(context, MessageProtocol.NEARBY)
            val account = if (identityOf(existing) != null) existing!! else {
                val keys = NoiseCrypto.generateKeyPair()
                JSONObject().put("private", NoiseCrypto.hex(keys.private)).put("public", NoiseCrypto.hex(keys.public))
                    .put("id", NearbyWire.newId())
            }
            withSigningKey(account)
            account.put("name", clean)
            DirectProtocols.saveAccount(context, MessageProtocol.NEARBY, account, discoverable)
        }

        /** Identities from before channels get their signing key the next time they're saved or Nearby opens. */
        private fun withSigningKey(account: JSONObject) {
            if (account.has("sign_private")) return
            val sign = NearbySign.generate()
            account.put("sign_private", NoiseCrypto.hex(sign.private)).put("sign_public", NoiseCrypto.hex(sign.public))
        }

        @JvmStatic fun ensureSigningKey(context: Context) {
            val account = DirectProtocols.account(context, MessageProtocol.NEARBY) ?: return
            if (account.has("sign_private")) return
            withSigningKey(account)
            DirectProtocols.saveAccount(context, MessageProtocol.NEARBY, account, DirectProtocols.stayConnected(
                DirectProtocols.prefs(context), MessageProtocol.NEARBY))
        }

        @JvmStatic fun setDiscoverable(context: Context, on: Boolean) {
            val account = DirectProtocols.account(context, MessageProtocol.NEARBY) ?: return
            DirectProtocols.saveAccount(context, MessageProtocol.NEARBY, account, on)
        }

        private fun nearbyRoutes(context: Context): List<JSONObject> {
            val routes = DirectProtocols.routes(DirectProtocols.prefs(context))
            return (0 until routes.length()).mapNotNull { routes.optJSONObject(it) }
                .filter { MessageProtocol.parse(it.optString("protocol")) == MessageProtocol.NEARBY }
        }

        /** People you've added, as their links. */
        @JvmStatic fun contacts(context: Context): List<JSONObject> = nearbyRoutes(context).filter { !it.optBoolean("group") }

        /** Channels and rooms you've joined, as their links. */
        @JvmStatic fun channels(context: Context): List<JSONObject> = nearbyRoutes(context).filter { it.optBoolean("group") }

        @JvmStatic fun contactRoute(context: Context, key: String): JSONObject? =
            contacts(context).firstOrNull { it.optString("target").equals(key, ignoreCase = true) }

        @JvmStatic fun channelRoute(context: Context, tag: String): JSONObject? =
            channels(context).firstOrNull { it.optString("target") == CHANNEL_PREFIX + tag }

        private fun contactBySigningKey(context: Context, key: String): JSONObject? =
            contacts(context).firstOrNull { it.optString("sign_key") == key }

        /** Notes the signing key a person you've added uses, so their channel messages show their name. */
        private fun learnSigningKey(context: Context, peer: String, key: String) {
            if (key.isEmpty()) return
            synchronized(DirectProtocols::class.java) {
                val prefs = DirectProtocols.prefs(context)
                val routes = DirectProtocols.routes(prefs)
                var changed = false
                for (i in 0 until routes.length()) {
                    val route = routes.optJSONObject(i) ?: continue
                    if (MessageProtocol.parse(route.optString("protocol")) == MessageProtocol.NEARBY && !route.optBoolean("group") &&
                        route.optString("target").equals(peer, true) && route.optString("sign_key") != key) {
                        route.put("sign_key", key); changed = true
                    }
                }
                if (changed) prefs.edit().putString(DirectProtocols.ROUTES, routes.toString()).apply()
            }
        }

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

        /**
         * Joins an open channel, or a room when [passphrase] isn't blank. Returns its chat's link. A room's key is
         * worked out from the passphrase (slow on purpose) and sealed on this phone; the passphrase isn't kept.
         */
        @JvmStatic fun join(context: Context, name: String, passphrase: String): JSONObject {
            val clean = NearbyMesh.cleanChannel(name) ?: throw IllegalArgumentException("Enter a channel name, up to ${NearbyWire.MAX_NAME} letters")
            identity(context) ?: throw IllegalStateException("Set your Nearby name first")
            ensureSigningKey(context)
            val room = passphrase.isNotBlank()
            val tag = if (room) {
                val key = NearbyRoom.key(clean, passphrase)
                NearbyRoom.tag(key).also { saveRoomKey(context, it, key) }
            } else NearbyMesh.channelTag(clean)
            channelRoute(context, tag)?.let { return it }
            val route = DirectProtocols.linkQuietly(context, MessageProtocol.NEARBY, CHANNEL_PREFIX + tag, (if (room) "🔒 " else "#") + clean,
                true, JSONObject().put("channel_name", clean).put("room", room))
            current?.let { node -> node.communityChanged(); node.workers.execute { node.showStored(tag) } }
            return route
        }

        @JvmStatic fun remove(context: Context, address: String) {
            val route = DirectProtocols.route(DirectProtocols.prefs(context), address, MessageProtocol.NEARBY)
            DirectProtocols.unlink(context, address, MessageProtocol.NEARBY)
            if (route?.optBoolean("room") == true) forgetRoomKey(context, route.optString("target").removePrefix(CHANNEL_PREFIX))
            current?.communityChanged()
            MmsTransport.changed(context)
        }

        @JvmStatic fun lastSeen(context: Context, key: String): Long =
            context.getSharedPreferences("fluid", Context.MODE_PRIVATE).getLong(SEEN + key, 0)

        /** The lock a Nearby chat shows: open channels aren't secret; rooms and people are end-to-end encrypted. */
        @JvmStatic fun encryption(prefs: SharedPreferences, address: String): Encryption {
            val route = DirectProtocols.route(prefs, address, MessageProtocol.NEARBY) ?: return Encryption.END_TO_END
            return if (route.optBoolean("group") && !route.optBoolean("room")) Encryption.NONE else Encryption.END_TO_END
        }

        /** The note above the composer: what others can see in this chat. "" for one-to-one chats. */
        @JvmStatic fun notice(prefs: SharedPreferences, address: String): String {
            val route = DirectProtocols.route(prefs, address, MessageProtocol.NEARBY) ?: return ""
            return when {
                !route.optBoolean("group") -> ""
                route.optBoolean("room") -> NearbyText.ROOM_NOTICE
                else -> NearbyText.OPEN_NOTICE
            }
        }

        private fun rooms(context: Context): JSONObject {
            val prefs = DirectProtocols.prefs(context)
            val sealed = prefs.getString(ROOMS, "").orEmpty()
            if (sealed.isEmpty()) return JSONObject()
            return try { JSONObject(Privacy.open(prefs, sealed)) } catch (error: Exception) { JSONObject() }
        }

        private fun roomKey(context: Context, tag: String): ByteArray? = rooms(context).optString(tag).takeIf { it.isNotEmpty() }?.let(NoiseCrypto::hex)

        private fun saveRoomKey(context: Context, tag: String, key: ByteArray) = synchronized(ROOMS) {
            val prefs = DirectProtocols.prefs(context)
            prefs.edit().putString(ROOMS, Privacy.seal(prefs, rooms(context).put(tag, NoiseCrypto.hex(key)).toString())).apply()
        }

        private fun forgetRoomKey(context: Context, tag: String) = synchronized(ROOMS) {
            val prefs = DirectProtocols.prefs(context)
            val all = rooms(context).apply { remove(tag) }
            prefs.edit().putString(ROOMS, Privacy.seal(prefs, all.toString())).apply()
        }

        /** Open channels seen nearby lately, for Join a channel: name and message count. */
        @JvmStatic fun channelsSeen(context: Context): List<Pair<String, Int>> = try {
            NearbyRelayStore.get(context).channels()
        } catch (error: Exception) { emptyList() }

        @JvmStatic fun carriedBytes(context: Context): Long = try { NearbyRelayStore.get(context).bytes() } catch (error: Exception) { 0 }
    }

    /** A channel just joined: messages this phone was already carrying for it show up now. */
    private fun showStored(tag: String) {
        try {
            val now = System.currentTimeMillis()
            for (item in store.items()) {
                val envelope = store.get(item.id) ?: continue
                if (envelope.tag != tag || !NearbyRelay.fresh(envelope.copy(hops = 0), now)) continue
                when (envelope.kind) {
                    Envelope.CHANNEL -> NearbyMesh.channelWords(envelope)?.let { showInChannel(envelope, envelope.name, it, envelope.sender) }
                    Envelope.ROOM -> roomKey(app, tag)?.let { key -> NearbyMesh.openRoom(envelope, key)?.let { showInChannel(envelope, it.name, it.words, it.sender) } }
                }
            }
        } catch (error: Exception) {}
    }
}

private const val SELF = "self"
