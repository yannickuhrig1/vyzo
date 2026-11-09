package fr.yannickuhrig.vyzo.util

import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeToSequence
import java.io.BufferedInputStream
import java.io.InputStream

val LenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
fun JsonElement?.asArr(): JsonArray? = this as? JsonArray

fun JsonElement?.prim(): String? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content
}

fun JsonObject.str(key: String): String = this[key].prim()?.trim().orEmpty()

/** Premier élément texte non vide d'un tableau, ou la valeur directe. */
fun JsonObject.strOrFirst(key: String): String = when (val v = this[key]) {
    is JsonArray -> v.firstNotNullOfOrNull { it.prim()?.takeIf { s -> s.isNotBlank() } }.orEmpty()
    else -> v.prim().orEmpty()
}

fun JsonObject.int(key: String): Int {
    val s = str(key)
    return s.toIntOrNull() ?: s.toDoubleOrNull()?.toInt() ?: 0
}

fun JsonObject.long(key: String): Long {
    val s = str(key)
    return s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong() ?: 0L
}

fun JsonObject.float(key: String): Float = str(key).replace(',', '.').toFloatOrNull() ?: 0f

fun JsonObject.bool(key: String): Boolean {
    val s = str(key).lowercase()
    return s == "1" || s == "true" || s == "yes"
}

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/**
 * Parcourt un gros tableau JSON élément par élément sans le charger entièrement.
 * Si la racine est un objet (certains panels renvoient {"id": {...}}), on itère sur ses valeurs.
 */
fun streamJsonObjects(input: InputStream, onItem: (JsonObject) -> Unit) {
    val bin = if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024)
    bin.mark(64)
    var first: Int
    do {
        first = bin.read()
    } while (first != -1 && (first.toChar().isWhitespace() || first == 0xEF || first == 0xBB || first == 0xBF))
    bin.reset()
    when (first.toChar()) {
        '[' -> LenientJson.decodeToSequence(bin, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)
            .forEach { (it as? JsonObject)?.let(onItem) }
        '{' -> {
            val root = LenientJson.decodeFromString(JsonElement.serializer(), bin.readBytes().decodeToString())
            (root as? JsonObject)?.values?.forEach { v ->
                when (v) {
                    is JsonObject -> onItem(v)
                    is JsonArray -> v.forEach { (it as? JsonObject)?.let(onItem) }
                    else -> Unit
                }
            }
        }
        else -> Unit
    }
}
