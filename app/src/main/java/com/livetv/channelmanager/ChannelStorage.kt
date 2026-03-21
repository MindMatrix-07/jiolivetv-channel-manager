package com.livetv.channelmanager

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ChannelStorage {

    private const val PREFS = "channel_prefs"
    private const val KEY = "channels"

    fun saveChannels(context: Context, channels: List<Channel>) {
        val arr = JSONArray()
        for (ch in channels) {
            arr.put(JSONObject().apply {
                put("id", ch.id)
                put("name", ch.name)
                put("channelNumber", ch.channelNumber)
                put("streamUrl", ch.streamUrl)
                put("logoUrl", ch.logoUrl)
                put("epgId", ch.epgId)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun loadChannels(context: Context): MutableList<Channel> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(json)
        val list = mutableListOf<Channel>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                Channel(
                    id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                    name = obj.getString("name"),
                    channelNumber = obj.getInt("channelNumber"),
                    streamUrl = obj.getString("streamUrl"),
                    logoUrl = obj.optString("logoUrl", ""),
                    epgId = obj.optString("epgId", "")
                )
            )
        }
        return list.sortedBy { it.channelNumber }.toMutableList()
    }

    /** Export channels as channels.json to Downloads folder */
    fun exportToFile(context: Context, channels: List<Channel>): Boolean {
        return try {
            // Build proxy-compatible JSON format
            val result = JSONArray()
            for (ch in channels) {
                result.put(JSONObject().apply {
                    put("channel_id", ch.channelNumber)
                    put("channel_name", ch.name)
                    put("channel_url", ch.streamUrl)
                    put("logoUrl", ch.logoUrl)
                    put("channelLanguageId", 7)
                })
            }
            val root = JSONObject().put("result", result)
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloads, "channels.json")
            file.writeText(root.toString(2))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
