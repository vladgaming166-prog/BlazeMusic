package com.blazemuzix.app.cloud

import com.blazemuzix.app.network.ApiException
import com.blazemuzix.app.network.HttpClient
import org.json.JSONArray
import org.json.JSONObject

/** Thin REST wrapper for the public Supabase URL + anon key. No service-role key. */
class CloudClient(
    private val http: HttpClient,
    private val accessToken: () -> String?
) {
    val configured: Boolean get() = CloudConfig.isConfigured

    fun requireConfigured() {
        if (!configured) throw ApiException.NotConfigured("BlazeMuzix Cloud")
    }

    fun headers(json: Boolean = true, user: Boolean = true): Map<String, String> {
        val token = if (user) accessToken()?.takeIf { it.isNotBlank() } else null
        val bearer = token ?: CloudConfig.anonKey
        val map = HashMap<String, String>()
        map["apikey"] = CloudConfig.anonKey
        map["Authorization"] = "Bearer $bearer"
        if (json) {
            map["Content-Type"] = "application/json"
            map["Prefer"] = "return=representation"
        }
        return map
    }

    suspend fun getObject(path: String, cacheTtlMs: Long = 0L): JSONObject {
        requireConfigured()
        return http.getJson(CloudConfig.url + path, headers(user = true), cacheTtlMs)
    }

    suspend fun getArray(path: String, cacheTtlMs: Long = 0L): JSONArray {
        requireConfigured()
        return http.getArray(CloudConfig.url + path, headers(user = true), cacheTtlMs)
    }

    suspend fun post(path: String, body: JSONObject): JSONObject {
        requireConfigured()
        return http.postJson(CloudConfig.url + path, headers(user = true), body)
    }

    suspend fun patch(path: String, body: JSONObject): JSONObject {
        requireConfigured()
        return http.patchJson(CloudConfig.url + path, headers(user = true), body)
    }

    suspend fun delete(path: String) {
        requireConfigured()
        http.delete(CloudConfig.url + path, headers(user = true))
    }

    suspend fun upload(bucket: String, objectPath: String, bytes: ByteArray, contentType: String, onProgress: ((Int) -> Unit)?): String {
        requireConfigured()
        val url = "${CloudConfig.url}/storage/v1/object/$bucket/$objectPath"
        val hdr = HashMap(headers(json = false, user = true))
        hdr["x-upsert"] = "true"
        hdr["Content-Type"] = contentType
        http.putBytes(url, hdr, bytes, contentType, onProgress)
        return "${CloudConfig.url}/storage/v1/object/public/$bucket/$objectPath"
    }

    fun firstObject(json: JSONObject): JSONObject? {
        val items = json.optJSONArray("items")
        if (items != null && items.length() > 0) return items.optJSONObject(0)
        return if (json.has("id")) json else null
    }
}
