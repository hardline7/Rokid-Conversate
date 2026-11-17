package app.conversate.glass.meeting

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.SecretKey

/** All methods serialize access and return detached JSON values.
 * Metadata is atomically encrypted; confirmed utterances use a durable encrypted append log.
 */
class MeetingStore(
    private val directory: File,
    key: SecretKey,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(File(context.filesDir, "meetings"), Vault.androidKey())
    private val vault = Vault(key)
    private val sessionsDirectory = File(directory, "sessions")
    private val eventCache = linkedMapOf<String, LinkedHashMap<String, JSONObject>>()

    init {
        check(directory.isDirectory || directory.mkdirs()) { L10n.t("저장 폴더를 만들 수 없습니다") }
        check(sessionsDirectory.isDirectory || sessionsDirectory.mkdirs()) { L10n.t("회의 폴더를 만들 수 없습니다") }
        // A process death never silently resumes native recognition or invents a completed recap.
        metadataFiles().forEach { file ->
            val metadata = readMetadata(file.nameWithoutExtension)
            var changed = false
            if (metadata.optString("status") in setOf("running", "paused", "starting", "stopping")) {
                metadata.put("status", "interrupted").put("endedAt", clock())
                changed = true
            }
            // Final recaps can be pending after the meeting is already ended. No
            // in-memory AI job survives process death, regardless of session status.
            if (metadata.optString("summaryStatus") == "pending") {
                metadata.put("summaryStatus", "unavailable")
                changed = true
            }
            if (changed) writeMetadata(metadata)
        }
    }

    @Synchronized fun settings(): JSONObject {
        val file = File(directory, "settings.enc")
        return if (file.exists()) JSONObject(vault.read(file, "settings")) else defaultSettings()
    }

    @Synchronized fun saveSettings(input: JSONObject): JSONObject {
        val result = settings()
        for (name in listOf("captions", "cues", "summaries", "cueAutoPopup")) if (input.has(name)) {
            require(input.opt(name) is Boolean) { L10n.t("{0} 값은 true 또는 false여야 합니다" , name) }
            result.put(name, input.getBoolean(name))
        }
        if (input.has("fontSize")) {
            val size = input.getInt("fontSize")
            require(size in listOf(14, 16, 18)) { L10n.t("글자 크기는 14, 16, 18 중 선택해 주세요") }
            result.put("fontSize", size)
        }
        if (input.has("selectedNoteIds")) {
            val requested = input.getJSONArray("selectedNoteIds")
            val existing = notes().objects().map { it.getString("id") }.toSet()
            val ids = (0 until requested.length()).map { requested.getString(it) }.distinct()
            require(ids.all { it in existing }) { L10n.t("선택한 자료를 찾을 수 없습니다") }
            result.put("selectedNoteIds", JSONArray(ids))
        }
        vault.write(File(directory, "settings.enc"), result.toString(), "settings")
        return clone(result)
    }

    @Synchronized fun notes(): JSONArray {
        val file = File(directory, "notes.enc")
        return if (file.exists()) JSONArray(vault.read(file, "notes")) else JSONArray()
    }

    @Synchronized fun saveNote(input: JSONObject): JSONObject {
        val title = input.getString("title").trim()
        val text = input.getString("text").trim()
        require(title.isNotEmpty() && title.length <= 120) { L10n.t("제목은 1~120자로 입력해 주세요") }
        require(text.isNotEmpty() && text.length <= 60000) { L10n.t("자료 내용은 1~60,000자로 입력해 주세요") }
        val all = notes().objects().toMutableList()
        val requestedId = input.optString("id", "")
        val index = if (requestedId.isEmpty()) -1 else all.indexOfFirst { it.getString("id") == requestedId }
        require(requestedId.isEmpty() || index >= 0) { L10n.t("수정할 자료를 찾을 수 없습니다") }
        require(index >= 0 || all.size < 100) { L10n.t("Prep Notes는 최대 100개입니다") }
        val now = clock()
        val note = JSONObject().put("id", if (index >= 0) requestedId else UUID.randomUUID().toString())
            .put("title", title).put("text", text)
            .put("createdAt", if (index >= 0) all[index].getLong("createdAt") else now).put("updatedAt", now)
        if (index >= 0) all[index] = note else all.add(note)
        vault.write(File(directory, "notes.enc"), JSONArray(all).toString(), "notes")
        return clone(note)
    }

    @Synchronized fun deleteNote(id: String) {
        val all = notes().objects().filterNot { it.getString("id") == id }
        vault.write(File(directory, "notes.enc"), JSONArray(all).toString(), "notes")
        val current = settings()
        val ids = current.getJSONArray("selectedNoteIds")
        current.put("selectedNoteIds", JSONArray((0 until ids.length()).map { ids.getString(it) }.filterNot { it == id }))
        vault.write(File(directory, "settings.enc"), current.toString(), "settings")
    }

    @Synchronized fun createSession(): JSONObject {
        val settings = settings()
        val ids = settings.getJSONArray("selectedNoteIds")
        val selected = (0 until ids.length()).map { ids.getString(it) }.toSet()
        val snapshots = notes().objects().filter { it.getString("id") in selected }
        require(snapshots.sumOf { it.getString("text").length } <= 100000) { L10n.t("선택한 회의 자료는 총 100,000자 이하여야 합니다") }
        val now = clock()
        val session = JSONObject().put("id", UUID.randomUUID().toString())
            .put("title", L10n.t("회의 ") + SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.forLanguageTag(L10n.tag)).format(Date(now)))
            .put("createdAt", now).put("endedAt", JSONObject.NULL).put("updatedAt", now)
            .put("status", "running").put("summaryStatus", "unavailable")
            .put("summary", emptySummary()).put("cues", JSONArray()).put("noteSnapshots", JSONArray(snapshots))
        writeMetadata(session)
        return session.put("utterances", JSONArray())
    }

    @Synchronized fun session(id: String): JSONObject? {
        validateId(id)
        if (!metadataFile(id).exists()) return null
        return clone(readMetadata(id).put("utterances", JSONArray(events(id).values.toList())))
    }

    @Synchronized fun sessions(query: String = ""): JSONArray {
        val needle = query.trim().lowercase(Locale.ROOT)
        val all = metadataFiles().map { file ->
            val id = file.nameWithoutExtension
            val metadata = readMetadata(id)
            val utterances = events(id)
            val matches = needle.isEmpty() || metadata.optString("title").lowercase(Locale.ROOT).contains(needle) ||
                metadata.optJSONObject("summary")?.toString()?.lowercase(Locale.ROOT)?.contains(needle) == true ||
                utterances.values.any { it.optString("text").lowercase(Locale.ROOT).contains(needle) }
            if (!matches) null else JSONObject().put("id", id).put("title", metadata.getString("title"))
                .put("createdAt", metadata.getLong("createdAt")).put("endedAt", metadata.opt("endedAt") ?: JSONObject.NULL)
                .put("status", metadata.getString("status")).put("summaryStatus", metadata.optString("summaryStatus", "unavailable"))
                .put("utteranceCount", utterances.size).put("cueCount", metadata.optJSONArray("cues")?.length() ?: 0)
        }.filterNotNull().sortedByDescending { it.getLong("createdAt") }
        return JSONArray(all)
    }

    @Synchronized fun saveSession(session: JSONObject) {
        val id = session.getString("id")
        validateId(id)
        require(metadataFile(id).exists()) { L10n.t("회의 기록을 찾을 수 없습니다") }
        val current = readMetadata(id)
        val copy = clone(current)
        for (name in session.keys()) copy.put(name, session.get(name))
        copy.remove("utterances")
        // Snapshot context and identifiers remain stable if a UI or worker holds stale JSON.
        for (immutable in listOf("id", "createdAt", "noteSnapshots")) copy.put(immutable, current.get(immutable))
        require(copy.optString("status") in setOf("running", "paused", "ended", "interrupted", "starting", "stopping")) { L10n.t("잘못된 회의 상태입니다") }
        require(copy.optString("title").length in 1..200) { L10n.t("회의 제목은 1~200자여야 합니다") }
        copy.put("updatedAt", clock())
        writeMetadata(copy)
    }

    @Synchronized fun appendUtterance(id: String, event: JSONObject): Boolean {
        validateId(id)
        require(metadataFile(id).exists()) { L10n.t("회의 기록을 찾을 수 없습니다") }
        if (event.has("final") && !event.getBoolean("final")) return false
        val eventId = event.getString("id")
        require(eventId.length in 1..200) { L10n.t("발화 식별자가 올바르지 않습니다") }
        val text = event.getString("text")
        require(text.length <= 16000) { L10n.t("발화가 너무 깁니다") }
        val all = events(id)
        val previous = all[eventId]
        val audioPath = event.optString("audioPath", "")
        val number = event.optInt("number", 0)
        val role = event.optString("role", "")
        if (previous != null && previous.getString("text") == text && previous.optString("audioPath") == audioPath &&
            previous.optInt("number") == number && previous.optString("role") == role) return false
        val normalized = JSONObject().put("id", eventId).put("text", text)
            .put("at", previous?.getLong("at") ?: event.optLong("at", clock()))
            .put("updatedAt", clock()).put("audioPath", audioPath).put("number", number).put("role", role).put("final", true)
        val line = vault.seal(normalized.toString(), "events:$id") + "\n"
        val file = eventsFile(id)
        RandomAccessFile(file, "rw").use { output ->
            // An incomplete final frame is uncommitted. Preserve earlier records and repair only that tail.
            if (output.length() > 0) {
                var end = output.length()
                output.seek(end - 1)
                if (output.read() != '\n'.code) {
                    do { end--; if (end > 0) { output.seek(end - 1) } } while (end > 0 && output.read() != '\n'.code)
                    output.setLength(end)
                }
            }
            output.seek(output.length()); output.write(line.toByteArray(Charsets.US_ASCII)); output.fd.sync()
        }
        all[eventId] = normalized
        return true
    }

    @Synchronized fun deleteSession(id: String) {
        validateId(id)
        // Named files are confined to the UUID session folder; never recurse over user-provided paths.
        for (file in listOf(metadataFile(id), eventsFile(id))) if (file.exists()) check(file.delete()) { L10n.t("회의 기록을 삭제할 수 없습니다") }
        eventCache.remove(id)
    }

    @Synchronized fun exportMarkdown(id: String): String {
        val session = session(id) ?: throw IllegalArgumentException(L10n.t("회의 기록을 찾을 수 없습니다"))
        val summary = session.optJSONObject("summary") ?: emptySummary()
        return buildString {
            appendLine("# ${session.getString("title").replace('\n', ' ')}")
            appendLine(); appendLine(L10n.t("- 상태: {0}" , session.getString("status")))
            appendLine(L10n.t("- 요약 상태: {0}" , session.optString("summaryStatus", "unavailable")))
            appendLine(); appendLine("## Summary"); appendLine(); appendLine(L10n.t("### 개요")); appendLine(summary.optString("overview", ""))
            for ((name, label) in listOf("conclusions" to L10n.t("핵심 결론"), "discussion" to L10n.t("논의 사항"), "nextSteps" to L10n.t("다음 단계"))) {
                appendLine(); appendLine("### $label")
                val items = summary.optJSONArray(name) ?: JSONArray()
                for (index in 0 until items.length()) appendLine("- ${items.optString(index)}")
            }
            appendLine(); appendLine(L10n.t("## 발화 묶음 요약"))
            val groups = session.optJSONArray("liveSummaries") ?: JSONArray()
            for (i in 0 until groups.length()) {
                val group = groups.getJSONObject(i)
                appendLine(); appendLine("### ${i + 1}. ${group.optString("title")}")
                appendLine(group.optString("body"))
                val terms = group.optJSONArray("terms") ?: JSONArray()
                for (j in 0 until terms.length()) {
                    val term = terms.getJSONObject(j)
                    appendLine("- ${term.optString("term")}: ${term.optString("definition")}")
                }
            }
            appendLine(); appendLine("## Cue List")
            for (cue in (session.optJSONArray("cues") ?: JSONArray()).objects()) {
                appendLine(); appendLine("### [${cue.optString("type")}] ${cue.optString("title")}")
                appendLine(cue.optString("body"))
                val source = cue.optString("sourceTitle")
                if (source.isNotEmpty()) appendLine(L10n.t("출처: {0}" , source))
            }
            appendLine(); appendLine("## Transcription")
            for (event in session.getJSONArray("utterances").objects()) {
                appendLine(); appendLine(event.getString("text"))
            }
        }
    }

    private fun events(id: String): LinkedHashMap<String, JSONObject> {
        eventCache[id]?.let { return it }
        val all = linkedMapOf<String, JSONObject>()
        val file = eventsFile(id)
        if (file.exists()) {
            val text = file.readText(Charsets.US_ASCII)
            // A crash between append and newline cannot erase already committed speech.
            val end = text.lastIndexOf('\n')
            if (end >= 0) for (line in text.substring(0, end).split('\n')) if (line.isNotEmpty()) {
                val event = JSONObject(vault.open(line, "events:$id"))
                all[event.getString("id")] = event
            }
        }
        if (eventCache.size >= 8) eventCache.remove(eventCache.keys.first())
        eventCache[id] = all
        return all
    }
    private fun readMetadata(id: String): JSONObject = JSONObject(vault.read(metadataFile(id), "session:$id"))
    private fun writeMetadata(metadata: JSONObject) {
        val id = metadata.getString("id"); validateId(id)
        val copy = clone(metadata).apply { remove("utterances") }
        vault.write(metadataFile(id), copy.toString(), "session:$id")
    }
    private fun metadataFiles(): List<File> = sessionsDirectory.listFiles()?.filter { it.name.matches(Regex("[0-9a-f-]{36}\\.enc")) } ?: emptyList()
    private fun metadataFile(id: String) = File(sessionsDirectory, "$id.enc")
    private fun eventsFile(id: String) = File(sessionsDirectory, "$id.events")
    private fun validateId(id: String) { require(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) { L10n.t("잘못된 회의 식별자입니다") } }
    private fun clone(value: JSONObject) = JSONObject(value.toString())
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun defaultSettings() = JSONObject().put("captions", true).put("cues", true).put("summaries", true)
        .put("cueAutoPopup", true).put("fontSize", 16).put("selectedNoteIds", JSONArray())
    private fun emptySummary() = JSONObject().put("overview", "").put("conclusions", JSONArray())
        .put("discussion", JSONArray()).put("nextSteps", JSONArray())
}
