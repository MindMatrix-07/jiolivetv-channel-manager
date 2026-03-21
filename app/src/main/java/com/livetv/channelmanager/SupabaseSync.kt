package com.livetv.channelmanager

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Direct Supabase REST API integration.
 * All channel edits made here immediately appear on the live TV at jiolivetv.vercel.app
 */
object SupabaseSync {

    private const val SUPABASE_URL = "https://rpdtsuvgqwabsswtnrpq.supabase.co"
    private const val SUPABASE_KEY = "sb_publishable_bVDGjrCQAGfOD9jULabjfQ_-A5CINb0"
    private const val TAG = "SupabaseSync"
    @Volatile var lastError: String? = null
        private set

    // ── Fetch all channels from Supabase ──
    suspend fun fetchChannels(): List<Channel> = withContext(Dispatchers.IO) {
        try {
            val conn = get("$SUPABASE_URL/rest/v1/channels?select=*&order=channel_id.asc")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                lastError = "Fetch failed: HTTP $code ${body.take(200)}"
                Log.e(TAG, lastError!!)
                return@withContext emptyList()
            }
            val arr = JSONArray(body)
            val list = mutableListOf<Channel>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    Channel(
                        id            = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        name          = obj.getString("channel_name"),
                        channelNumber = obj.getInt("channel_id"),
                        streamUrl     = obj.getString("channel_url"),
                        logoUrl       = obj.optString("logo_url", ""),
                        epgId         = obj.optString("epg_id", ""),
                        epgUrl        = obj.optString("epg_url", "")
                    )
                )
            }
            lastError = null
            list
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "fetchChannels failed", e)
            emptyList()
        }
    }

    // ── Upsert (add or update) one channel ──
    suspend fun upsertChannel(channel: Channel): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = channelToJson(channel).toString()
            val conn = URL("$SUPABASE_URL/rest/v1/channels?on_conflict=channel_id").openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("apikey", SUPABASE_KEY)
                setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Prefer", "resolution=merge-duplicates,return=representation")
                connectTimeout = 8000; readTimeout = 8000
            }
            OutputStreamWriter(conn.outputStream).use { it.write(json) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText().orEmpty()
            val ok = code in 200..299
            if (ok) {
                lastError = null
                Log.d(TAG, "upsert ${channel.name}: HTTP $code ${body.take(120)}")
            } else {
                lastError = "Upsert failed: HTTP $code ${body.take(200)}"
                Log.e(TAG, lastError!!)
            }
            ok
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "upsertChannel failed", e)
            false
        }
    }

    // ── Delete one channel ──
    suspend fun deleteChannel(channelId: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = URL("$SUPABASE_URL/rest/v1/channels?channel_id=eq.$channelId").openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "DELETE"
                setRequestProperty("apikey", SUPABASE_KEY)
                setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
                setRequestProperty("Prefer", "return=representation")
                connectTimeout = 8000; readTimeout = 8000
            }
            conn.connect()
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText().orEmpty()
            val ok = code in 200..299
            if (ok) {
                lastError = null
                Log.d(TAG, "delete CH $channelId: HTTP $code ${body.take(120)}")
            } else {
                lastError = "Delete failed: HTTP $code ${body.take(200)}"
                Log.e(TAG, lastError!!)
            }
            ok
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "deleteChannel failed", e)
            false
        }
    }

    // ── Fetch all shared EPG sources from Supabase ──
    suspend fun fetchEpgSources(): List<String> = withContext(Dispatchers.IO) {
        try {
            val conn = get("$SUPABASE_URL/rest/v1/epg_sources?select=url")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                lastError = "Fetch EPG sources failed: HTTP $code ${body.take(200)}"
                Log.e(TAG, lastError!!)
                return@withContext emptyList()
            }
            val arr = JSONArray(body)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                list.add(arr.getJSONObject(i).getString("url"))
            }
            lastError = null
            list
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "fetchEpgSources failed", e)
            emptyList()
        }
    }

    // ── Upsert multiple EPG sources ──
    suspend fun upsertEpgSources(urls: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) return@withContext true
        try {
            val json = JSONArray()
            for (url in urls) {
                json.put(JSONObject().put("url", url))
            }
            
            val conn = URL("$SUPABASE_URL/rest/v1/epg_sources?on_conflict=url").openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("apikey", SUPABASE_KEY)
                setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
                connectTimeout = 8000; readTimeout = 8000
            }
            OutputStreamWriter(conn.outputStream).use { it.write(json.toString()) }
            val code = conn.responseCode
            val ok = code in 200..299
            if (ok) {
                lastError = null
            } else {
                val body = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                lastError = "Upsert EPG sources failed: HTTP $code ${body.take(200)}"
                Log.e(TAG, lastError!!)
            }
            ok
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "upsertEpgSources failed", e)
            false
        }
    }

    // ─── helpers ───


    private fun channelToJson(ch: Channel) = JSONObject().apply {
        put("channel_id",          ch.channelNumber)
        put("channel_name",        ch.name)
        put("channel_url",         ch.streamUrl)
        put("logo_url",            ch.logoUrl)
        put("channel_language_id", 7)
        if (ch.epgId.isNotBlank()) {
            put("epg_id", ch.epgId)
        }
        if (ch.epgUrl.isNotBlank()) {
            put("epg_url", ch.epgUrl)
        }
    }

    private fun get(urlStr: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("apikey", SUPABASE_KEY)
        conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
        conn.connectTimeout = 8000; conn.readTimeout = 8000
        conn.connect()
        return conn
    }
}
