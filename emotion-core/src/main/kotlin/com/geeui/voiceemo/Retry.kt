package com.geeui.voiceemo

/** Bounded retry. Blank config uses 3 tries, 200 ms, doubled, capped at 2 s. */
object Retry {
    const val ATTEMPTS = 3
    const val FIRST_MS = 200L
    const val CAP_MS = 2_000L

    fun <T> run(attempts: Int = ATTEMPTS, firstMs: Long = FIRST_MS, block: () -> T): T {
        var wait = firstMs
        var last: Exception? = null
        repeat(attempts.coerceAtLeast(1)) { n ->
            try {
                return block()
            } catch (e: Exception) {
                last = e
                if (n == attempts - 1) throw e
                Thread.sleep(wait)
                wait = (wait * 2).coerceAtMost(CAP_MS)
            }
        }
        throw last ?: IllegalStateException("retry failed")
    }
}
