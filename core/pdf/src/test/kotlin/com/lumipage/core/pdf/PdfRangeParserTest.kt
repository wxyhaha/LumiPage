package com.lumipage.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfRangeParserTest {

    @Test
    fun `parsePageRanges handles single pages ranges and bounds`() {
        val pages = PdfService.parsePageRanges("1-3, 5, 8-6, 99", maxPages = 10)
        assertEquals(listOf(1, 2, 3, 5, 6, 7, 8), pages)
    }

    @Test
    fun `parsePageRanges returns empty for blank or invalid input`() {
        assertEquals(emptyList<Int>(), PdfService.parsePageRanges("", maxPages = 5))
        assertEquals(emptyList<Int>(), PdfService.parsePageRanges("abc", maxPages = 5))
    }
}
