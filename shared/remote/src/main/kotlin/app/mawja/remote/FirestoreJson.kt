/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/**
 * Reads Firestore REST typed values ({"booleanValue": true}, {"integerValue": "5"}, {"mapValue":
 * {"fields": {...}}}, {"timestampValue": "...Z"}, {"nullValue": null}) leniently: a wrong type reads
 * as null, never throws.
 */
internal object FirestoreJson {
    fun fields(document: JsonElement?): Map<String, JsonElement> = ((document as? JsonObject)?.get("fields") as? JsonObject).orEmpty()

    private fun value(
        v: JsonElement?,
        key: String,
    ): JsonPrimitive? = ((v as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }

    fun string(v: JsonElement?): String? = value(v, "stringValue")?.contentOrNull

    fun bool(v: JsonElement?): Boolean? = value(v, "booleanValue")?.booleanOrNull

    fun long(v: JsonElement?): Long? =
        value(v, "integerValue")?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
            ?: value(v, "doubleValue")?.doubleOrNull?.toLong()

    /** Epoch millis of a timestampValue (or a plain integer, taken as millis). */
    fun millis(v: JsonElement?): Long? =
        value(v, "timestampValue")?.contentOrNull?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: long(v)

    fun map(v: JsonElement?): Map<String, JsonElement> = (((v as? JsonObject)?.get("mapValue") as? JsonObject)?.get("fields") as? JsonObject).orEmpty()

    /** The last path segment of a document "name" (projects/.../documents/announcements/{id}). */
    fun docId(document: JsonElement?): String? =
        ((document as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    /** Documents of a :runQuery response (an array of {document?, readTime}). */
    fun queryDocuments(response: JsonElement?): List<JsonElement> =
        (response as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("document") }
}
