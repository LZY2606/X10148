package registry

/**
 * Small predicate language evaluated over flat string attributes.
 *
 * Grammar (case-insensitive keywords):
 *   expr      := or
 *   or        := and ("or" and)*
 *   and       := not ("and" not)*
 *   not       := "not" not | comparison | "(" expr ")"
 *   comparison := value op value
 *   op        := "=" | "!=" | "~" (substring, case-insensitive)
 *   value     := identifier | quoted-string
 *
 * A bare comparison needs a relational operator; a lone identifier is illegal so
 * typos cannot silently allow everything.
 */
class ExprException(message: String) : IllegalArgumentException(message)

object Expressions {
    fun evaluate(expr: String, attributes: Map<String, String>): Boolean =
        Parser(expr).parseOr(attributes)

    fun validate(expr: String) {
        Parser(expr).parseOr(emptyMap())
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun parseOr(attributes: Map<String, String>): Boolean {
            var result = parseAnd(attributes)
            while (matchKeyword("or")) result = result or parseAnd(attributes)
            return result
        }

        private fun parseAnd(attributes: Map<String, String>): Boolean {
            var result = parseNot(attributes)
            while (matchKeyword("and")) result = result and parseNot(attributes)
            return result
        }

        private fun parseNot(attributes: Map<String, String>): Boolean {
            if (matchKeyword("not")) return !parseNot(attributes)
            skipWs()
            if (consume('(')) {
                val result = parseOr(attributes)
                skipWs()
                if (!consume(')')) throw ExprException("缺少右括号 ')'")
                return result
            }
            return parseComparison(attributes)
        }

        private fun parseComparison(attributes: Map<String, String>): Boolean {
            val left = parseValue()
            skipWs()
            val operator = when {
                consume('=') -> "="
                consume('!') -> {
                    if (!consume('=')) throw ExprException("期望操作符 !=")
                    "!="
                }
                consume('~') -> "~"
                else -> throw ExprException("子句必须是包含 =、!= 或 ~ 的比较表达式")
            }
            skipWs()
            val right = parseValue()
            val leftValue = resolve(left, attributes)
            val rightValue = resolve(right, attributes)
            return when (operator) {
                "=" -> leftValue == rightValue
                "!=" -> leftValue != rightValue
                "~" -> leftValue.lowercase().contains(rightValue.lowercase())
                else -> error("unreachable")
            }
        }

        private fun parseValue(): String {
            skipWs()
            if (pos >= text.length) throw ExprException("表达式意外结束")
            if (text[pos] == '"' || text[pos] == '\'') {
                val quote = text[pos++]
                val builder = StringBuilder()
                while (pos < text.length && text[pos] != quote) {
                    if (text[pos] == '\\' && pos + 1 < text.length) {
                        builder.append(text[pos + 1]); pos += 2
                    } else builder.append(text[pos++])
                }
                if (pos >= text.length) throw ExprException("字符串字面量缺少结束引号")
                pos++
                return builder.toString()
            }
            val start = pos
            while (pos < text.length && text[pos] !in " \t()=!~") pos++
            val token = text.substring(start, pos)
            if (token.isEmpty()) throw ExprException("缺少标识符或字符串")
            return token
        }

        private fun resolve(token: String, attributes: Map<String, String>): String =
            attributes[token] ?: token

        private fun matchKeyword(keyword: String): Boolean {
            skipWs()
            if (pos + keyword.length <= text.length &&
                text.regionMatches(pos, keyword, 0, keyword.length, ignoreCase = true)
            ) {
                val after = pos + keyword.length
                val boundary = after >= text.length || text[after] in " \t()"
                if (boundary) {
                    pos = after
                    skipWs()
                    return true
                }
            }
            return false
        }

        private fun consume(char: Char): Boolean {
            if (pos < text.length && text[pos] == char) {
                pos++
                return true
            }
            return false
        }

        private fun skipWs() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }
}
