package registry

typealias JsonObject = Map<String, Any?>
typealias JsonArray = List<Any?>

object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        require(p.at >= text.length) { "trailing content at ${p.at}" }
        return v
    }

    fun write(value: Any?, canonical: Boolean = false): String {
        val sb = StringBuilder()
        writeInto(sb, value, canonical)
        return sb.toString()
    }

    private fun writeInto(sb: StringBuilder, value: Any?, canonical: Boolean) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(value.toString())
            is Number -> sb.append(formatNumber(value))
            is String -> writeString(sb, value)
            is Map<*, *> -> {
                sb.append('{')
                val keys: List<String> = if (canonical) value.keys.map { it as String }.sorted()
                else value.keys.map { it as String }
                keys.forEachIndexed { i, k ->
                    if (i > 0) sb.append(',')
                    writeString(sb, k)
                    sb.append(':')
                    writeInto(sb, value[k], canonical)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                value.forEachIndexed { i, e ->
                    if (i > 0) sb.append(',')
                    writeInto(sb, e, canonical)
                }
                sb.append(']')
            }
            else -> writeString(sb, value.toString())
        }
    }

    private fun formatNumber(n: Number): String = when (n) {
        is Double -> if (n % 1.0 == 0.0 && n.isFinite()) n.toLong().toString() else n.toString()
        is Float -> if (n % 1.0f == 0.0f && n.isFinite()) n.toLong().toString() else n.toString()
        else -> n.toString()
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(value: Any?): JsonObject = value as JsonObject
    fun arr(value: Any?): JsonArray = value as JsonArray

    private class Parser(val text: String) {
        var at = 0

        fun skipWs() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        fun expect(ch: Char) {
            require(at < text.length && text[at] == ch) { "expected '$ch' at $at" }
            at++
        }

        fun value(): Any? {
            skipWs()
            require(at < text.length) { "unexpected end" }
            return when (text[at]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't', 'f' -> bool()
                'n' -> nul()
                else -> number()
            }
        }

        fun obj(): JsonObject {
            expect('{')
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (text[at] == '}') { at++; return m }
            while (true) {
                skipWs()
                val k = string()
                skipWs()
                expect(':')
                val v = value()
                m[k] = v
                skipWs()
                if (text[at] == ',') { at++; continue }
                expect('}')
                break
            }
            return m
        }

        fun array(): JsonArray {
            expect('[')
            val list = ArrayList<Any?>()
            skipWs()
            if (text[at] == ']') { at++; return list }
            while (true) {
                list.add(value())
                skipWs()
                if (text[at] == ',') { at++; continue }
                expect(']')
                break
            }
            return list
        }

        fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(at < text.length) { "unterminated string" }
                val c = text[at++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = text[at++]
                        when (e) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                val hex = text.substring(at, at + 4)
                                at += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalArgumentException("bad escape $e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun bool(): Boolean {
            if (text.startsWith("true", at)) { at += 4; return true }
            if (text.startsWith("false", at)) { at += 5; return false }
            throw IllegalArgumentException("bad literal at $at")
        }

        fun nul(): Any? {
            if (text.startsWith("null", at)) { at += 4; return null }
            throw IllegalArgumentException("bad literal at $at")
        }

        fun number(): Any {
            val start = at
            if (text[at] == '-') at++
            while (at < text.length && (text[at].isDigit() || text[at] in ".eE+-")) at++
            val raw = text.substring(start, at)
            return if (raw.any { it in ".eE" }) raw.toDouble() else raw.toLong()
        }
    }
}

class JsonBuild {
    private val map = LinkedHashMap<String, Any?>()
    infix fun String.to(v: Any?): Any? { map[this] = v; return null }
    fun build(): JsonObject = map
}

fun jsonObj(block: JsonBuild.() -> Unit): JsonObject = JsonBuild().apply(block).build()
