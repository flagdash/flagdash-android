package com.flagdash.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class FlagDashClient @JvmOverloads constructor(
    private val sdkKey: String,
    baseUrl: String = "https://flagdash.io",
    timeoutMillis: Long = 5_000,
    private val cacheTtlMillis: Long = 60_000,
    private val region: String? = detectRegion(),
    httpClient: OkHttpClient? = null,
) : Closeable {
    private data class CacheEntry(val value: JsonElement, val expiresAt: Long)
    private val baseUrl = baseUrl.trimEnd('/') + "/api/v1"
    private val ownsHttp = httpClient == null
    private val http = httpClient ?: OkHttpClient.Builder().callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val events = mutableListOf<JsonObject>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes: SharedFlow<Unit> = mutableChanges

    init { require(sdkKey.isNotBlank()) { "sdkKey is required" } }

    @JvmOverloads suspend fun flag(key: String, defaultValue: JsonElement = JsonPrimitive(false), context: EvaluationContext? = null): JsonElement {
        if (context != null) return flagDetail(key, defaultValue, context).value
        cached("flag:$key")?.let { return it }
        return allFlags()[key] ?: defaultValue
    }

    @JvmOverloads suspend fun flagBoolean(key: String, defaultValue: Boolean = false, context: EvaluationContext? = null): Boolean =
        (flag(key, JsonPrimitive(defaultValue), context) as? JsonPrimitive)?.booleanOrNull ?: defaultValue

    @JvmOverloads suspend fun flagString(key: String, defaultValue: String, context: EvaluationContext? = null): String =
        (flag(key, JsonPrimitive(defaultValue), context) as? JsonPrimitive)?.contentOrNull ?: defaultValue

    suspend fun flagDetail(key: String, defaultValue: JsonElement = JsonNull, context: EvaluationContext? = null): FlagDetail = try {
        val item: FlagWire = get("/flags/${segment(key)}", context)
        FlagDetail(item.key, item.value, item.reason, item.variationKey)
    } catch (_: Exception) { FlagDetail(key, defaultValue, "default") }

    suspend fun allFlags(context: EvaluationContext? = null): Map<String, JsonElement> = try {
        val values = get<FlagsEnvelope>("/flags", context).flags
        if (context == null) values.forEach { (key, value) -> put("flag:$key", value) }
        values
    } catch (_: Exception) { emptyMap() }

    suspend fun config(key: String, defaultValue: JsonElement = JsonNull): JsonElement {
        cached("config:$key")?.let { return it }
        return try { get<ConfigEnvelope>("/configs/${segment(key)}").value.also { put("config:$key", it) } }
        catch (_: Exception) { defaultValue }
    }

    suspend fun listConfigs(): List<ConfigEnvelope> = get<ConfigsEnvelope>("/configs").configs
    suspend fun aiConfig(fileName: String): Map<String, JsonElement>? = try { get<AiConfigEnvelope>("/ai-configs/${segment(fileName)}").aiConfig } catch (_: Exception) { null }
    suspend fun listAiConfigs(): List<Map<String, JsonElement>> = get<AiConfigsEnvelope>("/ai-configs").aiConfigs

    suspend fun translation(key: String, locale: String, defaultValue: String = key, variables: Map<String, Any?> = emptyMap()): String {
        val parts = key.split('.', limit = 2); if (parts.size != 2) return defaultValue
        return try {
            val pattern = get<CatalogEnvelope>("/translations/${segment(locale)}/${segment(parts[0])}").catalog.messages[parts[1]] ?: return defaultValue
            Regex("\\{([\\w.]+)}").replace(pattern) { match -> variables[match.groupValues[1]]?.toString() ?: match.value }
        } catch (_: Exception) { defaultValue }
    }

    suspend fun experiment(key: String, context: EvaluationContext): Map<String, JsonElement>? {
        if (context.userId == null && context.unitId == null) return null
        return try { get<ExperimentEnvelope>("/experiments/${segment(key)}", context).experiment } catch (_: Exception) { null }
    }

    fun trackExperimentMetric(experimentKey: String, eventName: String, userId: String, value: Double? = null, properties: Map<String, String> = emptyMap()) {
        synchronized(events) {
            if (events.size >= 1_000) return
            events += JsonObject(mapOf("event_id" to JsonPrimitive("evt_${UUID.randomUUID()}"), "experiment_key" to JsonPrimitive(experimentKey),
                "event_name" to JsonPrimitive(eventName), "user_id" to JsonPrimitive(userId), "value" to (value?.let(::JsonPrimitive) ?: JsonNull),
                "properties" to JsonObject(properties.mapValues { JsonPrimitive(it.value) }), "occurred_at" to JsonPrimitive(Instant.now().toString())))
        }
    }

    suspend fun flush(): Boolean {
        return try {
            while (true) {
                val batch = synchronized(events) { events.take(100) }
                if (batch.isEmpty()) return true
                post("/experiment-events/batch", JsonObject(mapOf("events" to kotlinx.serialization.json.JsonArray(batch))))
                synchronized(events) { repeat(batch.size) { events.removeAt(0) } }
            }
            @Suppress("UNREACHABLE_CODE") true
        } catch (_: Exception) { false }
    }

    fun clearCache() = cache.clear()
    override fun close() { runBlocking { flush() }; scope.cancel(); if (ownsHttp) http.dispatcher.executorService.shutdown() }

    private suspend inline fun <reified T> get(path: String, context: EvaluationContext? = null): T {
        val builder = (baseUrl + path).toHttpUrl().newBuilder()
        context?.userId?.let { builder.addQueryParameter("user_id", it) }
        context?.unitId?.let { builder.addQueryParameter("unit_id", it) }
        context?.attributes?.forEach(builder::addQueryParameter)
        region?.let { builder.addQueryParameter("region", it) }
        val request = Request.Builder().url(builder.build()).header("Authorization", "Bearer $sdkKey").build()
        val body = http.newCall(request).execute().use { response -> if (!response.isSuccessful) error("FlagDash HTTP ${response.code}"); response.body!!.string() }
        return json.decodeFromString(body)
    }

    private fun post(path: String, body: JsonObject) {
        val request = Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $sdkKey")
            .post(json.encodeToString(body).toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { if (!it.isSuccessful) error("FlagDash HTTP ${it.code}") }
    }
    private fun cached(key: String): JsonElement? = cache[key]?.takeIf { it.expiresAt > android.os.SystemClock.elapsedRealtime() }?.value.also { if (it == null) cache.remove(key) }
    private fun put(key: String, value: JsonElement) { if (cacheTtlMillis > 0) cache[key] = CacheEntry(value, android.os.SystemClock.elapsedRealtime() + cacheTtlMillis) }
    private fun segment(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    companion object {
        private val regionNames = listOf("FLAGDASH_REGION", "FLY_REGION", "AWS_REGION", "AWS_DEFAULT_REGION", "VERCEL_REGION", "GOOGLE_CLOUD_REGION", "RAILWAY_REPLICA_REGION", "RENDER_REGION")
        private fun detectRegion() = regionNames.firstNotNullOfOrNull(System::getenv)
    }
}
