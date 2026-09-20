package registry

import java.time.Instant

object DecisionEngine {
    fun evaluate(
        service: RegistryService,
        fact: JsonObject,
        at: Instant,
        policyVersionOverride: Int? = null,
        persist: Boolean = true,
    ): DecisionExplanation = service.storage.withLock {
        val policyId = fact["policyId"] as? String
            ?: throw ApiError(ErrorKind.VALIDATION, "事实缺少 policyId")
        val requestedVersion = policyVersionOverride ?: (fact["policyVersion"] as? Number)?.toInt()
        val policy = (requestedVersion?.let { service.policies.find(policyId, it) }
            ?: service.policies.latest(policyId))
            ?: throw ApiError(ErrorKind.NOT_FOUND, "策略不存在: $policyId")

        val payload: JsonObject = if (fact.containsKey("payload")) Json.obj(fact["payload"]) else fact

        val hits = ArrayList<ClauseHit>()
        for (clause in policy.clauses) {
            val violated = try {
                ExprEvaluator.evalBoolean(ExprParser.parse(clause.predicate), payload)
            } catch (e: Exception) {
                throw ApiError(ErrorKind.VALIDATION, "条款 ${clause.id} 评估失败: ${e.message}")
            }
            if (violated) hits.add(ClauseHit(clause.id, clause.title, clause.predicate))
        }
        val hitIds = hits.map { it.clauseId }.toSet()

        val reliefs = ArrayList<Relief>()
        val ignored = ArrayList<IgnoredException>()
        val relievedClauseIds = HashSet<String>()
        val reliefUses = ArrayList<ExceptionUse>()

        val candidates = service.exceptions.all()
            .filter { it.policyId == policy.id && it.policyVersion == policy.version }
            .sortedWith(compareBy({ it.chainId }, { it.versionNo }, { it.createdAt }))

        for (record in candidates) {
            val hitsClause = record.relaxedClauseIds.any { it in hitIds }
            if (!hitsClause) continue
            val status = record.statusAt(at).name.lowercase()
            val eligible = record.eligibleAt(at)
            val subjectMatch = try {
                ExprEvaluator.evalBoolean(ExprParser.parse(record.subjectExpr), payload)
            } catch (e: Exception) {
                false
            }
            if (!eligible) {
                ignored.add(
                    IgnoredException(
                        record.id, record.chainId, record.versionNo, status,
                        if (status == "expired") "例外在决策时刻已过期（区间结束瞬间即失效）"
                        else if (status == "pending" || status == "scheduled") "例外尚未生效（待两名审核者确认）"
                        else "例外状态为 $status，不产生放行"
                    )
                )
                continue
            }
            if (!subjectMatch) {
                ignored.add(IgnoredException(record.id, record.chainId, record.versionNo, status, "对象表达式未命中当前事实"))
                continue
            }
            for (clauseId in record.relaxedClauseIds) {
                if (clauseId in hitIds && relievedClauseIds.add(clauseId)) {
                    reliefs.add(
                        Relief(
                            clauseId = clauseId,
                            exceptionId = record.id,
                            chainId = record.chainId,
                            versionNo = record.versionNo,
                            subjectExpr = record.subjectExpr,
                            reason = record.evidenceSummary,
                        )
                    )
                    reliefUses.add(ExceptionUse(record.id, record.chainId, record.versionNo, clauseId))
                }
            }
        }

        val remaining = hitIds.filter { it !in relievedClauseIds }.sorted()
        val conclusion = if (hits.isEmpty()) "CLEAN" else if (remaining.isEmpty()) "ALLOWED_BY_EXCEPTION" else "DENIED"
        val hitIdList = hits.map { it.clauseId }.sorted()
        val fingerprint = DecisionRecord.computeFingerprint(
            at, policy.id, policy.version, payload, hitIdList, reliefUses, remaining, conclusion
        )

        var stored: DecisionRecord? = null
        if (persist) {
            val meta = service.storage.readMeta()
            val id = (meta["nextDecision"] as Number).toLong()
            stored = DecisionRecord(
                id = id,
                at = at,
                policyId = policy.id,
                policyVersion = policy.version,
                fact = payload,
                hits = hitIdList,
                reliefs = reliefUses,
                remaining = remaining,
                conclusion = conclusion,
                fingerprint = fingerprint,
            )
            service.decisions.save(stored)
            meta["nextDecision"] = id + 1
            service.storage.writeMeta(meta)
            service.auditLog.append("DECISION", fact["actor"] as? String ?: "system",
                "decision-$id", null, true, "conclusion=$conclusion", service.clock)
        }

        DecisionExplanation(
            decision = stored,
            policyId = policy.id,
            policyVersion = policy.version,
            at = at,
            hits = hits,
            reliefs = reliefs.sortedBy { it.clauseId },
            ignoredExceptions = ignored.sortedBy { it.exceptionId },
            remaining = remaining,
            conclusion = conclusion,
            persisted = persist,
            fingerprint = fingerprint,
        )
    }
}
