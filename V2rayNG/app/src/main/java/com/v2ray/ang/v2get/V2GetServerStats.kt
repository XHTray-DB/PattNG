package com.v2ray.ang.v2get

import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * The app's word on each server it connects to: it came up, or it did not. The panel gathers the words of all
 * accounts, and the list builder of the repository drops the servers most accounts could not use. A test run from
 * outside Iran cannot tell what only works inside it; the people who use the servers can.
 */
object V2GetServerStats {

    private const val SAME_WORD_MS = 30 * 60_000L
    private val last = ConcurrentHashMap<String, Pair<Boolean, Long>>()

    /** The key the panel and the list builder know a server by: the hash part of its profile id. */
    fun keyOf(guid: String): String = guid.removePrefix(V2GetConfigSource.GUID_PREFIX)

    /** Reports [ok] for the server [guid], in the background. The same word for a server within half an hour is not sent again. */
    fun report(guid: String?, ok: Boolean, now: Long = System.currentTimeMillis()) {
        if (guid == null || !V2GetConfigSource.isV2GetGuid(guid) || !V2GetAuthManager.isLoggedIn()) return
        val before = last[guid]
        if (before != null && before.first == ok && now - before.second < SAME_WORD_MS) return
        last[guid] = ok to now
        thread(name = "v2get-server-report", isDaemon = true) { V2GetAuthManager.reportServer(keyOf(guid), ok) }
    }
}
