/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.codes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MawjaCodesTest {
    // Shared vectors: the same table is in admin-web/test/codes.test.ts and docs/codes.md.
    private val vectors =
        listOf(
            "MW-2222-2222-2222" to "d87c41c4f35d52bf4fcf895f6441b9e8b0378a01f34a2e8816a7f501b52a5111",
            "MW-ABCD-EFGH-JKM4" to "2e7dc17e68f222019c828129cf505158ad2646da6a285dbadf2dc2bc895b15af",
            "MW-ZZZZ-ZZZZ-ZZZQ" to "07acaede33ac2f043887aef08a467e94781f4f45f7fcd70d5316ef98513b484d",
            "MW-CZ6E-P34V-K5DG" to "2f12de93a40cbf1dd9f7325c01ab2839338bd9959269b3dfe904a54b4c3e59de",
            "MW-M3YJ-834F-F49Z" to "2f188ef47c2240ee6d5138a5ede5042a927d33794560d9f043838b24262a13e4",
        )

    @Test
    fun vectorsMatchTheSpec() {
        vectors.forEach { (code, hash) ->
            assertTrue(code, MawjaCodes.isValid(code))
            assertEquals(code, MawjaCodes.format(code))
            assertEquals(hash, MawjaCodes.hash(code))
        }
        assertEquals('4', MawjaCodes.checkSymbol("ABCDEFGHJKM"))
        assertEquals('Q', MawjaCodes.checkSymbol("ZZZZZZZZZZZ"))
    }

    @Test
    fun normalisationAcceptsHowPeopleTypeIt() {
        val canonical = "ABCDEFGHJKM4"
        listOf(
            "MW-ABCD-EFGH-JKM4",
            "mw-abcd-efgh-jkm4",
            "  abcd efgh jkm4 ",
            "ABCD–EFGH—JKM4",
            "MWABCDEFGHJKM4",
            "ＭＷ－ＡＢＣＤ－ＥＦＧＨ－ＪＫＭ４", // full-width
            "ABCD-EFGH-JKM٤", // Arabic-Indic digit
            "ABCD-EFGH-JKM۴", // Extended Arabic-Indic digit
            "\u200FABCD-EFGH-JKM4\u200E",
        ).forEach { assertEquals(it, canonical, MawjaCodes.normalize(it)) }
        assertEquals(MawjaCodes.hash("MW-ABCD-EFGH-JKM4"), MawjaCodes.hash("abcd efgh jkm٤"))
    }

    @Test
    fun parseExplainsWhatIsWrong() {
        assertEquals(MawjaCodes.Parsed.WrongLength, MawjaCodes.parse("MW-ABCD-EFGH"))
        assertEquals(MawjaCodes.Parsed.WrongLength, MawjaCodes.parse(""))
        assertEquals(MawjaCodes.Parsed.BadCharacter, MawjaCodes.parse("MW-ABCD-EFGH-JK0O"))
        assertEquals(MawjaCodes.Parsed.BadCharacter, MawjaCodes.parse("MW-1BCD-EFGH-JKM4"))
        assertEquals(MawjaCodes.Parsed.BadCharacter, MawjaCodes.parse("MW-ABCD-EFGH-JKL4"))
        assertEquals(MawjaCodes.Parsed.BadCheck, MawjaCodes.parse("MW-ABCD-EFGH-JKM5"))
        // A 12-symbol code that itself starts with MW keeps its symbols.
        val p = "MW" + "ABCDEFGHJ"
        val own = p + MawjaCodes.checkSymbol(p)
        assertEquals(own, MawjaCodes.normalize(own))
    }

    @Test
    fun everySingleTypoAndNeighbourSwapIsCaught() {
        val rnd = Random(42)
        repeat(200) {
            val code = MawjaCodes.normalize(MawjaCodes.generate(rnd))!!
            for (i in 0 until MawjaCodes.LENGTH) {
                for (c in MawjaCodes.ALPHABET) {
                    if (c == code[i]) continue
                    val typo = code.substring(0, i) + c + code.substring(i + 1)
                    assertFalse(typo, MawjaCodes.isValid(typo))
                }
                if (i < MawjaCodes.LENGTH - 1 && code[i] != code[i + 1]) {
                    val swapped = code.substring(0, i) + code[i + 1] + code[i] + code.substring(i + 2)
                    assertFalse(swapped, MawjaCodes.isValid(swapped))
                }
            }
        }
    }

    @Test
    fun generatedCodesAreValidDistinctAndUnambiguous() {
        val batch = MawjaCodes.generateBatch(300)
        assertEquals(300, batch.toSet().size)
        batch.forEach { code ->
            assertTrue(code.matches(Regex("^MW-[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}$")))
            assertTrue(MawjaCodes.isValid(code))
            assertEquals(64, MawjaCodes.hash(code).length)
            assertEquals(code.takeLast(4), MawjaCodes.hint(code))
        }
        listOf('0', 'O', '1', 'I', 'L').forEach { assertFalse(MawjaCodes.ALPHABET.contains(it)) }
        assertEquals(31, MawjaCodes.ALPHABET.length)
    }

    @Test
    fun invalidInputHasNoHash() {
        assertNull(MawjaCodes.normalize("MW-ABCD-EFGH-JKM5"))
        assertTrue(runCatching { MawjaCodes.hash("nope") }.isFailure)
    }
}
