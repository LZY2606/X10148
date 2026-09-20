package registry

import java.io.StringWriter

/**
 * Minimal dependency-free JSON codec used by the file-based persistence layer.
 * Values are represented as null, Boolean, Double, String, List<Any?>, Map<String, Any?>.
 * Doubles that carry integral values are written without a decimal point so the
 * on-disk form stays stable across platforms.
 */
object Json {
    fun parse(text: String): Any? = Parser(text).parseValue()

    fun stringify(value: Any?): String {
        val writer = StringWriter()
        write(writer, value)
        return writer.toString()
    }

    private fun write(out: java.io.Writer, value: Any?) {
        when (value) {
            null -> out.write("null")
            is Boolean -> out.write(value.toString())
            is Number -> {
                val d = value.toDouble()
                if (d.isFinite() && d == d.toLong().toDouble()) out.write(d.toLong().toString())
                else out.write(d.toString())
            }
            is String -> writeString(out, value)
            is List<*> -> {
                out.write("[")
                value.forEachIndexed { index, item ->
                    if (index > 0) out.write(",")
                    write(out, item)
                }
                out.write("]")
            }
            is Map<*, *> -> {
                out.write("{")
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) out.write(",")
                    writeString(out, entry.key.toString())
                    out.write(":")
                    write(out, entry.value)
                }
                out.write("}")
            }
            else -> writeString(out, value.toString())
        }
    }

    private fun writeString(out: java.io.Writer, value: String) {
        out.write("\"")
        for (char in value) {
            when (char) {
                '"' -> out.write("\\\"")
                '\\' -> out.write("\\\\")
                '\n' -> out.write("\\n")
                '\r' -> out.write("\\r")
                '\t' -> out.write("\\t")
                '\b' -> out.write("\\b")
                '\u000C' -> out.write("\\f")
                else -> if (char.code < 0x20) {
                    out.write("\\u%04x".format(char.code))
                } else {
                    out.write(char.code)
                }
            }
        }
        out.write("\"")
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun parseValue(): Any? {
            skipWhitespace()
            val value = when (text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> parseNumber()
            }
            skipWhitespace()
            return value
        }

        private fun parseObject(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            expect('{')
            skipWhitespace()
            if (peek() == '}') { pos++; return result }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue()
                result[key] = value
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    '}' -> break
                    else -> error("Expected ',' or '}' at $pos")
                }
            }
            return result
        }

        private fun parseArray(): List<Any?> {
            val result = ArrayList<Any?>()
            expect('[')
            skipWhitespace()
            if (peek() == ']') { pos++; return result }
            while (true) {
                result.add(parseValue())
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    ']' -> break
                    else -> error("Expected ',' or ']' at $pos")
                }
            }
            return result
        }

        private fun parseString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                val char = next()
                when (char) {
                    '"' -> return builder.toString()
                    '\\' -> when (val escaped = next()) {
                        '"' -> builder.append('"')
                        '\\' -> builder.append('\\')
                        '/' -> builder.append('/')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'u' -> {
                            val hex = text.substring(pos, pos + 4)
                            pos += 4
                            builder.append(hex.toInt(16).toChar())
                        }
                        else -> error("Bad escape \\$escaped at $pos")
                    }
                    else -> if (char.code < 0x20) error("Unescaped control character at $pos")
                    else builder.append(char)
                }
            }
        }

        private fun parseBoolean(): Boolean =
            if (text.startsWith("true", pos)) { pos += 4; true }
            else if (text.startsWith("false", pos)) { pos += 5; false }
            else error("Invalid literal at $pos")

        private fun parseNull(): Any? {
            if (text.startsWith("null", pos)) pos += 4 else error("Invalid literal at $pos")
            return null
        }

        private fun parseNumber(): Double {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            val raw = text.substring(start, pos)
            return raw.toDoubleOrNull() ?: error("Invalid number '$raw' at $start")
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun peek(): Char {
            if (pos >= text.length) error("Unexpected end of input")
            return text[pos]
        }

        private fun next(): Char {
            if (pos >= text.length) error("Unexpected end of input")
            return text[pos++]
        }

        private fun expect(char: Char) {
            if (next() != char) error("Expected '$char' at $pos")
        }
    }
}
