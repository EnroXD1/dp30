package ru.tulsu.dp30

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

class ApiError(val status: Int, val detail: JSONObject) :
    Exception(detail.optString("message", "Сервер вернул ошибку $status."))

fun json(vararg fields: Pair<String, Any?>) = JSONObject().apply {
    for ((key, value) in fields) put(key, value ?: JSONObject.NULL)
}

fun JSONObject.optionalLong(key: String): Long? = if (isNull(key)) null else getLong(key)
fun JSONObject.optionalText(key: String): String? = if (isNull(key)) null else getString(key)
fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
fun versioned(version: Int) = json("operation_id" to UUID.randomUUID().toString(), "expected_version" to version)
fun queryOf(fields: Map<String, String>) = fields.filterValues { it.isNotBlank() }
    .entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }

class Api(rawBase: String, private val token: String? = null) {
    private val base = rawBase.trim().trimEnd('/').also {
        val uri = runCatching { URI(it) }.getOrNull()
        require(uri?.scheme == "https" && uri.host != null && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.path.isEmpty()) {
            "Укажите HTTPS-адрес сервера без пути, например https://10.0.2.2:8443."
        }
    }

    suspend fun call(path: String, method: String = "GET", body: JSONObject? = null): Any =
        withContext(Dispatchers.IO) {
            val connection = URI("$base/api/v1$path").toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 4000
                connection.readTimeout = 6500
                connection.setRequestProperty("Accept", "application/json")
                token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    val payload = body.toString().toByteArray(Charsets.UTF_8)
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setFixedLengthStreamingMode(payload.size)
                    connection.outputStream.use { it.write(payload) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val detail = runCatching { JSONObject(text).getJSONObject("detail") }.getOrElse {
                        json("message" to "Сервер вернул ошибку $status.")
                    }
                    throw ApiError(status, detail)
                }
                try {
                    JSONTokener(text).nextValue()
                } catch (error: Exception) {
                    throw IOException("Не удалось прочитать ответ сервера.", error)
                }
            } finally {
                connection.disconnect()
            }
        }

    suspend fun obj(path: String, method: String = "GET", body: JSONObject? = null) =
        call(path, method, body) as JSONObject

    suspend fun array(path: String) = (call(path) as JSONArray).objects()
}
