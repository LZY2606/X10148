package registry

import java.time.Instant

enum class ExceptionStatus { PENDING, ACTIVE, EXPIRED, REVOKED, SUPERSEDED }

enum class DecisionVerdict { ALLOW, DENY }

/** A policy clause whose predicate is written in the small expression language. */
data class Clause(
    val id: String,
    val title: String,
    val severity: String,
    val predicateExpr: String,
    val rationale: String
)

/** Immutable policy revision. Editing a policy appends a new revision. */
data class PolicyVersion(
    val policyId: String,
    val version: Int,
    val title: String,
    val clauses: List<Clause>,
    val createdAt: Instant,
    val editedBy: String,
    val changeNote: String,
    val supersedesVersion: Int?
)

/** A directory entry used both for subject attributes and for stable scope samples. */
data class SubjectRecord(
    val subjectId: String,
    val displayName: String,
    val attributes: Map<String, String>
)

data class BlobRecord(
    val blobId: String,
    val fileName: String,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String,
    val uploadedAt: Instant,
    val deleted: Boolean = false,
    val deletedAt: Instant? = null
)

/**
 * One revision of an exception. Renewal creates a new record with renewedFrom set;
 * it never extends an existing record in place.
 */
data class ExceptionRecord(
    val exceptionId: String,
    val chainId: String,
    val chainVersion: Int,
    val revision: Int,
    val statusRevision: Int,
    val renewedFrom: String?,
    val policyId: String,
    val policyVersion: Int,
    val subjectExpr: String,
    val clauseIds: List<String>,
    val validFrom: Instant,
    val validUntil: Instant,
    val justification: String,
    val evidenceSummary: String,
    val blobId: String?,
    val submittedBy: String,
    val submittedAt: Instant,
    val approvals: List<Approval>,
    val status: ExceptionStatus,
    val activatedAt: Instant?,
    val revokedAt: Instant?,
    val revokedBy: String?,
    val revokeReason: String?,
    val renewedAt: Instant?,
    val renewedByChain: String?,
    val scopeMatchedCount: Int,
    val scopeSamples: List<String>,
    val scopeWarning: Boolean
) {
    fun isApprovedBy(reviewer: String): Boolean = approvals.any { it.reviewer == reviewer }
}

data class Approval(val reviewer: String, val at: Instant)

data class AuditEvent(
    val seq: Long,
    val at: Instant,
    val actor: String,
    val action: String,
    val target: String,
    val outcome: String,
    val detail: Map<String, String>,
    /** Never contains evidence material; failures carry only the rejection reason. */
    val sensitive: Boolean = false
)

data class ClauseDisposition(
    val clause: Clause,
    val triggered: Boolean,
    val enforced: Boolean,
    val exceptionId: String?,
    val exceptionRevision: Int?,
    val reason: String
)

data class DecisionRecord(
    val decisionId: String,
    val at: Instant,
    val policyId: String,
    val policyVersion: Int,
    val subjectId: String,
    val verdict: DecisionVerdict,
    val triggeredClauseIds: List<String>,
    val waivedClauseIds: List<String>,
    val waiverExceptionIds: List<String>,
    val fingerprint: String
)

data class DecisionResult(
    val verdict: DecisionVerdict,
    val at: Instant,
    val policy: PolicyVersion,
    val subjectId: String,
    val dispositions: List<ClauseDisposition>,
    val record: DecisionRecord
) {
    val triggered: List<ClauseDisposition> get() = dispositions.filter { it.triggered }
    val waived: List<ClauseDisposition> get() = dispositions.filter { it.triggered && it.enforced.not() }
}

data class Snapshot(
    val policies: List<PolicyVersion>,
    val subjects: List<SubjectRecord>,
    val exceptions: List<ExceptionRecord>,
    val blobs: List<BlobRecord>,
    val audit: List<AuditEvent>,
    val decisions: List<DecisionRecord>,
    val clockBase: Instant,
    val clockOffsetSeconds: Long,
    val simulatedAt: Instant? = null,
    val clockSimulating: Boolean,
    val auditSeq: Long,
    val counters: Map<String, Int>
)
