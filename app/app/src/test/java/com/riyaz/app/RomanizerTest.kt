package com.riyaz.app

import com.ibm.icu.text.Transliterator
import com.riyaz.app.lyrics.Romanizer
import org.junit.Assert.assertEquals
import org.junit.Test

class RomanizerTest {
    private val icu = Transliterator.getInstance("Any-Latin")
    private val r = Romanizer { icu.transliterate(it) }

    @Test fun hindi() {
        assertEquals("Uska hi bana", r.romanize("उसका ही बना"))
        assertEquals("Meri kismat ke, har ek panne pe", r.romanize("मेरी किस्मत के, हर एक पन्ने पे"))
        assertEquals("Sab kuchh likhun ya kuchh bhi na likhun?", r.romanize("सब कुछ लिखूँ या कुछ भी ना लिखूँ?"))
        assertEquals("Kya hi likhun ke tujhko na likhun?", r.romanize("क्या ही लिखूँ के तुझको ना लिखूँ?"))
        assertEquals("Bhatak raha hoon in andheron mein yun hi", r.romanize("भटक रहा हूँ इन अँधेरों में यूँ ही"))
        assertEquals("Naraz chaand hai ya taron ki kami?", r.romanize("नाराज़ चाँद है या तारों की कमी?"))
        assertEquals("Mere hisse ki khushi ko, hansi ko tu chahe aadha kar", r.romanize("मेरे हिस्से की ख़ुशी को, हँसी को तू चाहे आधा कर"))
        assertEquals("Ae khuda, ae khuda, jab bana uska hi bana", r.romanize("ऐ ख़ुदा, ऐ ख़ुदा, जब बना उसका ही बना"))
        assertEquals("Zindagi", r.romanize("ज़िंदगी"))
        assertEquals("Tum mere aasman", r.romanize("तुम मेरे आसमां"))
    }

    @Test fun teluguAndOthers() {
        assertEquals("Andamaina prema lekha nuvvule", r.romanize("అందమైన ప్రేమ లేఖ నువ్వులే"))
        assertEquals("Astamanu chaduvukuntane", r.romanize("అస్తమాను చదువుకుంటనే"))
        assertEquals("Ami tomake bhalobasi", r.romanize("আমি তোমাকে ভালোবাসি"))
        assertEquals("Tum hi ho", r.romanize("Tum hi ho"))   // already English letters: untouched
        assertEquals("Oh shitakalam", r.romanize("Oh శీతాకాలం"))
    }
}
