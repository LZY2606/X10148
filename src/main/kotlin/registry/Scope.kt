package registry

data class ScopeResult(
    val totalSubjects: Int,
    val matched: List<SubjectEntry>,
    val broad: Boolean,
    val validExpression: Boolean,
    val error: String?,
) {
    fun snapshot(): ScopeSnapshot {
        val sample = matched.asSequence()
            .sortedBy { it.id }
            .take(ScopeSnapshot.SAMPLE_SIZE)
            .map { it.id }
            .toList()
        return ScopeSnapshot(
            totalSubjects = totalSubjects,
            matched = matched.size,
            sampleIds = sample,
            broad = broad,
            validExpression = validExpression,
            error = error,
        )
    }
}

object ScopeAnalyzer {
    fun analyze(subjectExpr: String, subjects: List<SubjectEntry>): ScopeResult {
        val expr = try {
            ExprParser.parse(subjectExpr)
        } catch (e: Exception) {
            return ScopeResult(
                totalSubjects = subjects.size,
                matched = emptyList(),
                broad = false,
                validExpression = false,
                error = e.message,
            )
        }
        val hits = subjects.filter { entry ->
            val ctx = LinkedHashMap<String, Any?>()
            ctx.putAll(entry.attrs)
            ctx.putIfAbsent("id", entry.id)
            try {
                ExprEvaluator.evalBoolean(expr, ctx)
            } catch (e: Exception) {
                false
            }
        }
        val ratio = if (subjects.isEmpty()) 0.0 else hits.size.toDouble() / subjects.size
        val broad = hits.size > ScopeSnapshot.BROAD_LIMIT || ratio > 0.5
        return ScopeResult(subjects.size, hits, broad, true, null)
    }
}
