package dev.panoterm.term

/**
 * Minimal Dubeolsik (두벌식) Hangul automaton for hardware keyboards.
 * Feed QWERTY letters with [key]; committed text is returned, the syllable
 * being composed is available from [composing].
 */
class Hangul {
    private val cho = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private val jung = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    private val jong = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

    private val keymap: Map<Char, Char> = buildMap {
        val lower = "rsefaqtdwczxvgkoijpuhynbml"
        val jamo = "ㄱㄴㄷㄹㅁㅂㅅㅇㅈㅊㅋㅌㅍㅎㅏㅐㅑㅓㅔㅕㅗㅛㅜㅠㅡㅣ"
        lower.forEachIndexed { i, c -> put(c, jamo[i]); put(c.uppercaseChar(), jamo[i]) }
        // Shifted keys that produce different jamo
        put('R', 'ㄲ'); put('E', 'ㄸ'); put('Q', 'ㅃ'); put('T', 'ㅆ'); put('W', 'ㅉ')
        put('O', 'ㅒ'); put('P', 'ㅖ')
    }

    private val vowelPairs = mapOf(
        "ㅗㅏ" to 'ㅘ', "ㅗㅐ" to 'ㅙ', "ㅗㅣ" to 'ㅚ',
        "ㅜㅓ" to 'ㅝ', "ㅜㅔ" to 'ㅞ', "ㅜㅣ" to 'ㅟ', "ㅡㅣ" to 'ㅢ',
    )
    private val finalPairs = mapOf(
        "ㄱㅅ" to 'ㄳ', "ㄴㅈ" to 'ㄵ', "ㄴㅎ" to 'ㄶ', "ㄹㄱ" to 'ㄺ', "ㄹㅁ" to 'ㄻ',
        "ㄹㅂ" to 'ㄼ', "ㄹㅅ" to 'ㄽ', "ㄹㅌ" to 'ㄾ', "ㄹㅍ" to 'ㄿ', "ㄹㅎ" to 'ㅀ', "ㅂㅅ" to 'ㅄ',
    )
    private val vowelSplit = vowelPairs.entries.associate { it.value to it.key }
    private val finalSplit = finalPairs.entries.associate { it.value to it.key }

    private var c: Char? = null // choseong
    private var v: Char? = null // jungseong
    private var f: Char? = null // jongseong

    fun isHangulKey(ch: Char) = keymap.containsKey(ch)
    val isComposing: Boolean get() = c != null || v != null

    val composing: String
        get() {
            val cc = c; val vv = v; val ff = f
            return when {
                cc != null && vv != null -> {
                    val code = 0xAC00 + (cho.indexOf(cc) * 21 + jung.indexOf(vv)) * 28 + (if (ff == null) 0 else jong.indexOf(ff))
                    code.toChar().toString()
                }
                cc != null -> cc.toString()
                vv != null -> vv.toString()
                else -> ""
            }
        }

    /** Returns text to commit (possibly empty). */
    fun key(ch: Char): String {
        val j = keymap[ch] ?: return flush() + ch
        return if (j in jung) vowel(j) else consonant(j)
    }

    private fun consonant(j: Char): String {
        val cc = c; val vv = v; val ff = f
        return when {
            cc == null && vv == null -> { c = j; "" }
            cc != null && vv == null -> { val out = flush(); c = j; out }
            cc == null -> { val out = flush(); c = j; out } // lone vowel, then consonant
            ff == null -> {
                if (j in jong) { f = j; "" } else { val out = flush(); c = j; out }
            }
            else -> {
                val pair = finalPairs["$ff$j"]
                if (pair != null) { f = pair; "" } else { val out = flush(); c = j; out }
            }
        }
    }

    private fun vowel(j: Char): String {
        val cc = c; val vv = v; val ff = f
        return when {
            ff != null -> {
                // Final consonant moves to the next syllable: 갑 + ㅏ → 가바
                val split = finalSplit[ff]
                val keep = split?.get(0)
                val move = split?.get(1) ?: ff
                f = keep
                val out = composing
                c = move; v = j; f = null
                out
            }
            vv != null -> {
                val pair = vowelPairs["$vv$j"]
                if (pair != null && cc != null || pair != null && cc == null) { v = pair; "" }
                else { val out = flush(); v = j; out }
            }
            cc != null -> { v = j; "" }
            else -> { v = j; "" }
        }
    }

    /** Returns true if consumed (there was something to delete in the composition). */
    fun backspace(): Boolean {
        val ff = f; val vv = v
        when {
            ff != null -> f = finalSplit[ff]?.get(0)
            vv != null -> v = vowelSplit[vv]?.get(0)
            c != null -> c = null
            else -> return false
        }
        return true
    }

    fun flush(): String {
        val out = composing
        c = null; v = null; f = null
        return out
    }
}
