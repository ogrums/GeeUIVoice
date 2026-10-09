package com.geeui.voiceemo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RetryTest {
    @Test
    fun succeeds_on_the_second_try() {
        var n = 0
        val value = Retry.run(attempts = 3, firstMs = 1) {
            n += 1
            if (n < 2) error("down")
            "ok"
        }
        assertEquals("ok", value)
        assertEquals(2, n)
    }

    @Test
    fun gives_up_after_the_cap() {
        assertFailsWith<IllegalStateException> {
            Retry.run(attempts = 2, firstMs = 1) { error("down") }
        }
    }
}
