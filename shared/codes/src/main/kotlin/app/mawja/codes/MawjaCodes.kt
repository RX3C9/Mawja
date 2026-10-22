/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.codes

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * Mawja membership activation codes: `MW-XXXX-XXXX-XXXX`. Shared by the app and the Admin app
 * (Gradle module :shared-codes in shared/codes, a dependency of both); the TypeScript twin is admin-web/lib/codes.ts and the
 * spec both follow is docs/codes.md. Keep the three in step.
 *
 * - 12 symbols from [ALPHABET] (no 0, O, 1, I or L): 11 random + 1 check symbol.
 * - Check: with weights 1..12, the sum of weight × symbol value is 0 modulo 31. That catches every
 *   single wrong symbol and every swap of two neighbours, the check symbol included.
 * - Firestore keeps only [hash] of a code, never the code itself.
 */
object MawjaCodes {
    const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    const val PREFIX = "MW"
    const val PAYLOAD_LENGTH = 11
    const val LENGTH = 12
    const val HASH_PREFIX = "mawja-code-v1:"
    private const val BASE = 31 // ALPHABET.length, a prime
    private const val INV_12 = 13 // 12 × 13 = 156 ≡ 1 (mod 31)

    private val DASHES = setOf('-', '‐', '‑', '‒', '–', '—', '―', '−', '_', '.', 'ـ', '/')

    /** Result of reading what a person typed or pasted. */
    sealed interface Parsed {
        /** [canonical] is the 12 symbols without prefix or dashes. */
        data class Valid(
            val canonical: String,
        ) : Parsed

        /** Fewer or more than 12 symbols (after removing the MW prefix, spaces and dashes). */
        data object WrongLength : Parsed

        /** A character that never appears in a code (0, O, 1, I, L, other letters or symbols). */
        data object BadCharacter : Parsed

        /** The right shape, but the check symbol does not match: a typo. */
        data object BadCheck : Parsed
    }

    /**
     * NFKC, Arabic-Indic digits to ASCII, upper case, spaces and dashes removed, and the `MW`
     * prefix dropped when the result is 14 symbols long. Does not validate.
     */
    fun clean(input: String): String {
        val nfkc = Normalizer.normalize(input, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        for (ch in nfkc) {
            val mapped =
                when (ch) {
                    in '\u0660'..'\u0669' -> '0' + (ch - '\u0660')
                    in '\u06F0'..'\u06F9' -> '0' + (ch - '\u06F0')
                    else -> ch
                }
            if (mapped.isWhitespace() || mapped in DASHES || mapped == '\u200B' || mapped == '\u200F' || mapped == '\u200E' || mapped == '\uFEFF') continue
            sb.append(mapped)
        }
        val upper = sb.toString().uppercase(Locale.ROOT)
        return if (upper.length == LENGTH + PREFIX.length && upper.startsWith(PREFIX)) upper.substring(PREFIX.length) else upper
    }

    fun parse(input: String): Parsed {
        val code = clean(input)
        if (code.any { it !in ALPHABET }) return Parsed.BadCharacter
        if (code.length != LENGTH) return Parsed.WrongLength
        return if (weightedSum(code) % BASE == 0) Parsed.Valid(code) else Parsed.BadCheck
    }

    /** The canonical 12 symbols, or null when [input] is not a valid code. */
    fun normalize(input: String): String? = (parse(input) as? Parsed.Valid)?.canonical

    fun isValid(input: String): Boolean = normalize(input) != null

    /** The check symbol for 11 payload symbols. */
    fun checkSymbol(payload: String): Char {
        require(payload.length == PAYLOAD_LENGTH && payload.all { it in ALPHABET }) { "payload must be 11 code symbols" }
        val s = weightedSum(payload) % BASE
        return ALPHABET[((BASE - s) % BASE) * INV_12 % BASE]
    }

    /** `MW-XXXX-XXXX-XXXX` for a canonical (or any valid) code. */
    fun format(code: String): String {
        val c = normalize(code) ?: throw IllegalArgumentException("not a valid Mawja code")
        return "$PREFIX-${c.substring(0, 4)}-${c.substring(4, 8)}-${c.substring(8, 12)}"
    }

    /** The Firestore document id: lowercase hex SHA-256 of [HASH_PREFIX] + the canonical code. */
    fun hash(code: String): String {
        val c = normalize(code) ?: throw IllegalArgumentException("not a valid Mawja code")
        val digest = MessageDigest.getInstance("SHA-256").digest((HASH_PREFIX + c).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** The last group (4 symbols), kept beside the hash so staff can tell codes apart. */
    fun hint(code: String): String = (normalize(code) ?: throw IllegalArgumentException("not a valid Mawja code")).takeLast(4)

    /** One new formatted code. */
    fun generate(random: Random = secureRandom()): String {
        val payload = buildString { repeat(PAYLOAD_LENGTH) { append(ALPHABET[random.nextInt(BASE)]) } }
        return format(payload + checkSymbol(payload))
    }

    /** [count] distinct formatted codes. */
    fun generateBatch(
        count: Int,
        random: Random = secureRandom(),
    ): List<String> {
        require(count in 1..MAX_BATCH) { "count must be 1..$MAX_BATCH" }
        val out = LinkedHashSet<String>(count)
        while (out.size < count) out += generate(random)
        return out.toList()
    }

    const val MAX_BATCH = 500

    private fun secureRandom(): Random = SecureRandom().asKotlinRandom()

    private fun weightedSum(code: String): Int {
        var sum = 0
        code.forEachIndexed { i, ch -> sum += (i + 1) * ALPHABET.indexOf(ch) }
        return sum
    }
}
