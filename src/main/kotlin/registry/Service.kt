package registry

import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Policy Exception Registry core service.
 *
 * Concurrency: every public operation takes [lock], performs an expiry sweep, and
 * persists atomically. Mutations carry an expected status revision; stale requests
 * raise [ConflictException]. Every failure still appends an audit event whose detail
 * map contains no evidence material.
 */
class RegistryService(
    private val store: FileStore,
    val clock: ServiceClock,
    private val blobDirectory: java.nio.file.Path,
    private val idFactory: IdFactory = IdFactory()
) {
    private val lock = Any()
    private var state: Snapshot = store.load()

    init {
        val restoredSimulatedAt = state.simulatedAt
        if (!clock.isSimulating() && state.clockSimulating && restoredSimulatedAt != null) {
            clock.freezeAt(restoredSimulatedAt)
        }
    }

    // ---- Snapshot access -------------------------------------------------

    fun snapshot(): Snapshot = withLock { state.copy() }

    fun latestPolicyVersion(policyId: String? = null): PolicyVersion? =
        withLock {
            val versions = state.policies.filter { policyId == null || it.policyId == policyId }
            versions.maxWithOrNull(compareBy({ it.createdAt }, { it.version }))
        }

    fun policyVersion(policyId: String, version: Int): PolicyVersion? =
        withLock { state.policies.firstOrNull { it.policyId == policyId && it.version == version } }

    fun exception(exceptionId: String): ExceptionRecord? =
        withLock { state.exceptions.firstOrNull { it.exceptionId == exceptionId } }

    fun chain(chainId: String): List<ExceptionRecord> =
        withLock { state.exceptions.filter { it.chainId == chainId }.sortedBy { it.chainVersion } }

    /**
     * Effective status at [at]. The end instant is exclusive: at validUntil the
     * exception has already expired. Revoked/superseded are terminal.
     */
    fun effectiveStatus(record: ExceptionRecord, at: Instant): ExceptionStatus = when (record.status) {
        ExceptionStatus.REVOKED, ExceptionStatus.SUPERSEDED -> record.status
        ExceptionStatus.EXPIRED -> ExceptionStatus.EXPIRED
        ExceptionStatus.ACTIVE ->
            if (!at.isBefore(record.validUntil)) ExceptionStatus.EXPIRED else ExceptionStatus.ACTIVE
        ExceptionStatus.PENDING -> ExceptionStatus.PENDING
    }

    // ---- Directory -------------------------------------------------------

    fun upsertSubject(actor: String, subjectId: String, displayName: String, attributes: Map<String, String>): SubjectRecord =
        mutate("subject.upsert", actor, subjectId) {
            requireToken(subjectId, "对象 ID")
            val cleanAttributes = attributes.mapValues { it.value.trim() }.filterKeys { it.isNotBlank() }
            val record = SubjectRecord(subjectId.trim(), displayName.trim().ifBlank { subjectId.trim() }, cleanAttributes)
            val replaced = state.subjects.any { it.subjectId == record.subjectId }
            state = state.copy(subjects = state.subjects.filterNot { it.subjectId == record.subjectId } + record)
            (record to mapOf("replaced" to replaced.toString()))
        }

    fun deleteSubject(actor: String, subjectId: String) = mutate("subject.delete", actor, subjectId) {
        if (state.subjects.none { it.subjectId == subjectId }) throw NotFoundException("对象不存在: $subjectId")
        state = state.copy(subjects = state.subjects.filterNot { it.subjectId == subjectId })
        Unit to emptyMap()
    }

    // ---- Policies --------------------------------------------------------

    fun editPolicy(
        actor: String,
        policyId: String?,
        title: String,
        clauses: List<ClauseDraft>,
        changeNote: String
    ): PolicyVersion = mutate("policy.edit", actor, policyId ?: "(new)") {
        requireToken(actor, "编辑者")
        if (title.isBlank()) throw ValidationException("策略标题不能为空")
        if (clauses.isEmpty()) throw ValidationException("策略至少需要一个条款")
        val seenIds = HashSet<String>()
        val cleanClauses = clauses.map { draft ->
            val id = draft.id.trim()
            if (!id.matches(Regex("[A-Za-z0-9_.-]+"))) throw ValidationException("条款 ID 非法: '$id'，仅允许字母数字 _ . -")
            if (!seenIds.add(id)) throw ValidationException("条款 ID 重复: $id")
            if (draft.title.isBlank()) throw ValidationException("条款 $id 缺少标题")
            Expressions.validate(draft.predicateExpr.trim())
            Clause(id, draft.title.trim(), draft.severity.trim().ifBlank { "normal" }, draft.predicateExpr.trim(), draft.rationale.trim())
        }
        val id = policyId?.takeIf { it.isNotBlank() } ?: idFactory.newId("pol")
        val previous = state.policies.filter { it.policyId == id }.maxByOrNull { it.version }
        if (previous != null && previous.clauses.map { it.id }.toSet() != cleanClauses.map { it.id }.toSet()) {
            // clause set change is allowed, just noted
        }
        val version = PolicyVersion(
            policyId = id,
            version = (previous?.version ?: 0) + 1,
            title = title.trim(),
            clauses = cleanClauses,
            createdAt = clock.now(),
            editedBy = actor,
            changeNote = changeNote.trim().ifBlank { if (previous == null) "初始版本" else "策略编辑" },
            supersedesVersion = previous?.version
        )
        state = state.copy(policies = state.policies + version)
        version to mapOf("policyId" to id, "version" to version.version.toString())
    }

    // ---- Scope preview ---------------------------------------------------

    fun previewScope(expr: String): ScopePreview {
        Expressions.validate(expr)
        return withLock { scopeOf(expr) }
    }

    private fun scopeOf(expr: String): ScopePreview {
        val matched = state.subjects
            .filter { Expressions.evaluate(expr, subjectAttributes(it)) }
            .sortedBy { it.subjectId }
        val samples = matched.take(SAMPLE_LIMIT).map { it.subjectId }
        return ScopePreview(matched.size, samples, matched.size > SAMPLE_LIMIT)
    }

    // ---- Exceptions ------------------------------------------------------

    @Suppress("LongParameterList")
    fun submitException(
        actor: String,
        policyId: String,
        policyVersion: Int?,
        subjectExpr: String,
        clauseIds: List<String>,
        validFrom: Instant,
        validUntil: Instant,
        justification: String,
        evidenceSummary: String,
        blobId: String?
    ): ExceptionRecord = mutate("exception.submit", actor, "(new)") {
        requireToken(actor, "提交者")
        if (validUntil.isBefore(validFrom) || validUntil == validFrom) {
            throw ValidationException("生效区间结束必须严格晚于开始（结束瞬间即失效）")
        }
        val expr = subjectExpr.trim()
        if (expr.isBlank()) throw ValidationException("适用对象表达式不能为空")
        Expressions.validate(expr)
        val policy = resolvePolicyVersion(policyId, policyVersion)
        val distinctClauses = clauseIds.map { it.trim() }.distinct()
        if (distinctClauses.isEmpty()) throw ValidationException("必须明确列出要放宽的条款，不能跳过整个策略")
        val policyClauseIds = policy.clauses.map { it.id }.toSet()
        val unknown = distinctClauses.filterNot { it in policyClauseIds }
        if (unknown.isNotEmpty()) throw ValidationException("条款在所选策略版本中不存在: ${unknown.joinToString()}")
        if (evidenceSummary.isBlank()) throw ValidationException("证明材料摘要不能为空")
        if (blobId != null) {
            val blob = state.blobs.firstOrNull { it.blobId == blobId }
                ?: throw ValidationException("证明材料 blob 不存在")
            if (blob.deleted) throw ValidationException("证明材料 blob 已被删除，不能再关联")
        }
        val scope = scopeOf(expr)
        val chainId = idFactory.newId("chain")
        val exceptionId = idFactory.newId("exc")
        val record = ExceptionRecord(
            exceptionId = exceptionId,
            chainId = chainId,
            chainVersion = 1,
            revision = nextCounter("exceptionRevision"),
            statusRevision = 1,
            renewedFrom = null,
            policyId = policy.policyId,
            policyVersion = policy.version,
            subjectExpr = expr,
            clauseIds = distinctClauses,
            validFrom = validFrom,
            validUntil = validUntil,
            justification = justification.trim(),
            evidenceSummary = evidenceSummary.trim(),
            blobId = blobId,
            submittedBy = actor.trim(),
            submittedAt = clock.now(),
            approvals = emptyList(),
            status = ExceptionStatus.PENDING,
            activatedAt = null,
            revokedAt = null,
            revokedBy = null,
            revokeReason = null,
            renewedAt = null,
            renewedByChain = null,
            scopeMatchedCount = scope.matchedCount,
            scopeSamples = scope.samples,
            scopeWarning = scope.warning
        )
        state = state.copy(exceptions = state.exceptions + record)
        record to mapOf(
            "exceptionId" to exceptionId,
            "policyId" to policy.policyId,
            "policyVersion" to policy.version.toString(),
            "scopeMatched" to scope.matchedCount.toString(),
            "scopeBroad" to scope.warning.toString()
        )
    }

    fun approve(actor: String, exceptionId: String, expectedStatusRevision: Int): ExceptionRecord =
        mutate("exception.approve", actor, exceptionId) {
            requireToken(actor, "审核者")
            val record = getException(exceptionId)
            checkRevision(record, expectedStatusRevision)
            if (record.submittedBy == actor) {
                throw ValidationException("提交者不能审核自己提交的例外")
            }
            val currentStatus = effectiveStatus(record, clock.now())
            if (record.status != ExceptionStatus.PENDING || currentStatus != ExceptionStatus.PENDING) {
                throw ValidationException("仅待审核例外可以确认，当前状态: $currentStatus")
            }
            if (record.isApprovedBy(actor)) {
                throw ValidationException("同一审核者重复确认不增加计数")
            }
            val approvals = record.approvals + Approval(actor.trim(), clock.now())
            val activating = approvals.size >= REQUIRED_APPROVALS
            val updated = record.copy(
                approvals = approvals,
                status = if (activating) ExceptionStatus.ACTIVE else record.status,
                activatedAt = if (activating) clock.now() else null,
                statusRevision = record.statusRevision + 1
            )
            replaceException(updated)
            updated to mapOf(
                "approvalCount" to approvals.size.toString(),
                "activated" to activating.toString(),
                "reviewer" to actor.trim()
            )
        }

    fun revoke(actor: String, exceptionId: String, expectedStatusRevision: Int, reason: String): ExceptionRecord =
        mutate("exception.revoke", actor, exceptionId) {
            requireToken(actor, "撤销者")
            if (reason.isBlank()) throw ValidationException("撤销必须填写原因")
            val record = getException(exceptionId)
            checkRevision(record, expectedStatusRevision)
            val status = effectiveStatus(record, clock.now())
            if (status == ExceptionStatus.REVOKED || status == ExceptionStatus.SUPERSEDED || status == ExceptionStatus.EXPIRED) {
                throw ValidationException("当前状态 $status 不可撤销")
            }
            val now = clock.now()
            val updated = record.copy(
                status = ExceptionStatus.REVOKED,
                revokedAt = now,
                revokedBy = actor.trim(),
                revokeReason = reason.trim(),
                statusRevision = record.statusRevision + 1
            )
            replaceException(updated)
            updated to mapOf("previousStatus" to status.name)
        }

    @Suppress("LongParameterList")
    fun renew(
        actor: String,
        exceptionId: String,
        expectedStatusRevision: Int,
        validFrom: Instant,
        validUntil: Instant,
        justification: String,
        evidenceSummary: String,
        blobId: String?
    ): Pair<ExceptionRecord, ExceptionRecord> = mutate("exception.renew", actor, exceptionId) {
        requireToken(actor, "续期者")
        if (validUntil.isBefore(validFrom) || validUntil == validFrom) {
            throw ValidationException("续期区间结束必须严格晚于开始")
        }
        if (evidenceSummary.isBlank()) throw ValidationException("续期必须重新填写证明材料摘要")
        if (blobId != null) {
            val blob = state.blobs.firstOrNull { it.blobId == blobId }
                ?: throw ValidationException("证明材料 blob 不存在")
            if (blob.deleted) throw ValidationException("证明材料 blob 已被删除，不能再关联")
        }
        val old = getException(exceptionId)
        checkRevision(old, expectedStatusRevision)
        val status = effectiveStatus(old, clock.now())
        if (status != ExceptionStatus.ACTIVE) {
            throw ValidationException("只有 active 例外可以续期，当前状态: $status")
        }
        val policy = resolvePolicyVersion(old.policyId, null)
        val unknown = old.clauseIds.filterNot { id -> policy.clauses.any { it.id == id } }
        if (unknown.isNotEmpty()) {
            throw ValidationException("最新策略版本缺少原放宽条款，无法续期: ${unknown.joinToString()}；请重新提交例外")
        }
        val scope = scopeOf(old.subjectExpr)
        val now = clock.now()
        val superseded = old.copy(
            status = ExceptionStatus.SUPERSEDED,
            renewedAt = now,
            renewedByChain = old.chainId,
            statusRevision = old.statusRevision + 1
        )
        val newId = idFactory.newId("exc")
        val renewed = ExceptionRecord(
            exceptionId = newId,
            chainId = old.chainId,
            chainVersion = old.chainVersion + 1,
            revision = nextCounter("exceptionRevision"),
            statusRevision = 1,
            renewedFrom = old.exceptionId,
            policyId = policy.policyId,
            policyVersion = policy.version,
            subjectExpr = old.subjectExpr,
            clauseIds = old.clauseIds,
            validFrom = validFrom,
            validUntil = validUntil,
            justification = justification.trim().ifBlank { old.justification },
            evidenceSummary = evidenceSummary.trim(),
            blobId = blobId,
            submittedBy = actor.trim(),
            submittedAt = now,
            approvals = emptyList(),
            status = ExceptionStatus.PENDING,
            activatedAt = null,
            revokedAt = null,
            revokedBy = null,
            revokeReason = null,
            renewedAt = null,
            renewedByChain = null,
            scopeMatchedCount = scope.matchedCount,
            scopeSamples = scope.samples,
            scopeWarning = scope.warning
        )
        replaceException(superseded)
        state = state.copy(exceptions = state.exceptions + renewed)
        (superseded to renewed) to mapOf(
            "oldExceptionId" to old.exceptionId,
            "newExceptionId" to newId,
            "chainVersion" to renewed.chainVersion.toString(),
            "newPolicyVersion" to policy.version.toString()
        )
    }

    // ---- Decisions -------------------------------------------------------

    fun decide(
        actor: String,
        policyId: String,
        policyVersion: Int?,
        subjectId: String,
        extraFacts: Map<String, String> = emptyMap(),
        at: Instant? = null,
        persist: Boolean = true
    ): DecisionResult {
        requireToken(actor, "评估者")
        requireToken(subjectId, "对象 ID")
        return withLock {
            val evaluationTime = at ?: clock.now()
            val policy = resolvePolicyVersion(policyId, policyVersion)
            val directory = state.subjects.firstOrNull { it.subjectId == subjectId }
            val facts = buildMap {
                if (directory != null) putAll(directory.attributes)
                put("subject.id", subjectId)
                putAll(extraFacts)
            }

            // Status is interpreted at the evaluation instant: an exception that later
            // expired or was revoked still explains decisions made while it was active.
            val applicableExceptions = state.exceptions.filter { exception ->
                if (exception.policyId != policy.policyId || exception.policyVersion != policy.version) return@filter false
                if (!(!evaluationTime.isBefore(exception.validFrom) && evaluationTime.isBefore(exception.validUntil))) return@filter false
                if (!Expressions.evaluate(exception.subjectExpr, facts)) return@filter false
                when (exception.status) {
                    // Still active, or expired at the end instant: applies at any past
                    // instant inside its interval, so historical decisions stay explainable.
                    ExceptionStatus.ACTIVE, ExceptionStatus.EXPIRED -> true
                    // Revocation is effective from revokedAt; earlier decisions stand.
                    ExceptionStatus.REVOKED ->
                        exception.revokedAt == null || evaluationTime.isBefore(exception.revokedAt)
                    // Superseded by a renewal and never-activated records never apply.
                    ExceptionStatus.SUPERSEDED, ExceptionStatus.PENDING -> false
                }
            }.sortedWith(compareBy({ it.submittedAt }, { it.exceptionId }))

            val dispositions = policy.clauses.map { clause ->
                val triggered = try {
                    Expressions.evaluate(clause.predicateExpr, facts)
                } catch (error: ExprException) {
                    false
                }
                val waiver = if (triggered) {
                    applicableExceptions.firstOrNull { clause.id in it.clauseIds }
                } else null
                when {
                    !triggered -> ClauseDisposition(
                        clause = clause,
                        triggered = false,
                        enforced = false,
                        exceptionId = null,
                        exceptionRevision = null,
                        reason = "事实未命中该条款"
                    )
                    waiver != null -> ClauseDisposition(
                        clause = clause,
                        triggered = true,
                        enforced = false,
                        exceptionId = waiver.exceptionId,
                        exceptionRevision = waiver.chainVersion,
                        reason = "正常命中，但被例外 ${waiver.exceptionId}（链版本 ${waiver.chainVersion}）按条款级放宽放行"
                    )
                    else -> ClauseDisposition(
                        clause = clause,
                        triggered = true,
                        enforced = true,
                        exceptionId = null,
                        exceptionRevision = null,
                        reason = "正常命中且无适用例外，按策略执行"
                    )
                }
            }

            val triggeredIds = dispositions.filter { it.triggered }.map { it.clause.id }
            val waivedIds = dispositions.filter { it.triggered && !it.enforced }.map { it.clause.id }
            val waiverIds = dispositions.mapNotNull { it.exceptionId }.distinct()
            val verdict = if (dispositions.none { it.triggered && it.enforced }) {
                DecisionVerdict.ALLOW
            } else DecisionVerdict.DENY
            val fingerprint = decisionFingerprint(
                policy, subjectId, facts, evaluationTime, triggeredIds, waivedIds, waiverIds
            )
            val record = DecisionRecord(
                decisionId = idFactory.newId("dec"),
                at = clock.now(),
                policyId = policy.policyId,
                policyVersion = policy.version,
                subjectId = subjectId,
                verdict = verdict,
                triggeredClauseIds = triggeredIds,
                waivedClauseIds = waivedIds,
                waiverExceptionIds = waiverIds,
                fingerprint = fingerprint
            )
            if (persist) {
                val event = audit(
                    actor = actor,
                    action = "decision.evaluate",
                    target = "$subjectId@${policy.policyId}/v${policy.version}",
                    outcome = verdict.name.lowercase(),
                    detail = mapOf(
                        "verdict" to verdict.name,
                        "triggered" to triggeredIds.joinToString(","),
                        "waived" to waivedIds.joinToString(","),
                        "fingerprint" to fingerprint
                    )
                )
                state = state.copy(decisions = state.decisions + record, audit = state.audit + event)
                persist()
            }
            DecisionResult(verdict, evaluationTime, policy, subjectId, dispositions, record)
        }
    }

    /**
     * Stable decision fingerprint. Canonical lines mean that exporting and rebuilding
     * the instance reproduces the exact same fingerprint for the same inputs.
     */
    private fun decisionFingerprint(
        policy: PolicyVersion,
        subjectId: String,
        facts: Map<String, String>,
        at: Instant,
        triggeredIds: List<String>,
        waivedIds: List<String>,
        waiverIds: List<String>
    ): String {
        val canonical = buildString {
            appendLine("policy=${policy.policyId}")
            appendLine("policyVersion=${policy.version}")
            appendLine("subject=$subjectId")
            appendLine("at=$at")
            facts.toSortedMap().forEach { (key, value) -> appendLine("fact:$key=$value") }
            appendLine("triggered=${triggeredIds.joinToString(",")}")
            appendLine("waived=${waivedIds.joinToString(",")}")
            appendLine("waivers=${waiverIds.joinToString(",")}")
        }
        return sha256(canonical)
    }

    // ---- Evidence blobs --------------------------------------------------

    fun uploadBlob(actor: String, fileName: String, mediaType: String, content: ByteArray): BlobRecord =
        mutate("evidence.upload", actor, "(new)") {
            if (content.isEmpty()) throw ValidationException("证明材料内容不能为空")
            if (fileName.isBlank()) throw ValidationException("文件名不能为空")
            val blobId = idFactory.newId("blob")
            val blob = BlobRecord(
                blobId = blobId,
                fileName = fileName.trim(),
                mediaType = mediaType.trim().ifBlank { "application/octet-stream" },
                sizeBytes = content.size.toLong(),
                sha256 = sha256Bytes(content),
                uploadedAt = clock.now()
            )
            BlobStore.write(blobDirectory, blobId, content)
            state = state.copy(blobs = state.blobs + blob)
            blob to mapOf("sizeBytes" to content.size.toString(), "sha256" to blob.sha256)
        }

    fun readBlob(blobId: String): Pair<BlobRecord, ByteArray>? = withLock {
        val blob = state.blobs.firstOrNull { it.blobId == blobId } ?: return@withLock null
        if (blob.deleted) return@withLock blob to ByteArray(0)
        blob to BlobStore.read(blobDirectory, blobId)
    }

    fun deleteBlob(actor: String, blobId: String): BlobRecord = mutate("evidence.delete", actor, blobId) {
        val blob = state.blobs.firstOrNull { it.blobId == blobId }
            ?: throw NotFoundException("blob 不存在: $blobId")
        if (blob.deleted) throw ValidationException("blob 已经删除；历史记录仍保留摘要")
        BlobStore.delete(blobDirectory, blobId)
        val updated = blob.copy(deleted = true, deletedAt = clock.now())
        state = state.copy(blobs = state.blobs.map { if (it.blobId == blobId) updated else it })
        updated to mapOf("keptSummary" to "true")
    }

    // ---- Clock control ---------------------------------------------------

    fun freezeClock(actor: String, at: Instant): Instant = mutate("clock.freeze", actor, "clock") {
        clock.freezeAt(at)
        persistClock()
        at to mapOf("at" to at.toString())
    }

    fun advanceClock(actor: String, seconds: Long): Instant = mutate("clock.advance", actor, "clock") {
        if (seconds <= 0) throw ValidationException("时钟只能向未来推进，秒数必须为正")
        val at = clock.advance(seconds)
        persistClock()
        at to mapOf("at" to at.toString(), "advancedSeconds" to seconds.toString())
    }

    fun resetClock(actor: String): Instant = mutate("clock.reset", actor, "clock") {
        val at = clock.reset()
        state = state.copy(clockSimulating = false, clockBase = at, clockOffsetSeconds = 0L, simulatedAt = null)
        at to mapOf("at" to at.toString())
    }

    // ---- Export / import -------------------------------------------------

    fun exportBundle(): ByteArray {
        return withLock {
            val liveBlobs = state.blobs.filterNot { it.deleted }
                .filter { BlobStore.exists(blobDirectory, it.blobId) }
            val bundle = Bundle(
                snapshot = state,
                blobs = liveBlobs.associate { it.blobId to BlobStore.read(blobDirectory, it.blobId) }
            )
            BundleZip.write(bundle)
        }
    }

    fun importBundle(actor: String, content: ByteArray): Snapshot {
        // Import is a whole-instance replacement: it must not append its own event to
        // the imported audit chain, otherwise the rebuilt instance would diverge.
        if (content.isEmpty()) throw ValidationException("导入包为空")
        return synchronized(lock) {
            val bundle = try {
                BundleZip.read(content)
            } catch (error: Exception) {
                throw ValidationException("导出包无法解析: ${error.message}")
            }
            val restored = bundle.snapshot
            validateRestored(restored)
            bundle.blobs.forEach { (blobId, bytes) -> BlobStore.write(blobDirectory, blobId, bytes) }
            state = restored
            if (restored.clockSimulating && restored.simulatedAt != null) {
                clock.freezeAt(restored.simulatedAt)
            } else {
                clock.reset()
            }
            persist()
            restored
        }
    }

    private fun validateRestored(snapshot: Snapshot) {
        val sequences = snapshot.audit.map { it.seq }
        if (sequences != sequences.sorted() || sequences.toSet().size != sequences.size) {
            throw ValidationException("导入包审计链顺序或唯一性损坏")
        }
        snapshot.exceptions.forEach { record ->
            if (record.clauseIds.toSet().size != record.clauseIds.size) {
                throw ValidationException("导入包例外 ${record.exceptionId} 条款列表异常")
            }
        }
    }

    // ---- Internals -------------------------------------------------------

    private fun <T> mutate(
        action: String,
        actor: String,
        target: String,
        block: () -> Pair<T, Map<String, String>>
    ): T = synchronized(lock) {
        try {
            val (result, detail) = block()
            val event = audit(actor, action, target, "success", detail)
            state = state.copy(audit = state.audit + event)
            persist()
            // Sweep with the post-mutation clock so "advance/freeze past validUntil"
            // expires exceptions within the same request that moved time.
            sweepExpired()
            result
        } catch (error: RegistryException) {
            // Discard the in-memory partial mutation: the last good document on disk is
            // the transactional baseline. The rejected request still leaves an audit
            // event, carrying only the non-sensitive rejection reason.
            val reloaded = store.load()
            state = reloaded.copy(auditSeq = reloaded.auditSeq + 1)
            val event = AuditEvent(
                seq = state.auditSeq,
                at = clock.now(),
                actor = actor,
                action = action,
                target = target,
                outcome = "failure:${error.javaClass.simpleName.removeSuffix("Exception")}",
                detail = mapOf("reason" to (error.message ?: "拒绝")),
                sensitive = false
            )
            state = state.copy(audit = reloaded.audit + event)
            persist()
            throw error
        }
    }

    private fun <T> withLock(block: () -> T): T = synchronized(lock) {
        sweepExpired()
        block()
    }

    private fun persistClock() {
        val (simulating, simulated, realNow) = clock.snapshot()
        state = if (simulating) {
            state.copy(
                clockSimulating = true,
                clockBase = realNow,
                clockOffsetSeconds = ChronoUnit.SECONDS.between(realNow, simulated),
                simulatedAt = simulated
            )
        } else {
            state.copy(clockSimulating = false, clockOffsetSeconds = 0L, simulatedAt = null)
        }
    }

    private fun sweepExpired() {
        val now = clock.now()
        val expiring = state.exceptions
            .filter { it.status == ExceptionStatus.ACTIVE && !now.isBefore(it.validUntil) }
            .sortedWith(compareBy({ it.validUntil }, { it.exceptionId }))
        if (expiring.isEmpty()) return
        var audit = state.audit
        var seq = state.auditSeq
        state = state.copy(exceptions = state.exceptions.map { record ->
            val match = expiring.firstOrNull { it.exceptionId == record.exceptionId }
            if (match != null) {
                seq += 1
                audit = audit + AuditEvent(
                    seq = seq,
                    at = now,
                    actor = "system",
                    action = "exception.expire",
                    target = record.exceptionId,
                    outcome = "success",
                    detail = mapOf("validUntil" to record.validUntil.toString(), "at" to now.toString())
                )
                record.copy(status = ExceptionStatus.EXPIRED, statusRevision = record.statusRevision + 1)
            } else record
        }, audit = audit, auditSeq = seq)
        persist()
    }

    private fun audit(
        actor: String,
        action: String,
        target: String,
        outcome: String,
        detail: Map<String, String>
    ): AuditEvent {
        val seq = state.auditSeq + 1
        state = state.copy(auditSeq = seq)
        return AuditEvent(
            seq = seq,
            at = clock.now(),
            actor = actor,
            action = action,
            target = target,
            outcome = outcome,
            detail = detail,
            sensitive = false
        )
    }

    private fun resolvePolicyVersion(policyId: String, version: Int?): PolicyVersion {
        val candidates = state.policies.filter { it.policyId == policyId }
        if (candidates.isEmpty()) throw NotFoundException("策略不存在: $policyId")
        return if (version == null) {
            candidates.maxBy { it.version }
        } else {
            candidates.firstOrNull { it.version == version }
                ?: throw NotFoundException("策略版本不存在: $policyId v$version")
        }
    }

    private fun getException(exceptionId: String): ExceptionRecord =
        state.exceptions.firstOrNull { it.exceptionId == exceptionId }
            ?: throw NotFoundException("例外不存在: $exceptionId")

    private fun checkRevision(record: ExceptionRecord, expected: Int) {
        if (record.statusRevision != expected) {
            throw ConflictException(
                "版本冲突：例外 ${record.exceptionId} 当前状态版本为 ${record.statusRevision}，请求基于 $expected",
                expectedRevision = expected,
                actualRevision = record.statusRevision
            )
        }
    }

    private fun replaceException(updated: ExceptionRecord) {
        state = state.copy(exceptions = state.exceptions.map {
            if (it.exceptionId == updated.exceptionId) updated else it
        })
    }

    private fun subjectAttributes(record: SubjectRecord): Map<String, String> =
        record.attributes + ("subject.id" to record.subjectId)

    private fun nextCounter(name: String): Int {
        val next = (state.counters[name] ?: 0) + 1
        state = state.copy(counters = state.counters + (name to next))
        return next
    }

    private fun persist() = store.save(state)

    private fun requireToken(value: String, label: String) {
        if (value.isBlank()) throw ValidationException("$label 不能为空")
        if (value.length > 200) throw ValidationException("$label 过长")
    }

    private fun sha256(text: String): String = sha256Bytes(text.toByteArray(Charsets.UTF_8))

    private fun sha256Bytes(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val REQUIRED_APPROVALS = 2
        const val SAMPLE_LIMIT = 5
    }
}

data class ClauseDraft(
    val id: String,
    val title: String,
    val severity: String,
    val predicateExpr: String,
    val rationale: String
)

data class ScopePreview(val matchedCount: Int, val samples: List<String>, val warning: Boolean)

class IdFactory {
    fun newId(prefix: String): String =
        "$prefix-${java.lang.Long.toHexString(System.currentTimeMillis())}-${
            java.lang.Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextInt())
        }"
}
