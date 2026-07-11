package com.jp.paperplayer.party

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PartyFileServerTest {

    @Test
    fun `parses a bounded range`() {
        val range = PartyFileServer.parseRange("bytes=0-99", totalLength = 1000)
        assertEquals(0L..99L, range)
    }

    @Test
    fun `parses an open-ended range to the end of the file`() {
        val range = PartyFileServer.parseRange("bytes=100-", totalLength = 1000)
        assertEquals(100L..999L, range)
    }

    @Test
    fun `clamps an end past the end of the file`() {
        val range = PartyFileServer.parseRange("bytes=0-2000", totalLength = 1000)
        assertEquals(0L..999L, range)
    }

    @Test
    fun `accepts a range touching the last byte`() {
        val range = PartyFileServer.parseRange("bytes=900-999", totalLength = 1000)
        assertEquals(900L..999L, range)
    }

    @Test
    fun `rejects a start at or past the end of the file`() {
        assertNull(PartyFileServer.parseRange("bytes=1000-1010", totalLength = 1000))
        assertNull(PartyFileServer.parseRange("bytes=1000-", totalLength = 1000))
    }

    @Test
    fun `rejects an end before the start`() {
        assertNull(PartyFileServer.parseRange("bytes=500-100", totalLength = 1000))
    }

    @Test
    fun `rejects multi-range requests`() {
        assertNull(PartyFileServer.parseRange("bytes=0-10,20-30", totalLength = 1000))
    }

    @Test
    fun `rejects suffix ranges`() {
        assertNull(PartyFileServer.parseRange("bytes=-500", totalLength = 1000))
    }

    @Test
    fun `rejects malformed headers`() {
        assertNull(PartyFileServer.parseRange("bytes=abc-100", totalLength = 1000))
        assertNull(PartyFileServer.parseRange("bytes=100", totalLength = 1000))
        assertNull(PartyFileServer.parseRange("100-200", totalLength = 1000))
        assertNull(PartyFileServer.parseRange("", totalLength = 1000))
    }
}
