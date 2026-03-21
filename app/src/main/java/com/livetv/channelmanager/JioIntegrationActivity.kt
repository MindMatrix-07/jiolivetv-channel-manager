package com.livetv.channelmanager

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.livetv.channelmanager.databinding.ActivityJioIntegrationBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Jio TV Integration — same flow as JioTV-Go/jiotv_go and jiotv_go_app.
 * 1. Login with Jio (store session in DB via backend)
 * 2. Fetch Jio channel list
 * 3. Add selected channels to your TV (get stream URL, insert into Supabase)
 */
class JioIntegrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityJioIntegrationBinding

    private val apiBase: String
        get() = LiveTvBackend.API_BASE

    /** JSON null / missing keys must not show as the literal "null" in the UI. */
    private fun humanReadableApiError(json: JSONObject, body: String, httpCode: Int): String {
        fun fromKey(key: String): String? {
            if (!json.has(key)) return null
            val raw = json.optString(key, "")
            if (raw.isBlank() || raw.equals("null", ignoreCase = true)) return null
            return raw
        }
        val main = fromKey("detail")
            ?: fromKey("error")
            ?: body.trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            ?: "Request failed (HTTP $httpCode). Check Render logs and SUPABASE_SERVICE_ROLE on Render."
        val hint = fromKey("hint")
        return if (hint != null) "$main\n\n$hint" else main
    }

    private fun humanReadableException(e: Exception): String =
        e.message?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            ?: e.javaClass.simpleName

    private val jioChannels = mutableListOf<JioChannel>()
    private lateinit var jioAdapter: JioChannelAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJioIntegrationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.btnSendOtp.setOnClickListener { doSendOtp() }
        binding.btnJioLogin.setOnClickListener { doVerifyOtp() }
        binding.btnFetchJioChannels.setOnClickListener { fetchJioChannels() }

        jioAdapter = JioChannelAdapter(jioChannels) { ch -> addJioChannelToTv(ch) }
        binding.rvJioChannels.layoutManager = LinearLayoutManager(this)
        binding.rvJioChannels.adapter = jioAdapter
    }

    /** Send OTP to Jio number — POST /api/jio-login with { phoneNumber } (same flow as JioTV-Go/jiotv_go) */
    private fun doSendOtp() {
        val phone = binding.etJioUser.text?.toString()?.trim()?.replace(Regex("[^0-9]"), "")?.takeLast(10) ?: ""
        if (phone.length != 10) {
            Toast.makeText(this, "Enter a valid 10-digit Jio mobile number", Toast.LENGTH_SHORT).show()
            binding.tilJioUser.error = "10 digits required"
            return
        }
        binding.tilJioUser.error = null
        binding.tvJioLoginStatus.visibility = View.VISIBLE
        binding.tvJioLoginStatus.text = "Sending OTP..."

        lifecycleScope.launch {
            try {
                val conn = withContext(Dispatchers.IO) {
                    URL("$apiBase/jio-login").openConnection() as HttpsURLConnection
                }
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.outputStream.use { out ->
                    out.write(JSONObject().apply { put("phoneNumber", phone) }.toString().toByteArray(Charsets.UTF_8))
                }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
                val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                withContext(Dispatchers.Main) {
                    if (code in 200..299) {
                        binding.tvJioLoginStatus.text = "✅ OTP sent. Enter the code and tap Verify."
                        binding.tvJioLoginStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                    } else {
                        val msg = humanReadableApiError(json, body, code)
                        binding.tvJioLoginStatus.text = "❌ $msg"
                        binding.tvJioLoginStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvJioLoginStatus.text = "❌ ${humanReadableException(e)}"
                    binding.tvJioLoginStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                }
                Log.e("JioIntegration", "Send OTP failed", e)
            }
        }
    }

    /** Verify OTP and store session — POST /api/jio-login?verify=true with { phoneNumber, otp } */
    private fun doVerifyOtp() {
        val phone = binding.etJioUser.text?.toString()?.trim()?.replace(Regex("[^0-9]"), "")?.takeLast(10) ?: ""
        val otp = binding.etJioOtp.text?.toString()?.trim() ?: ""
        if (phone.length != 10) {
            Toast.makeText(this, "Enter a valid 10-digit Jio number", Toast.LENGTH_SHORT).show()
            return
        }
        if (otp.isEmpty()) {
            Toast.makeText(this, "Enter OTP then tap Verify", Toast.LENGTH_SHORT).show()
            binding.tilJioOtp.error = "Enter OTP"
            return
        }
        binding.tilJioOtp.error = null
        binding.tvJioLoginStatus.visibility = View.VISIBLE
        binding.tvJioLoginStatus.text = "Verifying OTP..."

        lifecycleScope.launch {
            try {
                val conn = withContext(Dispatchers.IO) {
                    URL("$apiBase/jio-login?verify=true").openConnection() as HttpsURLConnection
                }
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.outputStream.use { out ->
                    out.write(JSONObject().apply {
                        put("phoneNumber", phone)
                        put("otp", otp)
                    }.toString().toByteArray(Charsets.UTF_8))
                }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
                val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                withContext(Dispatchers.Main) {
                    if (code in 200..299) {
                        binding.tvJioLoginStatus.text = "✅ Session stored. You can fetch channels."
                        binding.tvJioLoginStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                    } else {
                        val msg = humanReadableApiError(json, body, code)
                        binding.tvJioLoginStatus.text = "❌ $msg"
                        binding.tvJioLoginStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvJioLoginStatus.text = "❌ ${humanReadableException(e)}"
                    binding.tvJioLoginStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                }
                Log.e("JioIntegration", "Verify OTP failed", e)
            }
        }
    }

    private fun fetchJioChannels() {
        binding.progressJio.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val conn = withContext(Dispatchers.IO) {
                    URL("$apiBase/jio-channels").openConnection() as HttpsURLConnection
                }
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.connect()
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val arr = json.optJSONArray("channels") ?: json.optJSONArray("result") ?: org.json.JSONArray()
                val list = mutableListOf<JioChannel>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list.add(
                        JioChannel(
                            id = o.optString("channel_id", o.optString("id", "")),
                            name = o.optString("channelName", o.optString("channel_name", o.optString("name", "?"))),
                            logoUrl = o.optString("logoUrl", o.optString("logo_url", ""))
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    jioChannels.clear()
                    jioChannels.addAll(list)
                    jioAdapter.notifyDataSetChanged()
                    binding.progressJio.visibility = View.GONE
                    Toast.makeText(this@JioIntegrationActivity, "Loaded ${list.size} channels", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressJio.visibility = View.GONE
                    Toast.makeText(this@JioIntegrationActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
                Log.e("JioIntegration", "Fetch channels failed", e)
            }
        }
    }

    private fun addJioChannelToTv(ch: JioChannel) {
        val channelId = ch.id.toIntOrNull() ?: return
        lifecycleScope.launch {
            try {
                val streamUrl = withContext(Dispatchers.IO) {
                    val conn = URL("$apiBase/jio-stream-url?channelId=$channelId").openConnection() as HttpsURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.connect()
                    val body = conn.inputStream.bufferedReader().readText()
                    JSONObject(body).optString("streamUrl")
                }
                if (streamUrl.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@JioIntegrationActivity, "No stream URL for ${ch.name}", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                val channel = Channel(
                    name = ch.name,
                    channelNumber = channelId,
                    streamUrl = streamUrl,
                    logoUrl = ch.logoUrl,
                    epgId = ch.id
                )
                val ok = SupabaseSync.upsertChannel(channel)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@JioIntegrationActivity,
                        if (ok) "✅ \"${ch.name}\" added to TV!" else "⚠️ Supabase sync failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@JioIntegrationActivity, "Add failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
                Log.e("JioIntegration", "Add channel failed", e)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

data class JioChannel(val id: String, val name: String, val logoUrl: String)

class JioChannelAdapter(
    private val items: List<JioChannel>,
    private val onAdd: (JioChannel) -> Unit
) : RecyclerView.Adapter<JioChannelAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvJioChannelName)
        val id: TextView = v.findViewById(R.id.tvJioChannelId)
        val logo: ImageView = v.findViewById(R.id.ivJioLogo)
        val add: MaterialButton = v.findViewById(R.id.btnAddToChannels)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_jio_channel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.name.text = ch.name
        holder.id.text = "ID: ${ch.id}"
        holder.add.setOnClickListener { onAdd(ch) }
        holder.logo.setImageDrawable(null)
        holder.logo.visibility = if (ch.logoUrl.isNotBlank()) View.VISIBLE else View.GONE
        if (ch.logoUrl.isNotBlank()) {
            Thread {
                try {
                    val bmp = URL(ch.logoUrl).openStream().use { BitmapFactory.decodeStream(it) }
                    holder.logo.post { holder.logo.setImageBitmap(bmp) }
                } catch (_: Exception) {}
            }.start()
        }
    }

    override fun getItemCount() = items.size
}
