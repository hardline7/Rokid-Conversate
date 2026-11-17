package app.conversate.glass.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.CharacterCodingException

class CompanionUtf8Test {
    @Test fun koreanAndEmojiSurviveExactByteLengthWithoutCharsetHeader() {
        val content = "회의 자료입니다. 다음 주 일정과 제품 출시 🚀"
        val bytes = "{\"text\":\"$content\"}".toByteArray(Charsets.UTF_8)
        // A network stream may return only part of a multi-byte Korean character per read.
        val chunks = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, minOf(length, 2))
        }
        assertEquals(content, CompanionServer.readJsonUtf8(chunks, bytes.size.toLong()).getString("text"))
    }

    @Test fun malformedUtf8IsRejectedInsteadOfReplacingWithUfffd() {
        val bytes = byteArrayOf(0x7b, 0x22, 0x78, 0x22, 0x3a, 0x22, 0xc3.toByte(), 0x28, 0x22, 0x7d)
        assertThrows(CharacterCodingException::class.java) {
            CompanionServer.readJsonUtf8(bytes.inputStream(), bytes.size.toLong())
        }
    }

    @Test fun truncatedAndOversizedRequestsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CompanionServer.readJsonUtf8("{}".byteInputStream(), 3) }
        assertThrows(IllegalArgumentException::class.java) { CompanionServer.readJsonUtf8("{}".byteInputStream(), 12L * 1024 * 1024 + 1) }
    }
}
