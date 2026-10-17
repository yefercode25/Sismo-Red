package com.sismored.data

import com.sismored.BuildConfig
import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.selectAsFlow
import io.github.jan.supabase.annotations.SupabaseExperimental
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

internal data class AccountProfile(
    val userId: String,
    val email: String,
    val displayName: String,
    val role: String,
    val region: String,
    val municipality: String
)

@Serializable
internal data class StationRecord(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("display_name") val displayName: String,
    val region: String,
    val municipality: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("location_updated_at") val locationUpdatedAt: String? = null
)

@Serializable
internal data class StationStatus(
    @SerialName("station_id") val stationId: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("station_name") val stationName: String,
    val region: String,
    val municipality: String,
    val amplitude: Float,
    val anomaly: Boolean,
    @SerialName("acceleration_x_m_s2") val accelerationX: Float? = null,
    @SerialName("acceleration_y_m_s2") val accelerationY: Float? = null,
    @SerialName("acceleration_z_m_s2") val accelerationZ: Float? = null,
    @SerialName("rotation_x_deg") val rotationX: Float? = null,
    @SerialName("rotation_y_deg") val rotationY: Float? = null,
    @SerialName("rotation_z_deg") val rotationZ: Float? = null,
    @SerialName("gyro_x_rad_s") val gyroX: Float? = null,
    @SerialName("gyro_y_rad_s") val gyroY: Float? = null,
    @SerialName("gyro_z_rad_s") val gyroZ: Float? = null,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
internal data class CommunityAlert(
    val id: String,
    val region: String,
    val municipality: String,
    @SerialName("detected_at") val detectedAt: String,
    @SerialName("station_count") val stationCount: Int,
    @SerialName("station_ids") val stationIds: List<String>
)

@Serializable
private data class ProfileRow(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    val role: String,
    val region: String,
    val municipality: String
)

@Serializable
private data class AnomalyEvent(
    @SerialName("station_id") val stationId: String,
    val amplitude: Float
)

@Serializable
private data class StationLocation(
    val latitude: Double,
    val longitude: Double,
    @SerialName("location_updated_at") val updatedAt: String
)

@OptIn(SupabaseExperimental::class)
internal class SupabaseRepository {
    private val client: SupabaseClient? = if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
        null
    } else {
        createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val observationJobs = mutableListOf<Job>()
    private var snapshotJob: Job? = null

    val isConfigured: Boolean get() = client != null

    suspend fun register(email: String, password: String, name: String, role: String, region: String, municipality: String): AccountProfile? {
        val supabase = requireClient()
        supabase.auth.signUpWith(Email) {
            this.email = email
            this.password = password
            data = buildJsonObject {
                put("display_name", name)
                put("role", role)
                put("region", region)
                put("municipality", municipality)
            }
        }
        return currentProfile()
    }

    suspend fun signIn(email: String, password: String): AccountProfile {
        requireClient().auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        return currentProfile() ?: error("La cuenta aún no tiene un perfil SismoRed. Ejecuta el esquema SQL de Supabase y vuelve a ingresar.")
    }

    suspend fun currentProfile(): AccountProfile? {
        val supabase = client ?: return null
        supabase.auth.awaitInitialization()
        val user = supabase.auth.currentUserOrNull() ?: return null
        val row = supabase.from("profiles").select {
            filter { eq("user_id", user.id) }
        }.decodeList<ProfileRow>().firstOrNull() ?: return null
        return AccountProfile(row.userId, user.email ?: "", row.displayName, row.role, row.region, row.municipality)
    }

    suspend fun stationForOwner(userId: String): StationRecord? = requireClient().from("stations").select {
        filter { eq("owner_id", userId) }
    }.decodeList<StationRecord>().firstOrNull()

    suspend fun updateStationLocation(station: StationRecord, latitude: Double, longitude: Double): StationRecord {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "La ubicación recibida no es válida." }
        requireClient().from("stations").update(StationLocation(latitude, longitude, Instant.now().toString())) {
            filter { eq("id", station.id) }
        }
        return stationForOwner(station.ownerId) ?: error("No se pudo volver a leer la estación actualizada.")
    }

    suspend fun findStation(id: String): StationRecord? {
        if (!id.matches(Regex("[0-9a-fA-F-]{36}"))) return null
        return requireClient().from("stations").select {
            filter { eq("id", id) }
        }.decodeList<StationRecord>().firstOrNull()
    }

    fun observe(onAlerts: (List<CommunityAlert>) -> Unit, onStations: (List<StationStatus>) -> Unit, onRegisteredStations: (List<StationRecord>) -> Unit, onError: (Throwable) -> Unit) {
        val supabase = client ?: return
        observationJobs += scope.launch {
            try {
                supabase.from("alerts").selectAsFlow(CommunityAlert::id).collectLatest { alerts ->
                    onAlerts(alerts)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { onError(error) }
        }
        observationJobs += scope.launch {
            try {
                supabase.from("station_status").selectAsFlow(StationStatus::stationId).collectLatest { stations ->
                    onStations(stations)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { onError(error) }
        }
        observationJobs += scope.launch {
            try {
                supabase.from("stations").selectAsFlow(StationRecord::id).collectLatest { stations ->
                    onRegisteredStations(stations)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { onError(error) }
        }
        // Keep a lightweight REST snapshot as a recovery path if a realtime
        // channel reconnects without replaying the latest row to this client.
        snapshotJob?.cancel()
        snapshotJob = scope.launch {
            while (isActive) {
                try {
                    onAlerts(supabase.from("alerts").select {}.decodeList<CommunityAlert>())
                    onStations(supabase.from("station_status").select {}.decodeList<StationStatus>())
                    onRegisteredStations(supabase.from("stations").select {}.decodeList<StationRecord>())
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    onError(error)
                }
                delay(10_000L)
            }
        }
    }

    fun publishStatus(
        profile: AccountProfile,
        station: StationRecord,
        amplitude: Float,
        anomaly: Boolean,
        accelerationX: Float,
        accelerationY: Float,
        accelerationZ: Float,
        rotationX: Float?,
        rotationY: Float?,
        rotationZ: Float?,
        gyroX: Float?,
        gyroY: Float?,
        gyroZ: Float?
    ) {
        val supabase = client ?: return
        scope.launch {
            runCatching {
                supabase.from("station_status").upsert(
                    StationStatus(
                        station.id, profile.userId, station.displayName, station.region, station.municipality,
                        amplitude, anomaly, accelerationX, accelerationY, accelerationZ, rotationX, rotationY,
                        rotationZ, gyroX, gyroY, gyroZ,
                        DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.now())
                    )
                ) { onConflict = "station_id" }
            }.onFailure { Log.w("SismoRed", "No se pudo publicar el estado de la estación", it) }
        }
    }

    fun publishAnomaly(station: StationRecord, amplitude: Float) {
        val supabase = client ?: return
        scope.launch { runCatching { supabase.from("anomaly_events").insert(AnomalyEvent(station.id, amplitude)) } }
    }

    suspend fun signOut() { stopObserving(); client?.auth?.signOut() }

    fun stopObserving() {
        snapshotJob?.cancel()
        snapshotJob = null
        observationJobs.forEach(Job::cancel)
        observationJobs.clear()
    }

    fun close() { stopObserving(); scope.cancel() }

    private fun requireClient(): SupabaseClient = client ?: error("Configura supabase.url y supabase.anonKey en local.properties.")
}
