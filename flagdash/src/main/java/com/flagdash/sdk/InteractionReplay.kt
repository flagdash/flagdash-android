package com.flagdash.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.time.Instant
import java.util.concurrent.TimeUnit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Explicit, privacy-safe interaction timeline. It never captures pixels or Android view trees. */
class InteractionReplay @JvmOverloads constructor(
    private val sdkKey: String,
    baseUrl: String = "https://flagdash.io",
    private val identity: String? = null,
    private val release: String? = null,
    metadata: Map<String, Any?> = emptyMap(),
    timeoutMillis: Long = 5_000,
    httpClient: OkHttpClient? = null,
) : Closeable, DefaultLifecycleObserver {
    private val baseUrl = baseUrl.trimEnd('/')
    private val ownsHttp = httpClient == null
    private val http = httpClient ?: OkHttpClient.Builder().callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val metadata = sanitizeMap(metadata)
    private val startedAt = Instant.now()
    private val events = mutableListOf<JsonObject>()
    private var id: String? = null
    private var sequence = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lifecycleOwner: LifecycleOwner? = null

    init { require(sdkKey.isNotBlank()) { "sdkKey is required" } }

    suspend fun start(): Boolean = try {
        val response = api("/api/v1/replay-sessions/start", JsonObject(mapOf(
            "type" to JsonPrimitive("interaction"), "platform" to JsonPrimitive("android"),
            "sdk_name" to JsonPrimitive("flagdash-android"), "started_at" to JsonPrimitive(startedAt.toString()),
            "identity" to nullable(identity), "release" to nullable(release), "metadata" to metadata)))
        if (response.first == 204) false else {
            id = (json.parseToJsonElement(response.second) as JsonObject)["id"]?.let { (it as JsonPrimitive).content }
            id != null
        }
    } catch (_: Exception) { false }

    @Synchronized fun interaction(name: String, screen: String? = null, category: String = "action", properties: Map<String, Any?> = emptyMap()) {
        if (id == null || name.isBlank() || events.size >= 1_000) return
        events += JsonObject(mapOf("name" to JsonPrimitive(name.take(100)), "category" to JsonPrimitive(category.take(40)),
            "timestamp" to JsonPrimitive(Instant.now().toString()), "screen" to nullable(screen?.take(200)),
            "properties" to sanitizeMap(properties)))
    }
    fun screen(name: String, properties: Map<String, Any?> = emptyMap()) = interaction("screen_viewed", name, "navigation", properties)
    fun breadcrumb(message: String, properties: Map<String, Any?> = emptyMap()) = interaction(message, category = "breadcrumb", properties = properties)
    fun captureException(error: Throwable, properties: Map<String, Any?> = emptyMap()) = interaction(error.javaClass.simpleName, category = "exception", properties = properties)
    fun contextHeaders(): Map<String, String> = id?.let { mapOf("x-flagdash-replay-id" to it) } ?: emptyMap()

    /** Attach explicitly when background transitions should flush pending events. */
    fun observeLifecycle(owner: LifecycleOwner): InteractionReplay {
        lifecycleOwner?.lifecycle?.removeObserver(this)
        lifecycleOwner = owner
        owner.lifecycle.addObserver(this)
        return this
    }

    override fun onStop(owner: LifecycleOwner) { scope.launch { flush() } }

    suspend fun flush(): Boolean {
        val replayId = id ?: return true
        while (true) {
            val batch = synchronized(this) { if (events.isEmpty()) emptyList() else events.take(100).also { events.subList(0, it.size).clear() } }
            if (batch.isEmpty()) return true
            val raw = json.encodeToString(JsonArray(batch)).encodeToByteArray()
            val manifestResponse = api("/api/v1/replay-sessions/$replayId/chunks/presign", JsonObject(mapOf(
                "sequence" to JsonPrimitive(sequence++), "byte_size" to JsonPrimitive(raw.size),
                "event_count" to JsonPrimitive(batch.size), "content_encoding" to JsonPrimitive("identity"))))
            val upload = (json.parseToJsonElement(manifestResponse.second) as JsonObject)["upload"] as JsonObject
            val request = Request.Builder().url((upload["url"] as JsonPrimitive).content).apply {
                (upload["headers"] as? JsonObject)?.forEach { (key, value) -> header(key, (value as JsonPrimitive).content) }
            }.put(raw.toRequestBody("application/json".toMediaType())).build()
            val ok = withContext(Dispatchers.IO) { http.newCall(request).execute().use { it.isSuccessful } }
            if (!ok) return false
        }
    }

    suspend fun stop(): Boolean {
        if (!flush()) return false
        val replayId = id ?: return true
        val duration = java.time.Duration.between(startedAt, Instant.now()).toMillis().coerceAtLeast(0)
        return try { api("/api/v1/replay-sessions/$replayId/complete", JsonObject(mapOf(
            "ended_at" to JsonPrimitive(Instant.now().toString()), "duration_ms" to JsonPrimitive(duration)))); true } catch (_: Exception) { false }
    }

    private suspend fun api(path: String, body: JsonObject): Pair<Int, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url((baseUrl + path).toHttpUrl()).header("Authorization", "Bearer $sdkKey")
            .post(json.encodeToString(body).toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val content = response.body?.string().orEmpty()
            if (!response.isSuccessful && response.code != 204) error("FlagDash replay HTTP ${response.code}")
            response.code to content
        }
    }

    override fun close() {
        lifecycleOwner?.lifecycle?.removeObserver(this)
        scope.cancel()
        if (ownsHttp) http.dispatcher.executorService.shutdown()
    }

    companion object {
        private val sensitive = Regex("pass(word)?|secret|token|authorization|cookie|session|api[-_]?key|credit|card|cvv|cvc|otp|ssn", RegexOption.IGNORE_CASE)
        private fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
        private fun sanitizeMap(value: Map<String, Any?>, depth: Int = 0): JsonObject = JsonObject(value.entries.take(500).associate { (key, item) ->
            key to if (sensitive.containsMatchIn(key)) JsonPrimitive("[REDACTED]") else sanitize(item, depth + 1) })
        private fun sanitize(value: Any?, depth: Int): JsonElement {
            if (depth > 8) return JsonPrimitive("[REDACTED]")
            return when (value) {
                null -> JsonNull; is Boolean -> JsonPrimitive(value); is Number -> JsonPrimitive(value)
                is String -> JsonPrimitive(value.take(2_000)); is Map<*, *> -> sanitizeMap(value.entries.associate { it.key.toString() to it.value }, depth)
                is Iterable<*> -> JsonArray(value.take(500).map { sanitize(it, depth + 1) })
                else -> JsonPrimitive("[REDACTED]")
            }
        }
    }
}
