package com.geeui.voice

import com.geeui.voice.bus.IDLE_FACE
import com.geeui.voice.bus.IdleFaces
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdleFacesTest {
    @Test
    fun defaultListIsTheRangeMinusExcludedFaces() {
        assertEquals(32, IdleFaces.DEFAULT.size)
        assertTrue("h0135" in IdleFaces.DEFAULT)
        assertTrue("h0175" in IdleFaces.DEFAULT)
        assertTrue("h0156" in IdleFaces.DEFAULT)
        for (id in IdleFaces.EXCLUDED.keys) {
            assertFalse(id in IdleFaces.DEFAULT, id)
        }
        assertEquals(9, IdleFaces.EXCLUDED.size)
    }

    @Test
    fun blankSettingUsesTheBuiltInList() {
        assertEquals(IdleFaces.DEFAULT, IdleFaces.parseList(null))
        assertEquals(IdleFaces.DEFAULT, IdleFaces.parseList("  "))
    }

    @Test
    fun offOrH0059DisablesRotation() {
        for (raw in listOf("off", "OFF", "0", "false", "non", "none", "h0059", "0059")) {
            assertEquals(emptyList(), IdleFaces.parseList(raw), raw)
        }
    }

    @Test
    fun customListIsHonouredIncludingExcludedIds() {
        assertEquals(listOf("h0135", "h0142", "h0175"), IdleFaces.parseList("h0135, 0142; h0175"))
    }

    @Test
    fun parseIntervalTreatsSmallNumbersAsSeconds() {
        assertEquals(0L, IdleFaces.parseInterval(null))
        assertEquals(0L, IdleFaces.parseInterval(""))
        assertEquals(20_000L, IdleFaces.parseInterval("20"))
        assertEquals(25_000L, IdleFaces.parseInterval("25000"))
        assertEquals(15_000L, IdleFaces.parseInterval("5"))
        assertEquals(90_000L, IdleFaces.parseInterval("120"))
    }

    @Test
    fun nextDelayPicksTwentyToFortySecondsWhenUnset() {
        assertEquals(30_000L, IdleFaces.nextDelayMs(0L) { 10 })
        assertEquals(20_000L, IdleFaces.nextDelayMs(0L) { 0 })
        assertEquals(40_000L, IdleFaces.nextDelayMs(0L) { 20 })
        assertEquals(25_000L, IdleFaces.nextDelayMs(25_000L) { 99 })
    }

    @Test
    fun nextAvoidsRepeatingTheCurrentFace() {
        val list = listOf("h0135", "h0136")
        assertEquals("h0136", IdleFaces.next("h0135", list) { 0 })
        assertEquals("h0135", IdleFaces.next("h0136", list) { 0 })
        assertEquals(IDLE_FACE, IdleFaces.next("h0135", emptyList()))
        assertEquals("h0135", IdleFaces.next("h0135", listOf("h0135")))
    }
}
