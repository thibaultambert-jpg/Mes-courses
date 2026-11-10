package fr.mescourses.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Un outil que le LLM local peut appeler. Indépendant du moteur d'inférence :
 * l'app l'adapte en `OpenApiTool` pour LiteRT-LM (ou autre moteur plus tard).
 */
interface AgentTool {
    val name: String
    val description: String
    /** Schéma JSON (type object) des paramètres. */
    val parameters: JsonObject
    suspend fun execute(args: JsonObject): JsonElement

    /** Description au format attendu par LiteRT-LM / OpenAPI function calling. */
    fun openApiJson(): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", parameters)
        },
    )
}

/** Petit DSL pour décrire les paramètres d'un outil. */
class ParamsBuilder {
    private val props = LinkedHashMap<String, JsonObject>()
    private val required = mutableListOf<String>()

    fun string(name: String, description: String, required: Boolean = true, enum: List<String>? = null) =
        add(name, required, buildJsonObject {
            put("type", "string"); put("description", description)
            enum?.let { values -> putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } } }
        })

    fun integer(name: String, description: String, required: Boolean = true) =
        add(name, required, buildJsonObject { put("type", "integer"); put("description", description) })

    fun stringArray(name: String, description: String, required: Boolean = true) =
        add(name, required, buildJsonObject {
            put("type", "array"); put("description", description)
            putJsonObject("items") { put("type", "string") }
        })

    fun integerArray(name: String, description: String, required: Boolean = true) =
        add(name, required, buildJsonObject {
            put("type", "array"); put("description", description)
            putJsonObject("items") { put("type", "integer") }
        })

    private fun add(name: String, isRequired: Boolean, schema: JsonObject) {
        props[name] = schema
        if (isRequired) required += name
    }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    }
}

fun params(block: ParamsBuilder.() -> Unit): JsonObject = ParamsBuilder().apply(block).build()

// --- Lecture tolérante des arguments produits par un petit modèle ---

fun JsonObject.str(key: String): String? = when (val v = this[key]) {
    is JsonPrimitive -> v.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    else -> null
}

fun JsonObject.int(key: String): Int? = when (val v = this[key]) {
    is JsonPrimitive -> v.intOrNull ?: v.contentOrNull?.trim()?.toDoubleOrNull()?.toInt()
    else -> null
}

fun JsonObject.long(key: String): Long? = when (val v = this[key]) {
    is JsonPrimitive -> v.longOrNull ?: v.contentOrNull?.trim()?.toDoubleOrNull()?.toLong()
    else -> null
}

/** Accepte un vrai tableau JSON, ou une chaîne « a, b, c » / « a\nb ». */
fun JsonObject.strList(key: String): List<String> = when (val v = this[key]) {
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotEmpty() }
    is JsonPrimitive -> v.contentOrNull.orEmpty().split('\n', ';', ',').map { it.trim() }.filter { it.isNotEmpty() }
    else -> emptyList()
}

/** Liste de nombres : vrai tableau, nombres en texte, ou chaîne « 1, 4 et 7 ». */
fun JsonObject.intList(key: String): List<Int> = when (val v = this[key]) {
    is JsonArray -> v.flatMap { e -> (e as? JsonPrimitive)?.contentOrNull?.let { numbersIn(it) } ?: emptyList() }
    is JsonPrimitive -> numbersIn(v.contentOrNull.orEmpty())
    else -> emptyList()
}

private fun numbersIn(text: String): List<Int> = Regex("""\d+""").findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()

fun toolOk(message: String, data: JsonElement? = null): JsonObject = buildJsonObject {
    put("ok", true)
    put("message", message)
    if (data != null) put("donnees", data)
}

fun toolError(message: String): JsonObject = buildJsonObject { put("ok", false); put("erreur", message) }
