package com.livetv.channelmanager

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object ServerSync {

    private const val PREFS = "server_prefs"
    private const val KEY_IP = "server_ip"

    fun saveServerIp(context: Context, ip: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_IP, ip.trim()).apply()
    }

    fun getServerIp(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_IP, null)
    }

    /** Test connection to the server and return channelCount on success, null on failure */
    suspend fun testConnection(ip: String): Int? = withContext(Dispatchers.IO) {
        try {
            val conn = URL("http://$ip:3001/api/info").openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                JSONObject(body).optInt("channelCount", -1)
            } else null
        } catch (e: Exception) { null }
    }

    /** POST one channel (add or update) to the proxy */
    suspend fun pushChannel(context: Context, channel: Channel): Boolean = withContext(Dispatchers.IO) {
        val ip = getServerIp(context) ?: return@withContext false
        try {
            val json = channelToJson(channel).toString()
            post("http://$ip:3001/api/channels/add", json)
        } catch (e: Exception) { e.printStackTrace(); false }
    }

    /** DELETE one channel from the proxy */
    suspend fun deleteChannel(context: Context, channelId: Int): Boolean = withContext(Dispatchers.IO) {
        val ip = getServerIp(context) ?: return@withContext false
        try {
            val conn = URL("http://$ip:3001/api/channels/$channelId").openConnection() as HttpURLConnection
            conn.requestMethod = "DELETE"
            conn.connectTimeout = 5000
            conn.connect()
            conn.responseCode == 200
        } catch (e: Exception) { e.printStackTrace(); false }
    }

    /** POST entire channel list to replace server channels */
    suspend fun syncAll(context: Context, channels: List<Channel>): Boolean = withContext(Dispatchers.IO) {
        val ip = getServerIp(context) ?: return@withContext false
        try {
            val arr = JSONArray()
            channels.forEach { arr.put(channelToJson(it)) }
            post("http://$ip:3001/api/channels", arr.toString())
        } catch (e: Exception) { e.printStackTrace(); false }
    }

    private fun channelToJson(ch: Channel) = JSONObject().apply {
        put("channel_id", ch.channelNumber)
        put("channel_name", ch.name)
        put("channel_url", ch.streamUrl)
        put("logoUrl", ch.logoUrl)
        put("channelLanguageId", 7)
    }

    private fun post(urlStr: String, body: String): Boolean {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        OutputStreamWriter(conn.outputStream).use { it.write(body) }
        return conn.responseCode in 200..299
    }
}
