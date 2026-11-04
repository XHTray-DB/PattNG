package com.v2ray.ang.v2get

/**
 * When the Aether tunnel drops, the app brings it back by itself: the core is started again in place, as after a
 * network handover, and nothing the person sees is torn down. This holds the two rules of that: which lines of the
 * core's output tell of a lost tunnel, and how often the app may try before it gives up and stops with the reason.
 */
object V2GetRecovery {

    /** The tries counted together: more than [MAX_IN_WINDOW] within this and the app stops instead of looping. */
    const val WINDOW_MS = 5 * 60_000L
    const val MAX_IN_WINDOW = 6

    /** A try this recent means a core that dies again is still part of the same recovery, not a start that failed. */
    const val RECENT_MS = 60_000L

    private val tries = ArrayDeque<Long>()

    /**
     * Takes one try at [now]. Returns how long to wait before it (short at first, longer as the tries pile up),
     * or null when the budget of the window is spent.
     */
    @Synchronized
    fun take(now: Long): Long? {
        while (tries.isNotEmpty() && now - tries.first() > WINDOW_MS) tries.removeFirst()
        if (tries.size >= MAX_IN_WINDOW) return null
        val wait = minOf(400L shl tries.size, 6_000L)
        tries.addLast(now)
        return wait
    }

    /** True when a try was taken within the last [RECENT_MS]. */
    @Synchronized
    fun recent(now: Long): Boolean = tries.any { now - it <= RECENT_MS }

    /** A start by the person begins with a full budget. */
    @Synchronized
    fun reset() = tries.clear()

    private val header = Regex("""^\[\S+\s+(?:WARN|ERROR)\s+aether\]\s*(.*)$""")

    private val lossWords = Regex(
        "tunnel (?:ended|closed|lost|down|dropped)|connection (?:reset|closed|lost|aborted|refused|timed out)|" +
            "timed out|timeout|broken pipe|disconnected|network is unreachable|no route to host|reconnect|" +
            "handshake|keep-?alive|session ended|stream (?:closed|reset)|endpoint (?:lost|unreachable)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * True when [line] of the core's output is a warning or an error of the core itself (not of Psiphon or Tor
     * inside it, which mend themselves) that says its tunnel ended or the connection broke. A line like this only
     * starts a check: the tunnel is tried before anything is restarted.
     */
    fun looksLikeLoss(line: String): Boolean {
        val message = header.find(line.trim())?.groupValues?.get(1) ?: return false
        return message.startsWith("[-]") && lossWords.containsMatchIn(message)
    }
}
