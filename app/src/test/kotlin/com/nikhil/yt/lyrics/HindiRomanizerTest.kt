package com.nikhil.yt.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HindiRomanizerTest {
    private fun roman(text: String) = HindiRomanizer.romanize(text)

    @Test
    fun everydayLyricWords_readAsRomanUrdu() {
        assertEquals("mera dil", roman("मेरा दिल"))
        assertEquals("teri meri", roman("तेरी मेरी"))
        assertEquals("ham tum", roman("हम तुम"))
        assertEquals("kya", roman("क्या"))
        assertEquals("pyaar", roman("प्यार"))
        assertEquals("nahin", roman("नहीं"))
        assertEquals("aankhen", roman("आँखें"))
        assertEquals("hai", roman("है"))
    }

    @Test
    fun silentA_isDroppedWhereHindiDropsIt() {
        assertEquals("dharkan", roman("धड़कन"))
        assertEquals("samajhna", roman("समझना"))
        assertEquals("mohabbat", roman("मोहब्बत"))
        assertEquals("kamal", roman("कमल"))
        assertEquals("na", roman("न"))
    }

    @Test
    fun urduSounds_fromNukta() {
        assertEquals("zindagi", roman("ज़िंदगी")) // separate nukta sign
        assertEquals("zindagi", roman("ज़िंदगी")) // precomposed letter
    }

    @Test
    fun mixedLines_keepNonHindiText() {
        assertEquals("mera dil, baby", roman("मेरा दिल, baby"))
        assertEquals("dil", roman("दिल।"))
    }

    @Test
    fun detection() {
        assertTrue(HindiRomanizer.isDevanagari("दिल hai"))
        assertFalse(HindiRomanizer.isDevanagari("dil hai"))
    }
}
