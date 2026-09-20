package registry

/** Minimal multipart/form-data parser: file bytes are kept in memory (evidence is small). */
data class Part(
    val fieldName: String,
    val text: String,
    val fileName: String? = null,
    val mediaType: String? = null,
    val bytes: ByteArray = ByteArray(0)
)

class MultipartForm(private val parts: Map<String, List<Part>>) {
    fun text(name: String): String? = parts[name]?.firstOrNull { it.fileName == null }?.text
    fun textOr(name: String, default: String): String = text(name) ?: default
    fun allText(name: String): List<String> = parts[name]?.filter { it.fileName == null }?.map { it.text } ?: emptyList()
    fun file(name: String): Part? = parts[name]?.firstOrNull { it.fileName != null && it.fileName!!.isNotBlank() }
}

object MultipartParser {
    private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())

    fun parse(body: ByteArray, contentType: String): MultipartForm {
        val marker = "boundary="
        val index = contentType.indexOf(marker)
        if (index < 0) throw ValidationException("multipart 请求缺少 boundary")
        val boundary = contentType.substring(index + marker.length).trim('"')
        val delimiter = ("--$boundary").toByteArray(Charsets.US_ASCII)
        val parts = LinkedHashMap<String, MutableList<Part>>()

        var cursor = indexOf(body, delimiter, 0)
        while (cursor >= 0) {
            var contentStart = cursor + delimiter.size
            if (contentStart + 1 < body.size && body[contentStart] == 45.toByte() && body[contentStart + 1] == 45.toByte()) break
            val next = indexOf(body, delimiter, contentStart)
            if (next < 0) break
            var partEnd = next
            if (partEnd >= 2 && body[partEnd - 2] == '\r'.code.toByte() && body[partEnd - 1] == '\n'.code.toByte()) {
                partEnd -= 2
            }
            parsePart(body, contentStart, partEnd, parts)
            cursor = next
        }
        return MultipartForm(parts)
    }

    private fun parsePart(body: ByteArray, start: Int, end: Int, parts: MutableMap<String, MutableList<Part>>) {
        val headerSep = byteArrayOf(
            '\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte()
        )
        val headerEnd = indexOf(body, headerSep, start)
        if (headerEnd < 0 || headerEnd > end) return
        val headers = String(body, start, headerEnd - start, Charsets.UTF_8)
        val valueStart = headerEnd + 4
        val disposition = headers.split("\r\n").firstOrNull { it.startsWith("Content-Disposition", true) }
            ?: return
        val name = Regex("name=\"([^\"]*)\"").find(disposition)?.groupValues?.get(1) ?: return
        val fileName = Regex("filename=\"([^\"]*)\"").find(disposition)?.groupValues?.get(1)
        val mediaType = headers.split("\r\n").firstOrNull { it.startsWith("Content-Type", true) }
            ?.substringAfter(":")?.trim()
        val raw = body.copyOfRange(valueStart, end)
        val part = if (fileName != null) {
            Part(name, "", fileName, mediaType, raw)
        } else {
            Part(name, String(raw, Charsets.UTF_8))
        }
        parts.getOrPut(name) { ArrayList() }.add(part)
    }

    private fun indexOf(body: ByteArray, target: ByteArray, from: Int): Int {
        if (target.isEmpty()) return -1
        outer@ for (i in from..(body.size - target.size)) {
            for (j in target.indices) if (body[i + j] != target[j]) continue@outer
            return i
        }
        return -1
    }
}

fun parseFormUrlEncoded(body: String): Map<String, String> {
    if (body.isBlank()) return emptyMap()
    return body.split("&").filter { it.isNotBlank() }.associate { pair ->
        val eq = pair.indexOf('=')
        val key = if (eq < 0) pair else pair.substring(0, eq)
        val value = if (eq < 0) "" else pair.substring(eq + 1)
        java.net.URLDecoder.decode(key, "UTF-8") to java.net.URLDecoder.decode(value, "UTF-8")
    }
}
