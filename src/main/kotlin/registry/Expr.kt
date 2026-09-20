package registry

sealed interface Expr {
    data class Literal(val value: Any?) : Expr
    data class Path(val segments: List<String>) : Expr
    data class Not(val inner: Expr) : Expr
    data class And(val left: Expr, val right: Expr) : Expr
    data class Or(val left: Expr, val right: Expr) : Expr
    data class Cmp(val op: String, val left: Expr, val right: Expr) : Expr
    data class In(val element: Expr, val container: Expr, val negate: Boolean) : Expr
    data class Contains(val container: Expr, val element: Expr, val negate: Boolean) : Expr
}

object ExprParser {
    fun parse(input: String): Expr {
        val tokens = Lexer(input).tokenize()
        val p = State(tokens)
        val e = p.parseOr()
        require(p.peek() == null) { "unexpected token '${p.peek()}'" }
        return e
    }

    private class State(val tokens: List<String>) {
        var pos = 0
        fun peek(): String? = tokens.getOrNull(pos)
        fun next(): String = tokens[pos++]

        fun parseOr(): Expr {
            var left = parseAnd()
            while (peek() == "||" || peek() == "or") {
                next()
                left = Expr.Or(left, parseAnd())
            }
            return left
        }

        fun parseAnd(): Expr {
            var left = parseNot()
            while (peek() == "&&" || peek() == "and") {
                next()
                left = Expr.And(left, parseNot())
            }
            return left
        }

        fun parseNot(): Expr {
            if (peek() == "!") { next(); return Expr.Not(parseNot()) }
            if (peek() == "not") { next(); return Expr.Not(parseNot()) }
            return parseCmp()
        }

        fun parseCmp(): Expr {
            val left = parsePrimary()
            val op = peek()
            if (op != null && op in setOf("==", "!=", ">", ">=", "<", "<=")) {
                next()
                val right = parsePrimary()
                return Expr.Cmp(op, left, right)
            }
            if (op == "contains") {
                next()
                val right = parsePrimary()
                return Expr.Contains(left, right, false)
            }
            if (op == "in") {
                next()
                val right = parsePrimary()
                return Expr.In(left, right, false)
            }
            return left
        }

        fun parsePrimary(): Expr {
            val t = next()
            return when {
                t == "(" -> {
                    val e = parseOr()
                    require(next() == ")") { "missing )" }
                    e
                }
                t == "[" -> {
                    val items = ArrayList<Any?>()
                    if (peek() != "]") {
                        while (true) {
                            val v = parsePrimary()
                            require(v is Expr.Literal) { "array items must be literals" }
                            items.add(v.value)
                            if (peek() == ",") { next(); continue }
                            break
                        }
                    }
                    require(next() == "]") { "missing ]" }
                    Expr.Literal(items)
                }
                t.startsWith("'") -> Expr.Literal(unquote(t, '\''))
                t.startsWith("\"") -> Expr.Literal(unquote(t, '"'))
                t == "true" -> Expr.Literal(true)
                t == "false" -> Expr.Literal(false)
                t == "null" -> Expr.Literal(null)
                t.toDoubleOrNull() != null ->
                    Expr.Literal(if (t.any { it == '.' }) t.toDouble() else t.toLong())
                else -> {
                    val segments = t.split('.').filter { it.isNotEmpty() }
                    require(segments.isNotEmpty()) { "bad identifier '$t'" }
                    Expr.Path(segments)
                }
            }
        }

        private fun unquote(raw: String, q: Char): String {
            val inner = raw.substring(1, raw.length - 1)
            return if (q == '\'') inner.replace("\\'", "'").replace("\\\\", "\\")
            else Json.parse(raw) as String
        }
    }

    private class Lexer(val input: String) {
        private var i = 0

        fun tokenize(): List<String> {
            val out = ArrayList<String>()
            while (i < input.length) {
                val c = input[i]
                when {
                    c.isWhitespace() -> i++
                    c == '(' || c == ')' || c == '[' || c == ']' || c == ',' -> { out += c.toString(); i++ }
                    c == '&' || c == '|' -> {
                        require(i + 1 < input.length && input[i + 1] == c) { "bad operator at $i" }
                        out += "$c$c"; i += 2
                    }
                    c == '=' || c == '!' || c == '>' || c == '<' -> {
                        if (i + 1 < input.length && input[i + 1] == '=') { out += "$c="; i += 2 }
                        else { out += c.toString(); i++ }
                    }
                    c == '"' || c == '\'' -> out += readString(c)
                    else -> out += readWord()
                }
            }
            return out
        }

        private fun readString(q: Char): String {
            val start = i
            i++
            while (i < input.length) {
                if (input[i] == '\\') { i += 2; continue }
                if (input[i] == q) { i++; return input.substring(start, i) }
                i++
            }
            throw IllegalArgumentException("unterminated string")
        }

        private fun readWord(): String {
            val start = i
            while (i < input.length) {
                val c = input[i]
                if (c.isWhitespace() || c in "()[]<>=!,&|") break
                i++
            }
            return input.substring(start, i)
        }
    }
}

object ExprEvaluator {
    fun evalBoolean(expr: Expr, context: Map<String, Any?>): Boolean = truthy(eval(expr, context))

    fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> value.isNotEmpty()
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }

    fun eval(expr: Expr, ctx: Map<String, Any?>): Any? = when (expr) {
        is Expr.Literal -> expr.value
        is Expr.Path -> resolve(ctx, expr.segments)
        is Expr.Not -> !evalBoolean(expr.inner, ctx)
        is Expr.And -> evalBoolean(expr.left, ctx) && evalBoolean(expr.right, ctx)
        is Expr.Or -> evalBoolean(expr.left, ctx) || evalBoolean(expr.right, ctx)
        is Expr.Cmp -> compare(expr, ctx)
        is Expr.In -> {
            val element = eval(expr.element, ctx)
            val container = eval(expr.container, ctx)
            val found = container is Collection<*> && container.contains(element)
            found != expr.negate
        }
        is Expr.Contains -> {
            val container = eval(expr.container, ctx)
            val element = eval(expr.element, ctx)
            val found = container is Collection<*> && container.contains(element)
            found != expr.negate
        }
    }

    private fun resolve(ctx: Map<String, Any?>, segments: List<String>): Any? {
        var cur: Any? = ctx
        for (s in segments) {
            cur = when (cur) {
                is Map<*, *> -> cur[s]
                else -> return null
            }
        }
        return cur
    }

    private fun compare(expr: Expr.Cmp, ctx: Map<String, Any?>): Boolean {
        val a = eval(expr.left, ctx)
        val b = eval(expr.right, ctx)
        return when (expr.op) {
            "==" -> a == b
            "!=" -> a != b
            else -> {
                val cmp = compareValuesUnordered(a, b) ?: return false
                when (expr.op) {
                    ">" -> cmp > 0
                    ">=" -> cmp >= 0
                    "<" -> cmp < 0
                    "<=" -> cmp <= 0
                    else -> false
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun compareValuesUnordered(a: Any?, b: Any?): Int? {
        if (a == null || b == null) return null
        if (a is Number && b is Number) return a.toDouble().compareTo(b.toDouble())
        if (a is String && b is String) return a.compareTo(b)
        if (a is Boolean && b is Boolean) return a.compareTo(b)
        return null
    }
}
