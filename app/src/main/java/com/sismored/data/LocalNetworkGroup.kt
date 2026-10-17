package com.sismored.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Base64
import android.util.Log
import com.sismored.ui.RemoteReading
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors

/** TCP group for devices on the same Wi-Fi/LAN. The host relays live sensor readings. */
internal class LocalNetworkGroup(
    context: Context,
    private val onStatus: (String, String?, String?, Int) -> Unit,
    private val onReadings: (List<RemoteReading>) -> Unit
) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val executor = Executors.newCachedThreadPool()
    private val writerExecutor = Executors.newSingleThreadExecutor()
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val peerWriters = Collections.synchronizedMap(mutableMapOf<Socket, BufferedWriter>())
    private val readings = mutableMapOf<String, RemoteReading>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var clientSocket: Socket? = null
    @Volatile private var clientWriter: BufferedWriter? = null
    @Volatile private var role: String? = null
    @Volatile private var lastSendLogAtMs = 0L
    @Volatile private var discoveryListener: NsdManager.DiscoveryListener? = null
    @Volatile private var registrationListener: NsdManager.RegistrationListener? = null
    @Volatile private var epoch = 0
    private val nodeId = UUID.randomUUID().toString()

    fun startHost() {
        stop()
        role = "Estación principal"
        executor.execute {
            try {
                val socket = ServerSocket(PORT)
                server = socket
                updateStatus("Grupo activo · esperando teléfonos", role, localIpv4(), 0)
                advertiseGroup()
                while (!socket.isClosed) {
                    val peer = socket.accept()
                    clients.add(peer)
                    peerWriters[peer] = BufferedWriter(OutputStreamWriter(peer.getOutputStream(), Charsets.UTF_8))
                    updateStatus("Grupo activo · ${clients.size} teléfono(s) conectado(s)", role, localIpv4(), clients.size)
                    executor.execute { readPeer(peer) }
                }
            } catch (e: Exception) {
                if (server?.isClosed == false) updateStatus("No se pudo abrir el grupo: ${e.localizedMessage}", null, null, 0)
            }
        }
    }

    fun join(host: String) {
        stop()
        role = "Teléfono conectado"
        executor.execute {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host.trim(), PORT), CONNECT_TIMEOUT_MS)
                clientSocket = socket
                clientWriter = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                updateStatus("Conectado a $host", role, null, 1)
                readServer(socket)
            } catch (e: Exception) {
                updateStatus("No se pudo conectar: ${e.localizedMessage ?: "revisa la dirección y el Wi-Fi"}", null, null, 0)
            }
        }
    }

    /** Finds a SismoRed station on the current local network and joins it automatically. */
    fun discoverAndJoin() {
        stop()
        val searchEpoch = epoch
        updateStatus("Buscando grupos SismoRed en esta red Wi‑Fi…", null, null, 0)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                if (searchEpoch == epoch) updateStatus("Buscando grupos SismoRed en esta red Wi‑Fi…", null, null, 0)
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                if (searchEpoch != epoch || service.serviceType != SERVICE_TYPE) return
                stopDiscovery(this)
                updateStatus("Estación encontrada · conectando automáticamente…", null, null, 0)
                @Suppress("DEPRECATION")
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (searchEpoch == epoch) updateStatus("No se pudo resolver la estación. Vuelve a buscar.", null, null, 0)
                    }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (searchEpoch != epoch) return
                        val address = serviceInfo.host?.hostAddress
                        if (address.isNullOrBlank() || serviceInfo.port <= 0) {
                            updateStatus("La estación no informó una dirección válida. Vuelve a buscar.", null, null, 0)
                        } else {
                            updateStatus("Estación encontrada en ${address}:${serviceInfo.port} · conectando…", null, null, 0)
                            join(address, serviceInfo.port)
                        }
                    }
                })
            }
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                stopDiscovery(this)
                if (searchEpoch == epoch) updateStatus("No se pudo iniciar la búsqueda de red (código $errorCode).", null, null, 0)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { nsdManager.stopServiceDiscovery(this) }
            }
        }
        discoveryListener = listener
        runCatching { nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { updateStatus("No se pudo buscar estaciones: ${it.localizedMessage}", null, null, 0) }
    }

    fun send(name: String, elapsedMs: Long, x: Float, y: Float, z: Float, amplitude: Float, anomaly: Boolean) {
        if (role == null) return
        val safeName = Base64.encodeToString(name.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val line = listOf(
            "S", nodeId, safeName, elapsedMs.toString(), x.toString(), y.toString(), z.toString(),
            amplitude.toString(), if (anomaly) "1" else "0", System.currentTimeMillis().toString()
        ).joinToString("\t")
        val now = System.currentTimeMillis()
        if (now - lastSendLogAtMs >= 1000L) {
            lastSendLogAtMs = now
            Log.d(TAG, "Enviando muestra como $role")
        }
        val currentRole = role ?: return
        if (currentRole == "Estación principal") {
            addReading(RemoteReading(nodeId, name, elapsedMs, amplitude, anomaly, System.currentTimeMillis()))
            writerExecutor.execute { broadcast(line) }
        } else {
            val socket = clientSocket ?: return
            writerExecutor.execute {
                synchronized(socket) {
                    runCatching { clientWriter?.let { writer -> writer.write(line); writer.newLine(); writer.flush() } }
                        .onFailure { Log.w(TAG, "No se pudo enviar la muestra", it) }
                }
            }
        }
    }

    fun stop() {
        epoch++
        discoveryListener?.let(::stopDiscovery)
        discoveryListener = null
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        registrationListener = null
        runCatching { server?.close() }
        runCatching { clientSocket?.close() }
        synchronized(clients) { clients.toList().forEach { runCatching { it.close() } }; clients.clear() }
        peerWriters.clear()
        server = null
        clientSocket = null
        clientWriter = null
        role = null
        synchronized(readings) { readings.clear() }
        onReadings(emptyList())
        updateStatus("Sin conexión", null, null, 0)
    }

    fun shutdown() {
        stop()
        executor.shutdownNow()
        writerExecutor.shutdownNow()
    }

    private fun readPeer(socket: Socket) {
        try {
            BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    val parsed = parse(line) ?: return@forEach
                    Log.d(TAG, "Muestra recibida de ${parsed.name}: ${parsed.amplitude} m/s²")
                    addReading(parsed)
                    broadcast(line)
                }
            }
        } catch (e: Exception) { Log.w(TAG, "Peer dejó el grupo", e) }
        finally {
            Log.d(TAG, "Cerrando peer ${socket.inetAddress?.hostAddress}")
            clients.remove(socket)
            peerWriters.remove(socket)
            runCatching { socket.close() }
            if (role == "Estación principal") updateStatus("Grupo activo · ${clients.size} teléfono(s) conectado(s)", role, localIpv4(), clients.size)
        }
    }

    private fun readServer(socket: Socket) {
        try {
            BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).useLines { lines ->
                lines.forEach { line -> parse(line)?.let(::addReading) }
            }
        } catch (e: Exception) { Log.w(TAG, "Error leyendo estación", e) }
        finally {
            if (clientSocket === socket) {
                Log.w(TAG, "El canal de la estación terminó")
                clientSocket = null
                updateStatus("Conexión interrumpida · revisa que el anfitrión siga activo", null, null, 0)
            }
        }
    }

    private fun join(host: String, port: Int = PORT) {
        stop()
        role = "Teléfono conectado"
        executor.execute {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host.trim(), port), CONNECT_TIMEOUT_MS)
                clientSocket = socket
                clientWriter = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                updateStatus("Conectado a la estación SismoRed", role, null, 1)
                readServer(socket)
            } catch (e: Exception) {
                updateStatus("No se pudo conectar a la estación: ${e.localizedMessage ?: "comprueba el Wi‑Fi"}", null, null, 0)
            }
        }
    }

    private fun advertiseGroup() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "SismoRed-${nodeId.take(6)}"
            serviceType = SERVICE_TYPE
            port = PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                if (role == "Estación principal") {
                    updateStatus("Grupo SismoRed activo · esperando teléfonos", role, localIpv4(), clients.size)
                }
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "No se pudo anunciar el grupo por NSD: $errorCode")
                if (role == "Estación principal") updateStatus("Grupo activo · búsqueda automática no disponible (código $errorCode)", role, localIpv4(), clients.size)
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "No se pudo retirar el anuncio NSD: $errorCode")
            }
        }
        registrationListener = listener
        runCatching { nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.e(TAG, "No se pudo anunciar el grupo", it) }
    }

    private fun stopDiscovery(listener: NsdManager.DiscoveryListener) {
        runCatching { nsdManager.stopServiceDiscovery(listener) }
        if (discoveryListener === listener) discoveryListener = null
    }

    private fun parse(line: String): RemoteReading? = runCatching {
        val fields = line.split('\t')
        if (fields.size != 10 || fields[0] != "S") return null
        RemoteReading(
            id = fields[1],
            name = String(Base64.decode(fields[2], Base64.NO_WRAP), Charsets.UTF_8),
            elapsedMs = fields[3].toLong(),
            amplitude = fields[7].toFloat(),
            anomaly = fields[8] == "1",
            updatedAtMs = fields[9].toLong()
        )
    }.getOrNull()

    private fun addReading(reading: RemoteReading) {
        val snapshot = synchronized(readings) { readings[reading.id] = reading; readings.values.sortedByDescending { it.updatedAtMs } }
        onReadings(snapshot)
    }

    private fun broadcast(line: String): Int {
        var delivered = 0
        synchronized(clients) {
            val writerSockets = clients.toList()
            writerSockets.forEach { socket ->
                runCatching {
                    synchronized(socket) {
                        peerWriters[socket]?.let { writer -> writer.write(line); writer.newLine(); writer.flush() }
                    }
                    delivered++
                }.onFailure { error ->
                    Log.w(TAG, "No se pudo retransmitir una lectura", error)
                    clients.remove(socket); peerWriters.remove(socket); runCatching { socket.close() }
                }
            }
            if (role == "Estación principal") updateStatus("Grupo activo · ${clients.size} teléfono(s) conectado(s)", role, localIpv4(), clients.size)
        }
        return delivered
    }

    private fun updateStatus(message: String, currentRole: String?, address: String?, peers: Int) = onStatus(message, currentRole, address, peers)

    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    companion object {
        const val PORT = 38647
        private const val SERVICE_TYPE = "_sismored._tcp."
        private const val CONNECT_TIMEOUT_MS = 6000
        private const val TAG = "SismoRedNetwork"
    }
}
