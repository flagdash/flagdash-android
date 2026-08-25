package com.flagdash.sdk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

data class EvaluationContext(
    val userId: String? = null,
    val unitId: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

data class FlagDetail(
    val key: String,
    val value: JsonElement,
    val reason: String,
    val variationKey: String? = null,
)

@Serializable internal data class FlagWire(
    val key: String,
    val value: JsonElement,
    val reason: String = "default",
    @SerialName("variation_key") val variationKey: String? = null,
)
@Serializable internal data class FlagsEnvelope(val flags: Map<String, JsonElement> = emptyMap())
@Serializable data class ConfigEnvelope(val key: String? = null, val value: JsonElement)
@Serializable internal data class ConfigsEnvelope(val configs: List<ConfigEnvelope> = emptyList())
@Serializable internal data class AiConfigEnvelope(@SerialName("ai_config") val aiConfig: Map<String, JsonElement>)
@Serializable internal data class AiConfigsEnvelope(@SerialName("ai_configs") val aiConfigs: List<Map<String, JsonElement>> = emptyList())
@Serializable internal data class CatalogEnvelope(val catalog: Catalog)
@Serializable internal data class Catalog(val messages: Map<String, String> = emptyMap())
@Serializable internal data class ExperimentEnvelope(val experiment: Map<String, JsonElement>)
