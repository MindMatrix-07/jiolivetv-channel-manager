package com.livetv.channelmanager

/**
 * Single Render service: YouTube / resolve-stream / Jio APIs (avoid Vercel 404 on /api).
 * Change once here after redeploying Live-TV proxy.
 */
object LiveTvBackend {
    const val BASE_URL = "https://live-tv-proxy-a9mg.onrender.com"
    /** Path prefix for /api/jio-login, /api/resolve-stream, /api/yt-stream, etc. */
    const val API_BASE = "$BASE_URL/api"
}
