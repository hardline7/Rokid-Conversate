package app.conversate.glass.stt

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Final/correction log. Consumers take the latest line per session/path/number.
 * Partials and audio are never written. */
class TranscriptJournal(directory: File) {
    val file = File(directory, "${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.jsonl")
    private val latest = mutableMapOf<Pair<String, Int>, RokidTranscript>()
    init { check(directory.isDirectory || directory.mkdirs()) { "전사 저장 폴더를 만들 수 없습니다" } }
    fun append(event: RokidTranscript): Boolean {
        if (!event.isFinal) return false
        val key = event.audioPath to event.number
        if (latest[key] == event) return false
        val line = JSONObject().put("received_at", System.currentTimeMillis())
            .put("content", event.content).put("final", true).put("number", event.number)
            .put("audio_path", event.audioPath).put("role", event.role).toString() + "\n"
        FileOutputStream(file, true).use { out -> out.write(line.toByteArray(Charsets.UTF_8)); out.fd.sync() }
        latest[key] = event
        return true
    }
}
