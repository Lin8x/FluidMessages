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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
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
 * - **With anyone else nearby**, while Who can see me nearby is Everyone nearby, or you've joined a channel, or Help
 *   pass messages along is on: envelopes (NearbyMesh): channel and room messages, and one-to-one messages sealed to
 *   someone else that this phone carries without being able to read. Phones that said they can be seen are listed
 *   under People nearby; tapping one starts a chat pinned to the key it proved (trust on first use, NearbyPeople).
 *   A one-to-one message from someone you haven't added lands as a message request, only while you're seen by
 *   everyone nearby; otherwise it's never taken.
 * While Add nearby person is open on both phones, they also connect and show a safety code (NearbyWire.safetyCode).
 * A phone showing the ID of someone you've added with another key puts their chat on hold until you check it.
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
                       val setTimeout: (Int) -> Unit = {}, val host: InetAddress? = null) {
        fun close() { try { closer() } catch (ignored: Exception) {} }
    }

    /** Someone on the Add nearby person page who isn't added yet, with the safety code both phones show. */
    data class Candidate(val key: String, val name: String, val id: String, val code: String)

    /**
     * Someone under People nearby: their name, the short tag from their key, how they're close by, the chat with them
     * if there is one, and the name of someone you've added if it's the same name with another key.
     */
    data class Near(val key: String, val name: String, val tag: String, val way: String, val address: String,
                    val added: Boolean, val sameNameAs: String)

    /** Looking for phones on this Wi-Fi: how many addresses were tried, how many phones answered, and whether it's done. */
    class Look {
        @Volatile var tried = 0
        @Volatile var found = 0
        @Volatile var done = false
        @Volatile var cancelled = false
        @Volatile var problem = ""
        fun cancel() { cancelled = true }
    }

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
        val person = person(app, route)
        if (person.blocked) throw IllegalStateException("You blocked ${route.optString("title")}")
        // Their key changed: nothing goes until you check it (NearbyPeople.keyChanged), like OMEMO's rule.
        if (!NearbyPeople.canSend(person)) throw IllegalStateException("${route.optString("title")}’s key changed. Check it in this chat’s details first.")
        // Replying to a request accepts it, so the reply goes now.
        if (!NearbyPeople.added(person)) MessageRequests.decide(prefs, route.optString("address"), MessageRequests.ACCEPTED)
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
            { socket.soTimeout = it }, socket.inetAddress)
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

    /** Look for people on this Wi-Fi: starts finding phones afresh, as if Nearby had just turned on. */
    private fun refreshDiscovery() {
        try { discovery?.let { nsd?.stopServiceDiscovery(it) } } catch (ignored: Exception) {}
        discovery = null
        try { discover() } catch (ignored: Exception) {}
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
    private fun open(link: Link, initiator: Boolean, known: (String) -> Unit = {}): String? {
        // Bluetooth streams have no read timeout, so a stalled handshake is closed by the timer.
        var adopted = false
        val guard = timer.schedule({ if (!adopted) link.close() }, 12, TimeUnit.SECONDS)
        var peer: String? = null
        try {
            link.setTimeout(10_000)
            val handshake = NoiseHandshake(NoisePattern.XX, initiator, NearbyWire.PROLOGUE, identity.keys)
            // Whether this phone can be listed: the side that starts knows who it's talking to; the other side sends
            // Seen once it does (adopt).
            fun hello(peerAdded: Boolean) = NearbyWire.encode(NearbyWire.Packet.Hello(identity.name, identity.id,
                identity.sign?.public?.let(NoiseCrypto::hex).orEmpty(), pairing(), modern = true,
                visible = NearbyPeople.shown(visibility(), peerAdded)))
            val theirs: NearbyWire.Packet
            if (initiator) {
                link.output.write(NearbyWire.frame(handshake.writeMessage(ByteArray(0))))
                link.output.flush()
                theirs = NearbyWire.decode(handshake.readMessage(readFrame(link.input)))
                peer = NoiseCrypto.hex(handshake.remoteStatic!!)
                // Decide before saying who this phone is, so a phone you haven't let in never learns your key.
                if (!welcome(peer)) { link.close(); return (if (peer == identity.card.keyHex) SELF else peer).also(known) }
                link.output.write(NearbyWire.frame(handshake.writeMessage(hello(contactRoute(app, peer) != null))))
                link.output.flush()
            } else {
                handshake.readMessage(readFrame(link.input))
                link.output.write(NearbyWire.frame(handshake.writeMessage(hello(false))))
                link.output.flush()
                theirs = NearbyWire.decode(handshake.readMessage(readFrame(link.input)))
                peer = NoiseCrypto.hex(handshake.remoteStatic!!)
                if (!welcome(peer)) { link.close(); return (if (peer == identity.card.keyHex) SELF else peer).also(known) }
            }
            val greeting = theirs as? NearbyWire.Packet.Hello ?: throw IOException("Expected a hello")
            val session = Session(link, handshake.transport(), peer, greeting.name, greeting.id,
                starter = if (initiator) identity.card.keyHex else peer, code = NearbyWire.safetyCode(handshake.handshakeHash),
                signKey = greeting.signKey, modern = greeting.modern, known = { contactRoute(app, peer) != null })
            session.pairing = greeting.pairing
            session.visible = greeting.visible
            link.setTimeout(70_000)
            adopted = true
            guard.cancel(false)
            known(peer)
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
     * Whether to go on with someone: a person you've added or who sent you a request; anyone while Add nearby person
     * is open; and anyone else (a few at a time) while you're seen by everyone nearby, or for channels and passing
     * messages along. Never yourself, and never someone you blocked.
     */
    private fun welcome(key: String): Boolean {
        if (key == identity.card.keyHex) return false
        val person = personRoute(app, key)
        if (person != null && blocked(prefs, person.optString("address"))) return false
        if (person != null || pairing()) return true
        return strangersWelcome() && sessions.values.count { !it.contact } < MAX_COMMUNITY
    }

    private fun visibility() = visibility(prefs)

    private fun strangersWelcome() = NearbyPeople.welcomesStrangers(visibility(), community(app))

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
        if (session.contact) arrived(session) else checkKeyChange(session)
        // Each side says what it has, and gets what it's missing.
        workers.execute {
            try {
                // Phones from before People nearby only see "Add nearby person is open" from Mode.
                if (pairing() && !session.modern) session.write(NearbyWire.Packet.Mode(true))
                if (session.modern) session.write(NearbyWire.Packet.Seen(NearbyPeople.shown(visibility(), session.contact)))
                session.write(NearbyWire.Packet.Have(store.items().take(NearbyRelay.INVENTORY).map { it.id }))
            } catch (error: Exception) { session.close() }
        }
        return true
    }

    /** A phone showing the ID of someone you've added, with another key: their chat says so and waits for your OK. */
    private fun checkKeyChange(session: Session) {
        if (personRoute(app, session.peer) != null) return
        val route = claimedBy(session) ?: return
        if (route.optString("changed_key") == session.peer || route.optString("kept_key") == session.peer) return
        val address = route.optString("address")
        updateRoute(app, address) { it.put("changed_key", session.peer).put("changed_name", session.name) }
        notice(route, "${route.optString("title")}’s key changed. This could be a new phone, or someone pretending. " +
            "Messages are on hold until you check it in this chat’s details.")
    }

    /** The person whose ID this phone shows with a different key, if any. */
    private fun claimedBy(session: Session): JSONObject? =
        people(app).firstOrNull { NearbyPeople.keyChanged(person(app, it), session.id, session.peer) }

    /** A line in the chat about its encryption, like XMPP's. */
    private fun notice(route: JSONObject, text: String) {
        try {
            val now = System.currentTimeMillis()
            DirectProtocols.incoming(app, DirectProtocols.record(route, "nearby-note-" + NearbyWire.newId(), text, false, now, "")
                .put("event", true).put("read", 1))
        } catch (ignored: Exception) {}
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
                    is NearbyWire.Packet.Seen -> session.visible = packet.visible
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
        // From people you've added or who sent you a request; someone new lands as a request (NearbyPeople.intake).
        val route = personRoute(app, session.peer)
        val impersonates = route == null && claimedBy(session) != null
        val chat = when (NearbyPeople.intake(route?.let { person(app, it) }, impersonates, visibility(), session.modern)) {
            NearbyPeople.Intake.STORE -> route
            NearbyPeople.Intake.REQUEST -> requestFrom(session)
            NearbyPeople.Intake.REFUSE -> null
        }
        if (store(session.peer, chat, packet)) session.write(NearbyWire.Packet.Ack(packet.id))
    }

    private val requestTimes = java.util.ArrayDeque<Long>()

    /** A message request's chat: their key pinned, not verified, at most 20 new ones an hour. */
    private fun requestFrom(session: Session): JSONObject? = synchronized(requestTimes) {
        val now = System.currentTimeMillis()
        while (requestTimes.isNotEmpty() && now - requestTimes.peekFirst() > 3_600_000L) requestTimes.removeFirst()
        if (requestTimes.size >= 20) return null
        requestTimes.addLast(now)
        try {
            DirectProtocols.linkQuietly(app, MessageProtocol.NEARBY, session.peer, NearbyPeople.label(session.name, NoiseCrypto.hex(session.peer)),
                false, JSONObject().put("nearby_id", session.id).put("tofu", true).put("verified", false).put("request", true))
        } catch (error: Exception) { null }
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
                // Carried messages come only from people with a chat here; someone new can't start one this way.
                NearbyMesh.openDirect(envelope, identity.keys)?.let { direct ->
                    val route = personRoute(app, direct.sender)?.takeIf { NearbyPeople.canSend(person(app, it)) }
                    store(direct.sender, route, direct.text)
                }
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
                          val starter: String, val code: String, val signKey: String, val modern: Boolean,
                          private val known: () -> Boolean) {
        /** Someone you've added, checked each time: adding them on Add nearby person keeps this same connection. */
        val contact: Boolean get() = known()
        @Volatile var alive = true
        @Volatile var pairing = false
        /** They said this phone may list them under People nearby. */
        @Volatile var visible = false
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
            if (personRoute(app, session.peer) == null && !strangersWelcome()) session.close()
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
        if (!strangersWelcome() && !pairing()) for (session in sessions.values) if (personRoute(app, session.peer) == null) session.close()
        retryAll()
    }

    /** Who can see me nearby changed: phones connected now hear it right away. */
    fun visibilityChanged() {
        for (session in sessions.values) if (session.modern && session.alive) {
            try { session.write(NearbyWire.Packet.Seen(NearbyPeople.shown(visibility(), session.contact))) } catch (ignored: Exception) {}
        }
        communityChanged()
    }

    /** People nearby: phones connected now that said they can be seen, and your people from before People nearby. */
    fun peopleNearby(): List<Near> {
        val mine = contacts(app)
        return sessions.values.filter { it.alive && (it.visible || it.contact && !it.modern) }.map { session ->
            val route = personRoute(app, session.peer)
            val twin = if (route == null) mine.firstOrNull { NearbyPeople.sameName(it.optString("title").substringBefore(" · "), session.name) } else null
            Near(session.peer, session.name, NearbyPeople.tag(NoiseCrypto.hex(session.peer)), NearbyPeople.way(session.link.kind),
                route?.optString("address").orEmpty(), session.contact, twin?.optString("title").orEmpty())
        }.sortedWith(compareBy({ !it.added }, { it.name.lowercase() }))
    }

    /**
     * Tap someone under People nearby: a chat with them right away, pinned to the key their phone proved (trust on
     * first use). Not verified until you compare the safety code or scan their code. Returns the chat's link.
     */
    fun startChat(key: String): JSONObject {
        personRoute(app, key)?.let { route ->
            // They sent you a request: tapping them accepts it.
            if (!NearbyPeople.added(person(app, route))) MessageRequests.decide(prefs, route.optString("address"), MessageRequests.ACCEPTED)
            added(key)
            return route
        }
        val session = sessions[key]?.takeIf { it.alive } ?: throw IllegalStateException("They aren’t close by anymore")
        val route = DirectProtocols.linkQuietly(app, MessageProtocol.NEARBY, key, NearbyPeople.label(session.name, NoiseCrypto.hex(key)),
            false, JSONObject().put("nearby_id", session.id).put("tofu", true).put("verified", false))
        added(key)
        if (session.modern) workers.execute {
            try { session.write(NearbyWire.Packet.Seen(NearbyPeople.shown(visibility(), true))) } catch (ignored: Exception) {}
        }
        return route
    }

    /** The safety code with this person, while their phone is connected; "" otherwise. */
    fun safetyCode(key: String): String = sessions[key]?.takeIf { it.alive }?.code.orEmpty()

    /**
     * Look for people on this Wi-Fi: finds phones afresh, then, for networks that block finding phones, tries the
     * Nearby port on each address of this phone's own Wi-Fi network (at most a /24, never mobile data or a public
     * range), at most [NearbyPeople.MAX_PER_SECOND] a second, each answered with the usual Nearby hello. Call
     * [Look.cancel] when the page closes.
     */
    fun lookOnWifi(): Look {
        val look = Look()
        refreshDiscovery()
        retryAll()
        workers.execute { try { lookThrough(look) } finally { look.done = true } }
        return look
    }

    @Suppress("DEPRECATION")
    private fun lookThrough(look: Look) {
        val connectivity = app.getSystemService(ConnectivityManager::class.java) ?: return
        // A Wi-Fi network with no internet often isn't the default one, so find it by itself and send only over it.
        val wifi = connectivity.allNetworks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)?.let {
                NearbyPeople.mayLook(it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                    it.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            } == true
        }
        if (wifi == null) { look.problem = "Connect to a Wi-Fi network first. Looking never uses mobile data."; return }
        val own = connectivity.getLinkProperties(wifi)?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        val targets = own?.let { NearbyPeople.addressesToTry(it.address.address, it.prefixLength) }.orEmpty()
        if (targets.isEmpty()) { look.problem = "FluidMessages looks only on home and local Wi-Fi networks, and this isn’t one."; return }
        val phones: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val busy = HashSet<InetAddress>()
        for (session in sessions.values) if (session.alive && session.link.host != null) { busy += session.link.host; phones += session.peer }
        look.found = phones.size
        val pending = Semaphore(16)
        for (target in targets) {
            if (look.cancelled || !running) break
            val address = InetAddress.getByAddress(target)
            if (address in busy) continue
            pending.acquire()
            look.tried++
            workers.execute {
                var socket: Socket? = null
                try {
                    socket = wifi.socketFactory.createSocket()
                    socket.connect(InetSocketAddress(address, PORT), NearbyPeople.TIMEOUT_MS)
                } catch (error: Exception) {
                    try { socket?.close() } catch (ignored: IOException) {}
                    socket = null
                } finally { pending.release() }
                val connected = socket ?: return@execute
                if (look.cancelled || !handshakes.tryAcquire(3, TimeUnit.SECONDS)) { connected.close(); return@execute }
                try {
                    open(tcp(connected), initiator = true) { peer ->
                        if (peer == SELF) return@open
                        phones += peer
                        look.found = phones.size
                        found.putIfAbsent("look:" + address.hostAddress, Found(address, PORT).also { it.peer = peer })
                    }
                } finally { handshakes.release() }
            }
            Thread.sleep(NearbyPeople.SPACING_MS)
        }
        // Let the last tries finish before saying how many answered.
        if (pending.tryAcquire(16, NearbyPeople.TIMEOUT_MS * 2L, TimeUnit.MILLISECONDS)) pending.release(16)
        Thread.sleep(1500)
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

        /** Everyone with a one-to-one chat here: people you've added, and message requests. */
        @JvmStatic fun people(context: Context): List<JSONObject> = nearbyRoutes(context).filter { !it.optBoolean("group") }

        /** People you've added (or whose request you accepted), as their links. */
        @JvmStatic fun contacts(context: Context): List<JSONObject> = people(context).filter { NearbyPeople.added(person(context, it)) }

        @JvmStatic fun personRoute(context: Context, key: String): JSONObject? =
            people(context).firstOrNull { it.optString("target").equals(key, ignoreCase = true) }

        /** A chat's trust, from its link (NearbyPeople.Person). */
        @JvmStatic fun person(context: Context, route: JSONObject): NearbyPeople.Person = person(DirectProtocols.prefs(context), route)

        @JvmStatic fun person(prefs: SharedPreferences, route: JSONObject): NearbyPeople.Person {
            val address = route.optString("address")
            return NearbyPeople.Person(route.optString("target"), route.optString("nearby_id"), route.optBoolean("tofu"),
                route.optBoolean("verified"), route.optBoolean("request"), MessageRequests.decision(prefs, address),
                route.optString("changed_key"), blocked(prefs, address))
        }

        @JvmStatic fun blocked(prefs: SharedPreferences, address: String) = MainActivity.addressList(prefs, "blocked").contains(address)

        /** A Nearby request chat, for MessageRequests: only one-to-one chats someone else started. */
        @JvmStatic fun requestRoute(prefs: SharedPreferences, address: String): JSONObject? =
            DirectProtocols.route(prefs, address, MessageProtocol.NEARBY)?.takeIf { it.optBoolean("request") && !it.optBoolean("group") }

        @JvmStatic fun visibility(prefs: SharedPreferences) = NearbyPeople.Visibility.parse(prefs.getString(NearbyPeople.VISIBILITY, null))

        @JvmStatic fun setVisibility(context: Context, choice: NearbyPeople.Visibility) {
            DirectProtocols.prefs(context).edit().putString(NearbyPeople.VISIBILITY, choice.key).apply()
            current?.visibilityChanged()
        }

        /** Marks a chat verified: you compared the safety code or scanned their code, and it matched. */
        @JvmStatic fun markVerified(context: Context, address: String) {
            updateRoute(context, address) { it.put("verified", true) }
            MmsTransport.changed(context)
        }

        /** Their code, scanned or typed while verifying: true when it holds the key this chat is locked to. */
        @JvmStatic fun verifyCard(context: Context, address: String, card: NearbyWire.Card): Boolean {
            val route = DirectProtocols.route(DirectProtocols.prefs(context), address, MessageProtocol.NEARBY) ?: return false
            if (!route.optString("target").equals(card.keyHex, ignoreCase = true)) return false
            markVerified(context, address)
            return true
        }

        /** Their key changed and you checked it with them: the chat moves to the new key, not verified yet. */
        @JvmStatic fun acceptNewKey(context: Context, address: String) {
            var key = ""
            updateRoute(context, address) { route ->
                key = route.optString("changed_key")
                if (key.isNotEmpty()) route.put("target", key).put("verified", false).put("tofu", true)
                route.remove("changed_key"); route.remove("changed_name"); route.remove("kept_key")
            }
            if (key.isNotEmpty()) current?.added(key)
            MmsTransport.changed(context)
        }

        /** Keep the key you had: the other one is refused from now on, and messages go again. */
        @JvmStatic fun keepOldKey(context: Context, address: String) {
            updateRoute(context, address) { route ->
                route.optString("changed_key").takeIf { it.isNotEmpty() }?.let { route.put("kept_key", it) }
                route.remove("changed_key"); route.remove("changed_name")
            }
            MmsTransport.changed(context)
        }

        private fun updateRoute(context: Context, address: String, change: (JSONObject) -> Unit) {
            synchronized(DirectProtocols::class.java) {
                val prefs = DirectProtocols.prefs(context)
                val routes = DirectProtocols.routes(prefs)
                for (i in 0 until routes.length()) {
                    val route = routes.optJSONObject(i) ?: continue
                    if (route.optString("address") == address && MessageProtocol.parse(route.optString("protocol")) == MessageProtocol.NEARBY) change(route)
                }
                prefs.edit().putString(DirectProtocols.ROUTES, routes.toString()).commit()
            }
        }

        /** The phone owner's first name, to start the Nearby name with, or "". Needs the contacts permission. */
        @JvmStatic fun ownerName(context: Context): String = try {
            if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) "" else
                context.contentResolver.query(android.provider.ContactsContract.Profile.CONTENT_URI,
                    arrayOf(android.provider.ContactsContract.Profile.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) NearbyPeople.firstName(it.getString(0)) else ""
                }.orEmpty()
        } catch (error: Exception) { "" }

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
            // Already a chat from People nearby or a request: adding them in person verifies it.
            personRoute(context, card.keyHex)?.let { route ->
                val address = route.optString("address")
                if (!NearbyPeople.added(person(context, route))) MessageRequests.decide(DirectProtocols.prefs(context), address, MessageRequests.ACCEPTED)
                markVerified(context, address)
                current?.added(card.keyHex)
                return route
            }
            // Added in person (a scanned or typed code, or matching safety codes): verified from the start.
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
            if (route.optBoolean("group")) return if (route.optBoolean("room")) Encryption.END_TO_END else Encryption.NONE
            // People met under People nearby: yellow "Not verified yet" until you check them, like OMEMO's blind trust.
            val person = person(prefs, route)
            return if (NearbyPeople.verified(person) && person.changedKey.isEmpty()) Encryption.END_TO_END else Encryption.UNVERIFIED
        }

        /** The words under a one-to-one chat's lock in its details. */
        @JvmStatic fun trustWords(prefs: SharedPreferences, address: String): String {
            val route = DirectProtocols.route(prefs, address, MessageProtocol.NEARBY) ?: return ""
            return NearbyPeople.lockWords(person(prefs, route), route.optString("title").substringBefore(" · "))
        }

        /** The note above the composer: what others can see in this chat. "" for one-to-one chats. */
        @JvmStatic fun notice(prefs: SharedPreferences, address: String): String {
            val route = DirectProtocols.route(prefs, address, MessageProtocol.NEARBY) ?: return ""
            return when {
                !route.optBoolean("group") -> if (route.optString("changed_key").isEmpty()) "" else
                    "${route.optString("title")}’s key changed. Messages are on hold: open this chat’s details to check it."
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
