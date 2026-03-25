package com.livetv.channelmanager

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.livetv.channelmanager.databinding.ActivityAddChannelBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.coroutines.resume

class AddChannelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddChannelBinding
    private var editIndex = -1

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val dataUri = withContext(Dispatchers.IO) { encodePickedImageToDataUri(uri) }
            if (dataUri != null) {
                binding.etLogoUrl.setText(dataUri)
                binding.tilLogoUrl.helperText = "Custom image (saved in database as logo)"
                binding.cardCustomLogoFallback.visibility = View.GONE
                loadLogoPreview(dataUri)
                Toast.makeText(this@AddChannelActivity, "Logo image set", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@AddChannelActivity, "Could not read that image", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // 🔸 Render proxy: yt-stream, resolve-stream (same host as Jio API in LiveTvBackend)
    private val proxyUrl = LiveTvBackend.BASE_URL
    private val preferredAirtelHost = "allinonereborn.online"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddChannelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Pre-fill if editing
        val existing = intent.getParcelableExtra<Channel>("channel")
        editIndex = intent.getIntExtra("edit_index", -1)

        if (existing != null) {
            supportActionBar?.title = "Edit Channel"
            binding.etChannelName.setText(existing.name)
            binding.etChannelNumber.setText(existing.channelNumber.toString())
            val displayUrl = existing.streamUrl.replace("#direct", "")
            binding.etStreamUrl.setText(displayUrl)
            if (existing.streamUrl.endsWith("#direct")) {
                binding.switchDirectStream.isChecked = true
                binding.cardExtractor.visibility = View.GONE
                binding.tilStreamUrl.visibility = View.VISIBLE
            }
            binding.etLogoUrl.setText(existing.logoUrl)
            binding.etEpgId.setText(existing.epgId)
            binding.etEpgUrl.setText(existing.epgUrl)
        } else {
            supportActionBar?.title = "Add Channel"
        }

        // ─── Auto-fetch logo when channel name is typed ───
        val logoHandler = Handler(Looper.getMainLooper())
        var logoRunnable: Runnable? = null
        binding.etChannelName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                logoRunnable?.let { logoHandler.removeCallbacks(it) }
            }
            override fun afterTextChanged(s: Editable?) {
                val name = s?.toString()?.trim() ?: return
                if (name.length < 2) return
                // Skip if logo already filled
                if (binding.etLogoUrl.text?.isNotBlank() == true) return
                logoRunnable = Runnable {
                    lifecycleScope.launch {
                        val url = autoFetchLogo(name)
                        if (url != null && binding.etLogoUrl.text?.isBlank() != false) {
                            binding.etLogoUrl.setText(url)
                            binding.tilLogoUrl.helperText = "✅ Logo auto-fetched from tv-logo repo"
                        }
                    }
                }
                logoHandler.postDelayed(logoRunnable!!, 800)
            }
        })
        
        // ─── Render Logo Preview when Logo URL changes ───
        binding.etLogoUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val url = s?.toString()?.trim() ?: ""
                when {
                    url.startsWith("http") -> loadLogoPreview(url)
                    url.startsWith("data:image") -> loadLogoPreview(url)
                    else -> binding.ivLogoPreview.visibility = View.GONE
                }
            }
        })
        
        // If editing existing channel, trigger preview immediately
        if (existing?.logoUrl?.isNotBlank() == true) {
            loadLogoPreview(existing.logoUrl)
        }

        binding.btnPickLogoImage.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.switchDirectStream.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.cardExtractor.visibility = View.GONE
                binding.tilStreamUrl.visibility = View.VISIBLE
            } else {
                binding.cardExtractor.visibility = View.VISIBLE
                binding.tilStreamUrl.visibility = View.GONE
            }
        }

        // ─── Extract Stream Link ───
        binding.btnExtract.setOnClickListener {
            val raw = binding.etSourceUrl.text.toString().trim()
            val sourceUrl = normalizePastedSourceUrl(raw)
            if (sourceUrl.isEmpty()) {
                binding.tilSourceUrl.error = "Paste a source URL first"
                return@setOnClickListener
            }
            if (sourceUrl != raw) {
                binding.etSourceUrl.setText(sourceUrl)
            }
            binding.tilSourceUrl.error = null
            binding.btnExtract.isEnabled = false
            binding.tvExtractStatus.visibility = View.VISIBLE

            // ── YouTube detection ──
            if (isYouTubeUrl(sourceUrl)) {
                binding.tvExtractStatus.text = "⏳ Converting YouTube to M3U8..."
                binding.tvExtractStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
                
                lifecycleScope.launch {
                    val m3u8Url = convertYoutubeToM3u8(sourceUrl)
                    binding.btnExtract.isEnabled = true
                    if (m3u8Url != null) {
                        binding.etStreamUrl.setText(m3u8Url)
                        binding.tilStreamUrl.visibility = View.VISIBLE
                        binding.tvExtractStatus.text = "✅ Success! M3U8 URL extracted and filled below."
                        binding.tvExtractStatus.setTextColor(ContextCompat.getColor(this@AddChannelActivity, android.R.color.holo_green_dark))
                    } else {
                        binding.tvExtractStatus.text = "❌ Failed to convert YouTube URL. Try pasting manually."
                        binding.tvExtractStatus.setTextColor(ContextCompat.getColor(this@AddChannelActivity, android.R.color.holo_red_light))
                    }
                }
                return@setOnClickListener
            }

            // Try proxy resolve-stream first (player pages → stream-only, no ads)
            binding.tvExtractStatus.text = "⏳ Resolving stream (no ads)..."

            lifecycleScope.launch {
                var result = resolveStreamViaProxy(sourceUrl)
                if (result == null) result = extractHlsUrl(sourceUrl)
                if (result == null && shouldTryWebViewExtractor(sourceUrl)) {
                    binding.tvExtractStatus.text = "⏳ Trying in-app browser fallback..."
                    result = extractViaHiddenWebView(sourceUrl)
                }
                binding.btnExtract.isEnabled = true
                if (result != null) {
                    binding.etStreamUrl.setText(result)
                    binding.tilStreamUrl.visibility = View.VISIBLE
                    binding.tvExtractStatus.text = "✅ Stream URL extracted. No ads."
                    binding.tvExtractStatus.setTextColor(ContextCompat.getColor(this@AddChannelActivity, android.R.color.holo_green_dark))
                } else {
                    binding.tvExtractStatus.text = "❌ Could not resolve stream. Try pasting the URL manually."
                    binding.tvExtractStatus.setTextColor(ContextCompat.getColor(this@AddChannelActivity, android.R.color.holo_red_light))
                }
            }
        }

        // ─── Explicit Fetch Logo Button ───
        binding.btnFetchLogo.setOnClickListener {
            val name = binding.etChannelName.text?.toString()?.trim()
            if (name.isNullOrEmpty()) {
                binding.tilChannelName.error = "Enter a channel name first"
                return@setOnClickListener
            }
            binding.tilChannelName.error = null
            
            lifecycleScope.launch {
                binding.btnFetchLogo.isEnabled = false
                val url = autoFetchLogo(name)
                binding.btnFetchLogo.isEnabled = true
                
                if (url != null) {
                    binding.cardCustomLogoFallback.visibility = View.GONE
                    binding.etLogoUrl.setText(url)
                    binding.tilLogoUrl.helperText = "✅ Logo fetched from tv-logo repo"
                    Toast.makeText(this@AddChannelActivity, "Logo found!", Toast.LENGTH_SHORT).show()
                } else {
                    binding.cardCustomLogoFallback.visibility = View.VISIBLE
                    binding.tilLogoUrl.helperText = "No match in tv-logo repo — use the box below or paste a URL"
                    Toast.makeText(this@AddChannelActivity, "Logo not found for '$name'", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // ─── Fetch EPG ID Button ───
        binding.btnFetchEpg.setOnClickListener {
            val name = binding.etChannelName.text?.toString()?.trim()
            if (name.isNullOrEmpty()) {
                binding.tilChannelName.error = "Enter a channel name first"
                return@setOnClickListener
            }
            binding.tilChannelName.error = null

            lifecycleScope.launch {
                binding.btnFetchEpg.isEnabled = false
                val suggestions = fetchEpgSuggestions(name)
                binding.btnFetchEpg.isEnabled = true

                if (suggestions == null) {
                    Toast.makeText(this@AddChannelActivity, "Please configure EPG source repositories in Settings first", Toast.LENGTH_LONG).show()
                } else if (suggestions.isEmpty()) {
                    Toast.makeText(this@AddChannelActivity, "No matching EPG found for '$name'", Toast.LENGTH_SHORT).show()
                } else {
                    val items = suggestions.map { "${it.second} (ID: ${it.first})" }.toTypedArray()
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@AddChannelActivity)
                        .setTitle("Select EPG ID")
                        .setItems(items) { _, which ->
                            binding.etEpgId.setText(suggestions[which].first)
                            Toast.makeText(this@AddChannelActivity, "EPG ID set", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }

        // ─── Auto-fill Metadata ───
        binding.etChannelNumber.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val num = s.toString().toIntOrNull() ?: return
                lifecycleScope.launch(Dispatchers.Default) {
                    val meta = lookupChannelMeta(num)
                    if (meta != null) {
                        withContext(Dispatchers.Main) {
                            if (binding.etChannelName.text?.isEmpty() == true) {
                                binding.etChannelName.setText(meta.first)
                            }
                            if (binding.etLogoUrl.text?.isEmpty() == true) {
                                binding.etLogoUrl.setText(meta.second)
                            }
                        }
                    }
                }
            }
        })

        // ─── Direct URL Resolution ───
        binding.etStreamUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (binding.switchDirectStream.isChecked) return
                val raw = s.toString().trim()
                if (raw.contains("player") || raw.contains("play.html")) {
                    val resolved = extractInnerUrlFromPlayer(raw)
                    if (resolved != raw) {
                        // Avoid infinite loop: only update if changed
                        binding.etStreamUrl.setText(resolved)
                        binding.etStreamUrl.setSelection(resolved.length)
                        Toast.makeText(this@AddChannelActivity, "Converted from player link", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })

        // ─── Save ───
        binding.btnSave.setOnClickListener {
            val name = binding.etChannelName.text.toString().trim()
            val numStr = binding.etChannelNumber.text.toString().trim()
            val url = binding.etStreamUrl.text.toString().trim()
            val logo = binding.etLogoUrl.text.toString().trim()
            val epgId = binding.etEpgId.text.toString().trim()
            val epgUrl = binding.etEpgUrl.text.toString().trim()

            var valid = true
            if (name.isEmpty()) { binding.tilChannelName.error = "Required"; valid = false }
            if (numStr.isEmpty() || numStr.toIntOrNull() == null) { binding.tilChannelNumber.error = "Enter a valid number"; valid = false }
            if (url.isEmpty()) { binding.tilStreamUrl.error = "Required"; valid = false }
            if (!valid) return@setOnClickListener

            lifecycleScope.launch {
                binding.btnSave.isEnabled = false
                
                var finalUrl = url
                
                // If it's a player page, extract the inner URL (e.g. live3.php)
                if (!binding.switchDirectStream.isChecked && (url.contains("player.html", ignoreCase = true) || url.contains("play.html", ignoreCase = true))) {
                    val uri = android.net.Uri.parse(url)
                    val extracted = uri.getQueryParameter("url")
                    if (extracted != null) {
                        finalUrl = extracted
                    }
                }

                val isDirect = binding.switchDirectStream.isChecked
                val channel = Channel(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    name = name,
                    channelNumber = numStr.toInt(),
                    streamUrl = if (isDirect && !finalUrl.endsWith("#direct")) "$finalUrl#direct" else finalUrl.replace("#direct", ""),
                    logoUrl = logo,
                    epgId = epgId,
                    epgUrl = epgUrl
                )
                setResult(RESULT_OK, Intent().putExtra("channel", channel).putExtra("edit_index", editIndex))
                finish()
            }
        }
    }

    /**
     * Fix common paste mistakes: encoded path only, path without host, truncated YouTube URLs.
     */
    private fun normalizePastedSourceUrl(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        if (s.startsWith("%2F", ignoreCase = true)) {
            try {
                s = URLDecoder.decode(s, "UTF-8")
            } catch (_: Exception) { }
        }
        if (!s.contains("://")) {
            when {
                s.startsWith("//") -> s = "https:$s"
                s.startsWith("/") -> s = "https://$preferredAirtelHost$s"
                s.startsWith("www.") -> s = "https://$s"
                Regex("""^[a-z0-9.-]+\.(com|in|net|org)/""", RegexOption.IGNORE_CASE).containsMatchIn(s) ->
                    s = "https://$s"
            }
        }
        return normalizeAirtelHost(expandPartialYoutubeUrl(s))
    }

    private fun normalizeAirtelHost(input: String): String {
        return try {
            val url = URL(input)
            if (url.host.equals("allinonereborn.online", ignoreCase = true) ||
                url.host.equals("allinonereborn.store", ignoreCase = true)
            ) {
                URL(url.protocol, preferredAirtelHost, url.file).toString()
            } else {
                input
            }
        } catch (_: Exception) {
            input
        }
    }

    /** e.g. ".com/live/VIDEO?si=..." → "https://www.youtube.com/live/VIDEO?si=..." */
    private fun expandPartialYoutubeUrl(s: String): String {
        val t = s.trim()
        if (t.contains("youtube.com", ignoreCase = true) || t.contains("youtu.be", ignoreCase = true)) {
            return if (t.contains("://", ignoreCase = true)) t else "https://$t"
        }
        // YouTube live paths use an id-like segment (avoid matching .../live3.php etc.)
        Regex("""(/live/[a-zA-Z0-9_-]{8,}(?:\?\S*)?)""").find(t)?.let { m ->
            return "https://www.youtube.com${m.groupValues[1]}"
        }
        Regex("""[?&]v=([a-zA-Z0-9_-]{6,})""").find(t)?.let { m ->
            if (!t.contains("youtube", ignoreCase = true)) {
                return "https://www.youtube.com/watch?v=${m.groupValues[1]}"
            }
        }
        return t
    }

    /**
     * Converts a YouTube channel URL to an M3U8 stream URL via the Render proxy.
     * The proxy handles yt-dlp extraction and returns the HLS URL.
     */
    private suspend fun convertYoutubeToM3u8(youtubeUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val encodedUrl = URLEncoder.encode(youtubeUrl, "UTF-8")
            val requestUrl = "$proxyUrl/api/yt-stream?url=$encodedUrl"
            
            val conn = URL(requestUrl).openConnection() as HttpURLConnection
            conn.apply {
                connectTimeout = 20000
                readTimeout = 90000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36")
            }
            
            return@withContext if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val jsonResponse = JSONObject(response)
                val u = jsonResponse.optString("streamUrl", "")
                u.takeIf { it.isNotEmpty() && !u.equals("null", ignoreCase = true) }
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Resolve player-page or wrapper URLs to direct stream via backend (like developer tools).
     * Handles e.g. allinonereborn.online/.../player.html?url=... → extracts inner URL, fetches, parses for m3u8.
     * Returns stream-only URL with no ads wrapper, or null if proxy fails.
     */
    private suspend fun resolveStreamViaProxy(sourceUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val encodedUrl = URLEncoder.encode(sourceUrl, "UTF-8")
            val conn = URL("$proxyUrl/api/resolve-stream?url=$encodedUrl").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", "Android-LiveTV-Manager/1.0")
            conn.connect()
            if (conn.responseCode != 200) return@withContext null
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            json.optString("streamUrl", "").takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** If pasted URL is a player page (e.g. player.html?url=...), return the inner stream URL. */
    private fun extractInnerUrlFromPlayer(pastedUrl: String): String {
        return try {
            val uri = android.net.Uri.parse(pastedUrl)
            val extracted = uri.getQueryParameter("url")
            if (extracted != null) {
                normalizeAirtelHost(extracted)
            } else {
                normalizeAirtelHost(pastedUrl)
            }
        } catch (_: Exception) {
            normalizeAirtelHost(pastedUrl)
        }
    }

    private fun shouldTryWebViewExtractor(sourceUrl: String): Boolean {
        val normalized = normalizeAirtelHost(sourceUrl)
        return normalized.contains("/player.", ignoreCase = true) ||
            normalized.contains("/live3.php", ignoreCase = true) ||
            normalized.contains("/live.php", ignoreCase = true) ||
            normalized.contains("/ptest.php", ignoreCase = true)
    }

    private fun buildPlayerPageUrl(sourceUrl: String): String {
        val normalized = normalizeAirtelHost(sourceUrl)
        if (normalized.contains("/player.", ignoreCase = true)) return normalized
        val inner = extractInnerUrlFromPlayer(normalized)
        return "https://$preferredAirtelHost/airteltv-web/player.html?url=${URLEncoder.encode(inner, "UTF-8")}"
    }

    private suspend fun extractViaHiddenWebView(sourceUrl: String): String? = suspendCancellableCoroutine { cont ->
        val playerUrl = buildPlayerPageUrl(sourceUrl)
        val candidateUrl = extractInnerUrlFromPlayer(sourceUrl)

        runOnUiThread {
            val webView = WebView(this)
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = false
                userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }

            fun finish(result: String?) {
                if (cont.isCompleted) return
                webView.stopLoading()
                webView.destroy()
                cont.resume(result)
            }

            val timeoutRunnable = Runnable { finish(null) }
            webView.postDelayed(timeoutRunnable, 15000)

            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

                override fun onPageFinished(view: WebView, url: String) {
                    val js = """
                        (async function() {
                          try {
                            const target = ${JSONObject.quote(candidateUrl)};
                            const resp = await fetch(target, { credentials: 'include' });
                            const text = await resp.text();
                            return JSON.stringify({
                              ok: resp.ok,
                              status: resp.status,
                              target,
                              bodyStart: text.slice(0, 400)
                            });
                          } catch (e) {
                            return JSON.stringify({ ok: false, error: String(e) });
                          }
                        })();
                    """.trimIndent()

                    view.evaluateJavascript(js) { raw ->
                        webView.removeCallbacks(timeoutRunnable)
                        val parsed = parseEvaluateJavascriptResult(raw)
                        val bodyStart = parsed?.optString("bodyStart", "") ?: ""
                        val ok = parsed?.optBoolean("ok", false) == true
                        val extracted = if (ok && bodyStart.contains("#EXTM3U")) candidateUrl else null
                        finish(extracted)
                    }
                }
            }

            cont.invokeOnCancellation {
                webView.removeCallbacks(timeoutRunnable)
                webView.stopLoading()
                webView.destroy()
            }

            webView.loadUrl(playerUrl)
        }
    }

    private fun parseEvaluateJavascriptResult(raw: String?): JSONObject? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            val decoded = JSONArray("[$raw]").getString(0)
            JSONObject(decoded)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Follows redirects from a proxy/source URL to resolve the final HLS .m3u8 URL.
     * Works for allinonereborn.store links and similar PHP redirect wrappers.
     * If URL is a player page (player.html?url=...), uses the inner URL first.
     */
    private suspend fun extractHlsUrl(sourceUrl: String): String? = withContext(Dispatchers.IO) {
        val urlToFetch = extractInnerUrlFromPlayer(sourceUrl)
        try {
            var currentUrl = urlToFetch
            var maxRedirects = 12

            while (maxRedirects-- > 0) {
                val conn = URL(currentUrl).openConnection() as HttpURLConnection
                conn.apply {
                    instanceFollowRedirects = false
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                    setRequestProperty("Referer", "https://allinonereborn.online/")
                    setRequestProperty("Accept", "*/*")
                }
                conn.connect()

                val code = conn.responseCode
                val location = conn.getHeaderField("Location")

                when {
                    // Redirect
                    code in 300..399 && location != null -> {
                        conn.disconnect()
                        currentUrl = if (location.startsWith("http")) location
                        else URL(URL(currentUrl), location).toString()
                    }
                    // Success — read body before disconnect (needed to parse HTML for m3u8)
                    code == 200 -> {
                        val body = try {
                            conn.inputStream.bufferedReader().use { it.readText() }
                        } catch (_: Exception) {
                            ""
                        }
                        conn.disconnect()
                        return@withContext if (currentUrl.contains(".m3u8", ignoreCase = true) ||
                            currentUrl.contains("m3u8", ignoreCase = true)
                        ) {
                            currentUrl
                        } else {
                            val m3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(body)?.value
                            m3u8 ?: currentUrl
                        }
                    }
                    else -> {
                        conn.disconnect()
                        return@withContext null
                    }
                }
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun isAirtelWrapper(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("allinonereborn", ignoreCase = true) && 
               (u.contains(".php") || u.contains("/player"))
    }

    private fun isYouTubeUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("youtube.com") || u.contains("youtu.be")
    }

    private fun lookupChannelMeta(channelNumber: Int): Pair<String, String>? {
        return try {
            val jsonStr = assets.open("channels.json").bufferedReader().use { it.readText() }
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.getInt("channel_id") == channelNumber) {
                    return Pair(obj.getString("name"), obj.getString("logo"))
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    // ─── Auto-fetch logo from tv-logo/tv-logos GitHub repo ───
    private suspend fun autoFetchLogo(channelName: String): String? = withContext(Dispatchers.IO) {
        // Convert "Asianet HD" → "asianet-hd"
        val slug = channelName
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")   // strip special chars
            .replace(Regex("\\s+"), "-")          // spaces → hyphens
            .replace(Regex("-+"), "-")            // collapse double hyphens

        val base = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries"
        val candidates = listOf(
            "$base/india/$slug-in.png",
            "$base/international/$slug.png",
            "$base/india/$slug.png"
        )

        for (url in candidates) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.connect()
                if (conn.responseCode == 200) return@withContext url
                conn.disconnect()
            } catch (_: Exception) {}
        }
        null
    }

    private suspend fun fetchEpgSuggestions(channelName: String): List<Pair<String, String>>? = withContext(Dispatchers.IO) {
        val prefs = getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
        val localReposStr = prefs.getString("epg_repos", "")?.trim()
        val localUrls = localReposStr?.split("\n", ",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        
        // Fetch shared repos from Supabase
        val sharedUrls = try {
            SupabaseSync.fetchEpgSources()
        } catch (e: Exception) {
            emptyList<String>()
        }
        
        val urls = (localUrls + sharedUrls).distinct()
        if (urls.isEmpty()) return@withContext null

        val results = mutableListOf<Pair<String, String>>()
        val searchTarget = channelName.lowercase().replace(Regex("[^a-z0-9]"), "")

        for (urlStr in urls) {
            try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.connect()
                if (conn.responseCode == 200) {
                    val stream = if (urlStr.endsWith(".gz", ignoreCase = true)) {
                        java.util.zip.GZIPInputStream(conn.inputStream)
                    } else {
                        conn.inputStream
                    }
                    
                    val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
                    val parser = factory.newPullParser()
                    parser.setInput(stream, null)
                    
                    var eventType = parser.eventType
                    var currentId = ""
                    var currentName = ""
                    var insideChannel = false
                    
                    while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        when (eventType) {
                            org.xmlpull.v1.XmlPullParser.START_TAG -> {
                                if (parser.name == "channel") {
                                    insideChannel = true
                                    currentId = parser.getAttributeValue(null, "id") ?: ""
                                    currentName = ""
                                } else if (insideChannel && parser.name == "display-name") {
                                    currentName = parser.nextText()
                                } else if (parser.name == "programme") {
                                    // Optimization: Stop parsing once programmes start, since channels are usually listed first
                                    break
                                }
                            }
                            org.xmlpull.v1.XmlPullParser.END_TAG -> {
                                if (parser.name == "channel") {
                                    if (currentId.isNotEmpty() && currentName.isNotEmpty()) {
                                        val cNameNormalized = currentName.lowercase().replace(Regex("[^a-z0-9]"), "")
                                        if (cNameNormalized.contains(searchTarget) || searchTarget.contains(cNameNormalized)) {
                                            results.add(Pair(currentId, currentName))
                                        }
                                    }
                                    insideChannel = false
                                }
                            }
                        }
                        eventType = parser.next()
                    }
                    stream.close()
                }
                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return@withContext results.distinctBy { it.first }
    }

    /**
     * Embeds a picked image as a data URI in [logo_url] / Channel.logoUrl — same DB field as hosted URLs (e.g. Asianet).
     * Downscales and JPEG-compresses to keep Supabase row size reasonable.
     */
    private fun encodePickedImageToDataUri(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val raw = BitmapFactory.decodeStream(input) ?: return null
                val scaled = scaleBitmapMaxEdge(raw, 512f)
                if (scaled != raw && !raw.isRecycled) raw.recycle()
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, baos)
                if (scaled != raw && !scaled.isRecycled) scaled.recycle()
                val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                "data:image/jpeg;base64,$b64"
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun scaleBitmapMaxEdge(source: Bitmap, maxEdge: Float): Bitmap {
        val w = source.width.toFloat()
        val h = source.height.toFloat()
        val max = maxOf(w, h)
        if (max <= maxEdge) return source
        val scale = maxEdge / max
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, nw, nh, true)
    }

    // ─── Load logo preview Bitmap ───
    private fun loadLogoPreview(url: String) {
        if (url.startsWith("data:image")) {
            lifecycleScope.launch(Dispatchers.Default) {
                val bmp = decodeDataUriBitmap(url)
                withContext(Dispatchers.Main) {
                    if (bmp != null) {
                        binding.ivLogoPreview.setImageBitmap(bmp)
                        binding.ivLogoPreview.visibility = View.VISIBLE
                    } else {
                        binding.ivLogoPreview.visibility = View.GONE
                    }
                }
            }
            return
        }
        if (!url.startsWith("http")) {
            binding.ivLogoPreview.visibility = View.GONE
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.doInput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.connect()
                if (conn.responseCode == 200) {
                    val bitmap = BitmapFactory.decodeStream(conn.inputStream)
                    withContext(Dispatchers.Main) {
                        if (bitmap != null) {
                            binding.ivLogoPreview.setImageBitmap(bitmap)
                            binding.ivLogoPreview.visibility = View.VISIBLE
                        } else {
                            binding.ivLogoPreview.visibility = View.GONE
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) { binding.ivLogoPreview.visibility = View.GONE }
                }
                conn.disconnect()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { binding.ivLogoPreview.visibility = View.GONE }
            }
        }
    }

    private fun decodeDataUriBitmap(dataUri: String): Bitmap? {
        val comma = dataUri.indexOf(',')
        if (comma <= 0) return null
        val payload = dataUri.substring(comma + 1)
        return try {
            val bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
