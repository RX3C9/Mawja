/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * A small Firestore REST client, authenticated as the signed-in user with their Firebase ID token,
 * so every call goes through the security rules. The app carries no Firestore SDK (its protolite
 * clashes with the app's protobuf-javalite), and the REST API needs no Cloud Functions, so this
 * works on the free Spark plan. Shared by the github flavor's account backend and cloud sync; foss
 * builds never construct it (they have no ID token).
 */
internal class FirestoreRest(
    private val projectId: String,
    private val token: suspend () -> String?,
) {
    class HttpError(
        val code: Int,
        val status: String,
        message: String,
    ) : IOException("$code $status $message")

    /** A document as plain values: String, Long, Double, Boolean, Instant (timestamps), null, List, Map. */
    data class Doc(
        val path: String,
        val fields: Map<String, Any?>,
    )

    /** One batchGet result: the docs that exist by path, and the server time of the read. */
    data class Snapshot(
        val docs: Map<String, Doc>,
        val readTime: Instant,
    )

    sealed interface Write

    /** Writes [fields] (only those keys) and sets [serverTimeFields] to the request time. */
    data class Update(
        val path: String,
        val fields: Map<String, Any?>,
        val serverTimeFields: List<String> = emptyList(),
        val mustExist: Boolean? = null,
    ) : Write

    /** Deletes the document at [path] (no error when it is already gone). */
    data class Delete(
        val path: String,
    ) : Write

    private val root = "projects/$projectId/databases/(default)/documents"
    private val base = "https://firestore.googleapis.com/v1/$root"
    private val json = "application/json; charset=utf-8".toMediaType()

    fun name(path: String) = "$root/$path"

    suspend fun batchGet(
        paths: List<String>,
        transaction: String? = null,
    ): Snapshot {
        val body = JSONObject().put("documents", JSONArray(paths.map(::name)))
        if (transaction != null) body.put("transaction", transaction)
        val array = JSONArray(post(":batchGet", body))
        val docs = mutableMapOf<String, Doc>()
        var readTime: Instant? = null
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            item.optString("readTime").takeIf { it.isNotEmpty() }?.let { readTime = Instant.parse(it) }
            val found = item.optJSONObject("found") ?: continue
            val path = found.getString("name").removePrefix("$root/")
            docs[path] = Doc(path, decodeFields(found.optJSONObject("fields")))
        }
        return Snapshot(docs, readTime ?: Instant.now())
    }

    /**
     * Runs a structured query on the collection [collectionId] under [parentPath] ("" for the root,
     * "users/{uid}" for a user's subcollection). Returns the documents in query order.
     */
    suspend fun runQuery(
        parentPath: String,
        collectionId: String,
        where: JSONObject? = null,
        orderBy: List<String> = emptyList(),
        startAfter: List<Any?>? = null,
        limit: Int? = null,
    ): List<Doc> {
        val q = JSONObject().put("from", JSONArray().put(JSONObject().put("collectionId", collectionId)))
        if (where != null) q.put("where", where)
        if (orderBy.isNotEmpty()) {
            q.put(
                "orderBy",
                JSONArray(orderBy.map { JSONObject().put("field", JSONObject().put("fieldPath", it)).put("direction", "ASCENDING") }),
            )
        }
        if (startAfter != null) {
            q.put("startAt", JSONObject().put("before", false).put("values", JSONArray(startAfter.map { encodeValue(it) })))
        }
        if (limit != null) q.put("limit", limit)
        val verb = (if (parentPath.isEmpty()) "" else "/$parentPath") + ":runQuery"
        val array = JSONArray(post(verb, JSONObject().put("structuredQuery", q)))
        val out = ArrayList<Doc>(array.length())
        for (i in 0 until array.length()) {
            val document = array.getJSONObject(i).optJSONObject("document") ?: continue
            val path = document.getString("name").removePrefix("$root/")
            out += Doc(path, decodeFields(document.optJSONObject("fields")))
        }
        return out
    }

    /** A document reference value for cursors on `__name__`. */
    data class Ref(
        val path: String,
    )

    /** `field >= value` filter for [runQuery]. */
    fun fieldAtLeast(
        field: String,
        value: Any?,
    ): JSONObject =
        JSONObject().put(
            "fieldFilter",
            JSONObject()
                .put("field", JSONObject().put("fieldPath", field))
                .put("op", "GREATER_THAN_OR_EQUAL")
                .put("value", encodeValue(value)),
        )

    /** `field == value` filter for [runQuery] (single-field index, no composite index needed). */
    fun fieldEquals(
        field: String,
        value: Any?,
    ): JSONObject =
        JSONObject().put(
            "fieldFilter",
            JSONObject()
                .put("field", JSONObject().put("fieldPath", field))
                .put("op", "EQUAL")
                .put("value", encodeValue(value)),
        )

    suspend fun beginTransaction(): String = JSONObject(post(":beginTransaction", JSONObject().put("options", JSONObject().put("readWrite", JSONObject())))).getString("transaction")

    suspend fun rollback(transaction: String) {
        runCatching { post(":rollback", JSONObject().put("transaction", transaction)) }
    }

    suspend fun commit(
        writes: List<Write>,
        transaction: String? = null,
    ) {
        val array = JSONArray()
        writes.forEach { w ->
            when (w) {
                is Update -> {
                    val write = JSONObject()
                    write.put("update", JSONObject().put("name", name(w.path)).put("fields", encodeFields(w.fields)))
                    write.put("updateMask", JSONObject().put("fieldPaths", JSONArray(w.fields.keys.toList())))
                    if (w.serverTimeFields.isNotEmpty()) {
                        write.put(
                            "updateTransforms",
                            JSONArray(w.serverTimeFields.map { JSONObject().put("fieldPath", it).put("setToServerValue", "REQUEST_TIME") }),
                        )
                    }
                    w.mustExist?.let { write.put("currentDocument", JSONObject().put("exists", it)) }
                    array.put(write)
                }

                is Delete -> array.put(JSONObject().put("delete", name(w.path)))
            }
        }
        val body = JSONObject().put("writes", array)
        if (transaction != null) body.put("transaction", transaction)
        post(":commit", body)
    }

    private suspend fun post(
        verb: String,
        body: JSONObject,
    ): String {
        val idToken = token() ?: throw HttpError(401, "UNAUTHENTICATED", "not signed in")
        val request =
            Request
                .Builder()
                .url(base + verb)
                .header("Authorization", "Bearer $idToken")
                .post(body.toString().toRequestBody(json))
                .build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    val error = runCatching { JSONObject(text).getJSONObject("error") }.getOrNull()
                    throw HttpError(response.code, error?.optString("status").orEmpty(), error?.optString("message").orEmpty())
                }
                text
            }
        }
    }

    companion object {
        private val client: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        fun decodeFields(fields: JSONObject?): Map<String, Any?> {
            if (fields == null) return emptyMap()
            val out = LinkedHashMap<String, Any?>()
            fields.keys().forEach { key -> out[key] = decodeValue(fields.getJSONObject(key)) }
            return out
        }

        private fun decodeValue(v: JSONObject): Any? =
            when {
                v.has("stringValue") -> v.getString("stringValue")
                v.has("integerValue") -> v.getString("integerValue").toLongOrNull()
                v.has("doubleValue") -> v.getDouble("doubleValue")
                v.has("booleanValue") -> v.getBoolean("booleanValue")
                v.has("timestampValue") -> runCatching { Instant.parse(v.getString("timestampValue")) }.getOrNull()
                v.has("mapValue") -> decodeFields(v.getJSONObject("mapValue").optJSONObject("fields"))
                v.has("arrayValue") -> {
                    val values = v.getJSONObject("arrayValue").optJSONArray("values") ?: JSONArray()
                    (0 until values.length()).map { decodeValue(values.getJSONObject(it)) }
                }
                else -> null
            }

        fun encodeFields(fields: Map<String, Any?>): JSONObject {
            val out = JSONObject()
            fields.forEach { (key, value) -> out.put(key, encodeValue(value)) }
            return out
        }

        private fun encodeValue(value: Any?): JSONObject =
            when (value) {
                null -> JSONObject().put("nullValue", JSONObject.NULL)
                is Ref -> JSONObject().put("referenceValue", value.path)
                is String -> JSONObject().put("stringValue", value)
                is Int -> JSONObject().put("integerValue", value.toString())
                is Long -> JSONObject().put("integerValue", value.toString())
                is Double -> JSONObject().put("doubleValue", value)
                is Boolean -> JSONObject().put("booleanValue", value)
                is Instant -> JSONObject().put("timestampValue", value.toString())
                is Map<*, *> ->
                    JSONObject().put(
                        "mapValue",
                        JSONObject().put("fields", encodeFields(value.entries.associate { (k, v) -> k.toString() to v })),
                    )
                is List<*> -> JSONObject().put("arrayValue", JSONObject().put("values", JSONArray(value.map { encodeValue(it) })))
                else -> JSONObject().put("stringValue", value.toString())
            }
    }
}
