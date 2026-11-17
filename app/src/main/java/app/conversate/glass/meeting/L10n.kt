package app.conversate.glass.meeting

import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Only application-owned messages are translated; transcript/notes never pass here. */
object L10n {
    @Volatile var tag = "ko-KR"; private set
    private var english = JSONObject()
    private var translated = JSONObject()
    private var directory: File? = null
    private val knownLabels = linkedMapOf<String, String>()
    @Volatile var revision = 0; private set
    private val placeholder = Regex("\\{\\d+\\}")

    fun normalize(value: String): String {
        if (!value.matches(Regex("[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})*"))) return "en-US"
        return Locale.forLanguageTag(value.replace('_', '-')).toLanguageTag().takeUnless { it == "und" } ?: "en-US"
    }
    @Synchronized fun initialize(messages: JSONObject, cache: File?, language: String) {
        english = JSONObject(messages.toString()); directory = cache; knownLabels.clear()
        english.keys().forEach { knownLabels[english.getString(it)] = it }
        tag = ""; switchLanguage(language)
    }
    @Synchronized fun switchLanguage(language: String): Boolean {
        val next = normalize(language)
        if (tag == next) return false
        tag = next
        translated = runCatching { cacheFile()?.takeIf { it.exists() }?.readText()?.let(::JSONObject) }.getOrNull() ?: JSONObject()
        translated.keys().forEach { knownLabels[translated.getString(it)] = it }
        revision++; return true
    }
    @Synchronized fun t(source: String, vararg values: Any?): String {
        val text = if (tag.startsWith("ko")) source else translated.optString(source).ifBlank { english.optString(source, source) }
        return placeholder.replace(text) { match -> values.getOrNull(match.value.drop(1).dropLast(1).toInt())?.toString() ?: match.value }
    }
    /** Re-render stored status labels when a language pack arrives, without changing user text. */
    @Synchronized fun label(value: String): String = t(knownLabels[value] ?: value)
    @Synchronized fun snapshot(): JSONObject {
        val messages = JSONObject()
        if (!tag.startsWith("ko")) english.keys().forEach { key -> messages.put(key, translated.optString(key).ifBlank { english.getString(key) }) }
        return JSONObject().put("tag", tag).put("revision", revision).put("ready", isReady())
            .put("fallback", "en").put("messages", messages)
    }
    @Synchronized fun isReady() = tag.startsWith("ko") || tag.startsWith("en") || english.keys().asSequence().all { translated.has(it) }
    @Synchronized fun missing(limit: Int = 20): List<String> = if (isReady()) emptyList() else
        english.keys().asSequence().filter { !translated.has(it) }.take(limit).toList()
    @Synchronized fun input(keys: List<String>): JSONObject = JSONObject().put("languageTag", tag).put("messages",
        JSONObject().apply { keys.forEachIndexed { i, key -> put("m$i", english.getString(key)) } })
    @Synchronized fun accept(language: String, keys: List<String>, output: JSONObject): Boolean {
        if (language != tag) return false
        val messages = output.optJSONObject("translations") ?: return false
        if (messages.length() != keys.size) return false
        val valid = keys.mapIndexed { i, source ->
            val value = messages.opt("m$i") as? String ?: return false
            val original = english.getString(source)
            if (value.isBlank() || value.length > maxOf(160, original.length * 4) ||
                placeholder.findAll(value).map { it.value }.sorted().toList() !=
                placeholder.findAll(original).map { it.value }.sorted().toList()) return false
            source to value
        }
        valid.forEach { (source, value) -> translated.put(source, value); knownLabels[value] = source }
        cacheFile()?.let { file ->
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".tmp"); temp.writeText(translated.toString()); check(temp.renameTo(file))
        }
        revision++; return true
    }
    private fun cacheFile(): File? = directory?.let { File(it, "$tag.json") }
}
