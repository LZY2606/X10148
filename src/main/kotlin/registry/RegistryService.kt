package registry

import java.nio.file.Path
import java.time.Instant
import java.util.UUID

data class DecisionExplanation(
    val decision: DecisionRecord?,
    val policyId: String,
    val policyVersion: Int,
    val at: Instant,
    val hits: List<ClauseHit>,
    val reliefs: List<Relief>,
    val ignoredExceptions: List<IgnoredException>,
    val remaining: List<String>,
    val conclusion: String,
    val persisted: Boolean,
    val fingerprint: String,
)

data class ClauseHit(
    val clauseId: String,
    val title: String,
    val predicate: String,
)

data class Relief(
    val clauseId: String,
    val exceptionId: String,
    val chainId: String,
    val versionNo: Int,
    val subjectExpr: String,
    val reason: String,
)

data class IgnoredException(
    val exceptionId: String,
    val chainId: String,
    val versionNo: Int,
    val status: String,
    val reason: String,
)

class RegistryService(
    val storage: FileStorage,
    val clock: Clock,
    private val seed: Boolean = true,
) {
    val policies = PolicyRepository(storage)
    val exceptions = ExceptionRepository(storage)
    val subjects = SubjectRepository(storage)
    val decisions = DecisionRepository(storage)
    val auditLog = AuditLog(storage)

    init {
        if (seed && policies.all().isEmpty()) SeedData.install(this)
        verifyAuditChain()
    }

    fun verifyAuditChain() {
        val errors = auditLog.verifyChain()
        check(errors.isEmpty()) { "审计链校验失败: $errors" }
    }

    private fun <T> mutate(action: () -> T): T = storage.withLock(action)

    // ---------- 策略 ----------

    @JvmOverloads
    fun createPolicy(
        name: String,
        description: String,
        clauses: List<Clause>,
        actor: String = "admin",
        basedOn: Policy? = null,
        at: Instant = clock.now(),
    ): Policy = mutate {
        requireText(name, "name")
        if (clauses.isEmpty()) throw ApiError(ErrorKind.VALIDATION, "策略至少包含一条条款")
        clauses.forEach { c ->
            requireText(c.id, "clause id")
            try { ExprParser.parse(c.predicate) } catch (e: Exception) {
                throw ApiError(ErrorKind.VALIDATION, "条款 ${c.id} 谓词无效: ${e.message}")
            }
        }
        if (clauses.map { it.id }.toSet().size != clauses.size)
            throw ApiError(ErrorKind.VALIDATION, "条款 id 重复")
        val id = basedOn?.id ?: "pol-" + UUID.randomUUID().toString().take(8)
        val nextVersion = if (basedOn != null) basedOn.version + 1
        else (policies.versions(id).maxOfOrNull { it.version } ?: 0) + 1
        val policy = Policy(id, nextVersion, name, description, clauses, at, basedOn?.version)
        policies.save(policy)
        auditLog.append("POLICY_CREATE", actor, "$id@v$nextVersion", null, true, null, clock)
        policy
    }

    fun editPolicy(id: String, name: String, description: String, clauses: List<Clause>, actor: String): Policy {
        val base = policies.latest(id) ?: throw ApiError(ErrorKind.NOT_FOUND, "策略不存在: $id")
        return createPolicy(name, description, clauses, actor, basedOn = base)
    }

    // ---------- 目录 ----------

    fun upsertSubject(entry: SubjectEntry, actor: String = "admin"): SubjectEntry = mutate {
        requireText(entry.id, "subject id")
        subjects.save(entry)
        auditLog.append("SUBJECT_UPSERT", actor, entry.id, null, true, null, clock)
        entry
    }

    fun deleteSubject(id: String, actor: String): Unit = mutate {
        subjects.delete(id)
        auditLog.append("SUBJECT_DELETE", actor, id, null, true, null, clock)
    }

    // ---------- 例外提交 ----------

    data class SubmitRequest(
        val policyId: String,
        val policyVersion: Int?,
        val subjectExpr: String,
        val relaxedClauseIds: List<String>,
        val startAt: Instant,
        val endAt: Instant,
        val evidenceSummary: String,
        val evidenceBlobId: String?,
        val submitter: String,
        val justification: String = "",
    )

    fun submitException(request: SubmitRequest): ExceptionRecord = mutate {
        val now = clock.now()
        val actor = request.submitter
        val policy = request.policyVersion?.let { policies.find(request.policyId, it) }
            ?: policies.latest(request.policyId)
            ?: throw auditFail("EXCEPTION_SUBMIT", actor, null, null,
                ErrorKind.NOT_FOUND, "策略不存在: ${request.policyId}")
        try {
            validateExceptionRequest(request, policy)
        } catch (e: ApiError) {
            throw auditFail("EXCEPTION_SUBMIT", actor, null, null, e.kind, e.message!!)
        }
        val scope = ScopeAnalyzer.analyze(request.subjectExpr, subjects.all()).snapshot()
        val id = "exc-" + UUID.randomUUID().toString().take(8)
        val record = ExceptionRecord(
            id = id,
            chainId = id,
            versionNo = 1,
            rev = 1,
            policyId = policy.id,
            policyVersion = policy.version,
            subjectExpr = request.subjectExpr,
            relaxedClauseIds = request.relaxedClauseIds.distinct(),
            startAt = request.startAt,
            endAt = request.endAt,
            evidenceSummary = request.evidenceSummary,
            evidenceBlobId = request.evidenceBlobId,
            submitter = request.submitter,
            approvals = emptyList(),
            storedState = StoredState.PENDING,
            revokedBy = null,
            revokedAt = null,
            revokeReason = null,
            renewedFrom = null,
            scope = scope,
            createdAt = now,
        )
        exceptions.save(record)
        auditLog.append("EXCEPTION_SUBMIT", request.submitter, id, id, true,
            "clauses=${request.relaxedClauseIds.size},broad=${scope.broad}", clock)
        record
    }

    private fun validateExceptionRequest(request: SubmitRequest, policy: Policy) {
        requireText(request.subjectExpr, "subjectExpr")
        requireText(request.submitter, "submitter")
        try {
            ExprParser.parse(request.subjectExpr)
        } catch (e: Exception) {
            throw ApiError(ErrorKind.VALIDATION, "对象表达式无效: ${e.message}")
        }
        if (request.relaxedClauseIds.isEmpty())
            throw ApiError(ErrorKind.VALIDATION, "例外必须明确列出至少一条放宽条款")
        val policyClauseIds = policy.clauses.map { it.id }.toSet()
        val unknown = request.relaxedClauseIds - policyClauseIds
        if (unknown.isNotEmpty())
            throw ApiError(ErrorKind.VALIDATION, "条款不属于所选策略版本: $unknown")
        if (request.relaxedClauseIds.distinct().size == policy.clauses.size)
            throw ApiError(ErrorKind.VALIDATION, "一条例外不能放宽策略的全部条款（禁止跳过整个策略）")
        if (!request.endAt.isAfter(request.startAt))
            throw ApiError(ErrorKind.VALIDATION, "生效区间非法：结束必须晚于开始")
        if (!request.evidenceSummary.isNotBlank() && request.evidenceBlobId == null)
            throw ApiError(ErrorKind.VALIDATION, "需要证明材料摘要或材料文件")
        if (request.evidenceBlobId != null && !storage.blobExists(request.evidenceBlobId))
            throw ApiError(ErrorKind.BLOB_MISSING, "证明材料不存在: ${request.evidenceBlobId}")
    }

    // ---------- 审核确认 ----------

    fun confirmException(exceptionId: String, reviewer: String, expectedRev: Long): ExceptionRecord = mutate {
        val record = exceptions.find(exceptionId)
            ?: throw auditFail("APPROVE", reviewer, exceptionId, null,
                ErrorKind.NOT_FOUND, "例外不存在")
        if (record.rev != expectedRev)
            throw auditFail("APPROVE", reviewer, exceptionId, record.chainId,
                ErrorKind.CONFLICT, "版本号不匹配: expected=$expectedRev actual=${record.rev}")
        if (record.storedState == StoredState.PENDING && record.submitter == reviewer)
            throw auditFail("APPROVE", reviewer, exceptionId, record.chainId,
                ErrorKind.SELF_REVIEW, "提交者不能审核自己的例外")
        if (record.storedState != StoredState.PENDING)
            throw auditFail("APPROVE", reviewer, exceptionId, record.chainId,
                ErrorKind.WRONG_STATE, "当前状态 ${record.storedState} 不可审核")
        if (record.approvals.any { it.reviewer == reviewer })
            throw auditFail("APPROVE", reviewer, exceptionId, record.chainId,
                ErrorKind.DUPLICATE_CONFIRMATION, "同一审核者重复确认不增加计数: $reviewer")
        if (!clock.now().isBefore(record.endAt))
            throw auditFail("APPROVE", reviewer, exceptionId, record.chainId,
                ErrorKind.INTERVAL_CLOSED, "区间结束瞬间已失效，不能再激活")
        val updated = record.copy(approvals = record.approvals + Approval(reviewer, clock.now()), rev = record.rev + 1)
        val activated = updated.approvals.size >= 2
        val finalRecord = if (activated) updated.copy(storedState = StoredState.ACTIVE) else updated
        exceptions.save(finalRecord)
        auditLog.append(
            if (activated) "EXCEPTION_ACTIVATE" else "APPROVE",
            reviewer, exceptionId, record.chainId, true,
            if (activated) "approvers=${finalRecord.approvals.joinToString(",") { it.reviewer }}"
            else "approvers=${finalRecord.approvals.size}",
            clock,
        )
        finalRecord
    }

    // ---------- 撤销 ----------

    fun revokeException(exceptionId: String, reviewer: String, reason: String, expectedRev: Long): ExceptionRecord = mutate {
        val record = exceptions.find(exceptionId)
            ?: throw auditFail("REVOKE", reviewer, exceptionId, null,
                ErrorKind.NOT_FOUND, "例外不存在")
        if (record.rev != expectedRev)
            throw auditFail("REVOKE", reviewer, exceptionId, record.chainId,
                ErrorKind.CONFLICT, "版本号不匹配: expected=$expectedRev actual=${record.rev}")
        if (record.storedState == StoredState.REVOKED)
            throw auditFail("REVOKE", reviewer, exceptionId, record.chainId,
                ErrorKind.WRONG_STATE, "例外已撤销")
        val updated = record.copy(
            storedState = StoredState.REVOKED,
            revokedBy = reviewer,
            revokedAt = clock.now(),
            revokeReason = reason.ifBlank { "(未填写)" },
            rev = record.rev + 1,
        )
        exceptions.save(updated)
        auditLog.append("REVOKE", reviewer, exceptionId, record.chainId, true,
            "previousState=${record.storedState}", clock)
        updated
    }

    // ---------- 续期（必须创建新版本） ----------

    data class RenewRequest(
        val exceptionId: String,
        val actor: String,
        val startAt: Instant,
        val endAt: Instant,
        val subjectExpr: String?,
        val relaxedClauseIds: List<String>?,
        val evidenceSummary: String?,
        val evidenceBlobId: String?,
        val expectedRev: Long,
    )

    fun renewException(request: RenewRequest): ExceptionRecord = mutate {
        val source = exceptions.find(request.exceptionId)
            ?: throw auditFail("RENEW", request.actor, request.exceptionId, null,
                ErrorKind.NOT_FOUND, "例外不存在")
        if (source.rev != request.expectedRev)
            throw auditFail("RENEW", request.actor, request.exceptionId, source.chainId,
                ErrorKind.CONFLICT, "版本号不匹配: expected=${request.expectedRev} actual=${source.rev}")
        if (source.storedState == StoredState.PENDING)
            throw auditFail("RENEW", request.actor, request.exceptionId, source.chainId,
                ErrorKind.WRONG_STATE, "未激活的例外不能续期")
        if (source.storedState == StoredState.REVOKED)
            throw auditFail("RENEW", request.actor, request.exceptionId, source.chainId,
                ErrorKind.WRONG_STATE, "已撤销的例外不能续期")
        val chain = exceptions.chain(source.chainId)
        if (source.versionNo != chain.maxOf { it.versionNo })
            throw auditFail("RENEW", request.actor, request.exceptionId, source.chainId,
                ErrorKind.CHAIN_CONFLICT, "只能基于链上最新版本续期")
        val now = clock.now()
        val policy = policies.find(source.policyId, source.policyVersion)
            ?: throw auditFail("RENEW", request.actor, request.exceptionId, source.chainId,
                ErrorKind.NOT_FOUND, "原策略版本缺失")
        val newRequest = SubmitRequest(
            policyId = source.policyId,
            policyVersion = source.policyVersion,
            subjectExpr = request.subjectExpr ?: source.subjectExpr,
            relaxedClauseIds = request.relaxedClauseIds ?: source.relaxedClauseIds,
            startAt = request.startAt,
            endAt = request.endAt,
            evidenceSummary = request.evidenceSummary ?: source.evidenceSummary,
            evidenceBlobId = request.evidenceBlobId ?: source.evidenceBlobId,
            submitter = request.actor,
        )
        try {
            validateExceptionRequest(newRequest, policy)
        } catch (e: ApiError) {
            throw auditFail("RENEW", request.actor, request.exceptionId, source.chainId, e.kind, e.message!!)
        }
        val scope = ScopeAnalyzer.analyze(newRequest.subjectExpr, subjects.all()).snapshot()
        val newRecord = ExceptionRecord(
            id = "exc-" + UUID.randomUUID().toString().take(8),
            chainId = source.chainId,
            versionNo = source.versionNo + 1,
            rev = 1,
            policyId = source.policyId,
            policyVersion = source.policyVersion,
            subjectExpr = newRequest.subjectExpr,
            relaxedClauseIds = newRequest.relaxedClauseIds.distinct(),
            startAt = newRequest.startAt,
            endAt = newRequest.endAt,
            evidenceSummary = newRequest.evidenceSummary,
            evidenceBlobId = newRequest.evidenceBlobId,
            submitter = request.actor,
            approvals = emptyList(),
            storedState = StoredState.PENDING,
            revokedBy = null,
            revokedAt = null,
            revokeReason = null,
            renewedFrom = VersionRef(source.id, source.versionNo),
            scope = scope,
            createdAt = now,
        )
        exceptions.save(newRecord)
        auditLog.append("RENEW", request.actor, newRecord.id, newRecord.chainId, true,
            "from=${source.id}@v${source.versionNo},new=v${newRecord.versionNo}", clock)
        newRecord
    }

    private fun auditFail(
        type: String, actor: String, targetId: String?, chainId: String?,
        kind: ErrorKind, message: String,
    ): ApiError {
        auditLog.append("${type}_REJECTED", actor, targetId, chainId, false, "${kind.name}:$message", clock)
        return ApiError(kind, message)
    }

    private fun requireText(value: String, field: String) {
        if (value.isBlank()) throw ApiError(ErrorKind.VALIDATION, "$field 不能为空")
    }
}
