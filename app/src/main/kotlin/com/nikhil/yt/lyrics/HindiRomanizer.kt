package com.nikhil.yt.lyrics

/**
 * Hindi (Devanagari) lyrics to the Roman Urdu people actually type: "mera dil", "zindagi", "pyaar".
 * Rule based, because Devanagari is phonetic; the two rules that make it read naturally are
 * - schwa deletion: the built-in "a" after a consonant is silent at the end of a word and between an
 *   open syllable and a vowel ("dil", not "dila"; "dharkan", not "dharakana");
 * - Roman Urdu spelling at word ends: aa -> a, ee -> i, oo -> u ("mera", "meri", "tu").
 */
object HindiRomanizer {

    fun isDevanagari(text: String): Boolean = text.any { it in 'ऀ'..'ॿ' }

    fun romanize(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (isWordChar(text[i])) {
                val start = i
                while (i < text.length && isWordChar(text[i])) i++
                out.append(romanizeWord(text.substring(start, i)))
            } else {
                out.append(
                    when (text[i]) {
                        '।', '॥' -> "" // danda: verse marks, no Roman equivalent
                        in '०'..'९' -> ('0' + (text[i] - '०')).toString()
                        else -> text[i].toString()
                    },
                )
                i++
            }
        }
        return out.toString().replace(Regex(" {2,}"), " ").trim()
    }

    private class Syllable(
        var consonant: String,
        /** null = the built-in "a" (may be silent); "" = none (virama or deleted). */
        var vowel: String?,
        var nasal: String = "",
        var halant: Boolean = false,
    ) {
        val hasSchwa get() = vowel == null && !halant && consonant.isNotEmpty()
        val hasVowel get() = !halant && (vowel == null || vowel!!.isNotEmpty())
    }

    private fun romanizeWord(word: String): String {
        val syllables = mutableListOf<Syllable>()
        for (c in word) {
            val last = syllables.lastOrNull()
            when {
                c in CONSONANTS -> syllables += Syllable(CONSONANTS.getValue(c), null)
                c == NUKTA && last != null -> last.consonant = NUKTA_FORMS[last.consonant] ?: last.consonant
                c in MATRAS && last != null -> last.vowel = MATRAS.getValue(c)
                c == VIRAMA && last != null -> { last.halant = true; last.vowel = "" }
                (c == ANUSVARA || c == CHANDRABINDU) && last != null -> last.nasal = "n"
                c == VISARGA && last != null -> last.nasal = "h"
                c in VOWELS -> syllables += Syllable("", VOWELS.getValue(c))
            }
        }
        if (syllables.isEmpty()) return ""

        // Word-final schwa is silent ("dil"), except in one-syllable words ("na", "ka").
        if (syllables.size > 1 && syllables.last().hasSchwa) syllables.last().vowel = ""
        // Medial schwa is silent between an open syllable and a vowel (VC_CV), right to left.
        for (i in syllables.size - 2 downTo 1) {
            val current = syllables[i]
            val previous = syllables[i - 1]
            val next = syllables[i + 1]
            if (current.hasSchwa && previous.hasVowel && previous.nasal.isEmpty() && next.hasVowel) current.vowel = ""
        }

        return buildString {
            syllables.forEachIndexed { index, syllable ->
                append(syllable.consonant)
                val isLast = index == syllables.lastIndex
                append(
                    when (val vowel = syllable.vowel) {
                        null -> if (syllable.halant) "" else "a"
                        else -> if (isLast) WORD_END[vowel] ?: vowel else vowel
                    },
                )
                if (syllable.nasal == "n") {
                    val following = syllables.getOrNull(index + 1)?.consonant.orEmpty()
                    append(if (following.startsWith("p") || following.startsWith("b") || following.startsWith("m")) "m" else "n")
                } else {
                    append(syllable.nasal)
                }
            }
        }
    }

    private fun isWordChar(c: Char) =
        (c in 'ऀ'..'ॣ') || (c in '॰'..'ॿ') || c == '‌' || c == '‍'

    private const val NUKTA = '़'
    private const val VIRAMA = '्'
    private const val ANUSVARA = 'ं'
    private const val CHANDRABINDU = 'ँ'
    private const val VISARGA = 'ः'

    private val WORD_END = mapOf("aa" to "a", "ee" to "i", "oo" to "u")

    private val VOWELS = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo",
        'ऋ' to "ri", 'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऍ' to "e",
    )

    private val MATRAS = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ॅ' to "e",
    )

    private val CONSONANTS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "w",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        // Precomposed nukta letters: the Urdu sounds.
        'क़' to "q", 'ख़' to "kh", 'ग़' to "gh", 'ज़' to "z",
        'ड़' to "r", 'ढ़' to "rh", 'फ़' to "f", 'य़' to "y",
    )

    /** A consonant followed by a separate nukta sign becomes its Urdu sound. */
    private val NUKTA_FORMS = mapOf("k" to "q", "j" to "z", "ph" to "f", "d" to "r", "dh" to "rh")
}
