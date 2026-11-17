package app.conversate.glass.meeting

import android.content.Context
import android.util.Base64
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class PrepImporter(context: Context) {
    init { PDFBoxResourceLoader.init(context.applicationContext) }
    fun extract(input: JSONObject): JSONObject {
        val name = input.getString("name").substringAfterLast('/').substringAfterLast('\\').take(120)
        val encoded = input.getString("base64")
        require(encoded.length <= 11200000) { L10n.t("파일은 8MB 이하여야 합니다") }
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        require(bytes.size <= 8 * 1024 * 1024) { L10n.t("파일은 8MB 이하여야 합니다") }
        val text = when (name.substringAfterLast('.', "").lowercase()) {
            "txt", "md" -> Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            "pdf" -> (try { PDDocument.load(bytes) } catch (_: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
                throw IllegalArgumentException(L10n.t("암호화된 PDF는 지원하지 않습니다"))
            }).use { document ->
                require(!document.isEncrypted) { L10n.t("암호화된 PDF는 지원하지 않습니다") }
                require(document.numberOfPages <= 200) { L10n.t("PDF는 200페이지 이하여야 합니다") }
                buildString {
                    val stripper = PDFTextStripper()
                    for (page in 1..document.numberOfPages) {
                        stripper.startPage = page; stripper.endPage = page
                        append("[p.$page]\n").append(stripper.getText(document)).append('\n')
                        require(length <= 60000) { L10n.t("추출한 내용이 60,000자를 넘습니다") }
                    }
                }
            }
            else -> throw IllegalArgumentException(L10n.t("TXT, MD, PDF 파일을 올려 주세요"))
        }
        require(text.replace(Regex("\\[p\\.\\d+\\]"), "").isNotBlank()) { L10n.t("텍스트가 없습니다. 스캔 PDF는 텍스트로 변환해 주세요") }
        return JSONObject().put("title", name).put("text", text)
    }
}
