package registry

import java.time.Instant

data class Clause(
    val id: String,
    val title: String,
    val description: String,
    val predicate: String,
) {
    fun toJson(): JsonObject = mapOf(
        "id" to id,
        "title" to title,
        "description" to description,
        "predicate" to predicate,
    )

    companion object {
        fun fromJson(j: JsonObject) = Clause(
            id = j["id"] as String,
            title = j["title"] as String,
            description = (j["description"] as? String) ?: "",
            predicate = j["predicate"] as String,
        )
    }
}

data class Policy(
    val id: String,
    val version: Int,
    val name: String,
    val description: String,
    val clauses: List<Clause>,
    val createdAt: Instant,
    val basedOnVersion: Int?,
) {
    fun clause(id: String): Clause? = clauses.firstOrNull { it.id == id }

    fun toJson(): JsonObject = mapOf(
        "id" to id,
        "version" to version,
        "name" to name,
        "description" to description,
        "clauses" to clauses.map { it.toJson() },
        "createdAt" to Instants.render(createdAt),
        "basedOnVersion" to basedOnVersion,
    )

    companion object {
        fun fromJson(j: JsonObject) = Policy(
            id = j["id"] as String,
            version = (j["version"] as Number).toInt(),
            name = j["name"] as String,
            description = (j["description"] as? String) ?: "",
            clauses = Json.arr(j["clauses"]).map { Clause.fromJson(Json.obj(it)) },
            createdAt = Instants.parse(j["createdAt"] as String),
            basedOnVersion = (j["basedOnVersion"] as? Number)?.toInt(),
        )
    }
}

enum class StoredState { PENDING, ACTIVE, REVOKED }

enum class ViewStatus { PENDING, SCHEDULED, ACTIVE, EXPIRED, REVOKED }

data class Approval(val reviewer: String, val at: Instant) {
    fun toJson() = mapOf("reviewer" to reviewer, "at" to Instants.render(at))
    companion object {
        fun fromJson(j: JsonObject) = Approval(j["reviewer"] as String, Instants.parse(j["at"] as String))
    }
}

data class VersionRef(val id: String, val versionNo: Int) {
    fun toJson() = mapOf("id" to id, "versionNo" to versionNo)
    companion object {
        fun fromJson(j: JsonObject) = VersionRef(j["id"] as String, (j["versionNo"] as Number).toInt())
    }
}

data class ScopeSnapshot(
    val totalSubjects: Int,
    val matched: Int,
    val sampleIds: List<String>,
    val broad: Boolean,
    val validExpression: Boolean,
    val error: String?,
) {
    fun toJson(): JsonObject = mapOf(
        "totalSubjects" to totalSubjects,
        "matched" to matched,
        "sampleIds" to sampleIds,
        "broad" to broad,
        "validExpression" to validExpression,
        "error" to error,
    )

    companion object {
        val BROAD_LIMIT = 10
        val SAMPLE_SIZE = 5

        fun fromJson(j: Map<String, Any?>?) = if (j == null) null else ScopeSnapshot(
            totalSubjects = (j["totalSubjects"] as Number).toInt(),
            matched = (j["matched"] as Number).toInt(),
            sampleIds = Json.arr(j["sampleIds"]).map { it as String },
            broad = j["broad"] as Boolean,
            validExpression = j["validExpression"] as Boolean,
            error = j["error"] as String?,
        )
    }
}

data class ExceptionRecord(
    val id: String,
    val chainId: String,
    val versionNo: Int,
    val rev: Long,
    val policyId: String,
    val policyVersion: Int,
    val subjectExpr: String,
    val relaxedClauseIds: List<String>,
    val startAt: Instant,
    val endAt: Instant,
    val evidenceSummary: String,
    val evidenceBlobId: String?,
    val submitter: String,
    val approvals: List<Approval>,
    val storedState: StoredState,
    val revokedBy: String?,
    val revokedAt: Instant?,
    val revokeReason: String?,
    val renewedFrom: VersionRef?,
    val scope: ScopeSnapshot?,
    val createdAt: Instant,
) {
    fun statusAt(at: Instant): ViewStatus = when (storedState) {
        StoredState.PENDING -> ViewStatus.PENDING
        StoredState.REVOKED -> ViewStatus.REVOKED
        StoredState.ACTIVE -> when {
            at < startAt -> ViewStatus.SCHEDULED
            at >= endAt -> ViewStatus.EXPIRED
            else -> ViewStatus.ACTIVE
        }
    }

    /** 决策时刻是否可用于放行：已激活且在 [start,end) 区间内，且未撤销。 */
    fun eligibleAt(at: Instant): Boolean =
        storedState == StoredState.ACTIVE && !at.isBefore(startAt) && at.isBefore(endAt)

    fun withBump(nextState: StoredState? = null): ExceptionRecord =
        copy(storedState = nextState ?: storedState, rev = rev + 1)

    fun toJson(): JsonObject = mapOf(
        "id" to id,
        "chainId" to chainId,
        "versionNo" to versionNo,
        "rev" to rev,
        "policyId" to policyId,
        "policyVersion" to policyVersion,
        "subjectExpr" to subjectExpr,
        "relaxedClauseIds" to relaxedClauseIds,
        "startAt" to Instants.render(startAt),
        "endAt" to Instants.render(endAt),
        "evidenceSummary" to evidenceSummary,
        "evidenceBlobId" to evidenceBlobId,
        "submitter" to submitter,
        "approvals" to approvals.map { it.toJson() },
        "storedState" to storedState.name,
        "revokedBy" to revokedBy,
        "revokedAt" to revokedAt?.let { Instants.render(it) },
        "revokeReason" to revokeReason,
        "renewedFrom" to renewedFrom?.toJson(),
        "scope" to scope?.toJson(),
        "createdAt" to Instants.render(createdAt),
    )

    companion object {
        fun fromJson(j: JsonObject) = ExceptionRecord(
            id = j["id"] as String,
            chainId = j["chainId"] as String,
            versionNo = (j["versionNo"] as Number).toInt(),
            rev = (j["rev"] as Number).toLong(),
            policyId = j["policyId"] as String,
            policyVersion = (j["policyVersion"] as Number).toInt(),
            subjectExpr = j["subjectExpr"] as String,
            relaxedClauseIds = Json.arr(j["relaxedClauseIds"]).map { it as String },
            startAt = Instants.parse(j["startAt"] as String),
            endAt = Instants.parse(j["endAt"] as String),
            evidenceSummary = (j["evidenceSummary"] as? String) ?: "",
            evidenceBlobId = j["evidenceBlobId"] as String?,
            submitter = j["submitter"] as String,
            approvals = Json.arr(j["approvals"]).map { Approval.fromJson(Json.obj(it)) },
            storedState = StoredState.valueOf(j["storedState"] as String),
            revokedBy = j["revokedBy"] as String?,
            revokedAt = (j["revokedAt"] as String?)?.let { Instants.parse(it) },
            revokeReason = j["revokeReason"] as String?,
            renewedFrom = (j["renewedFrom"] as? Map<String, Any?>)?.let(VersionRef::fromJson),
            scope = ScopeSnapshot.fromJson(j["scope"] as? Map<String, Any?>),
            createdAt = Instants.parse(j["createdAt"] as String),
        )
    }
}

data class AuditEvent(
    val seq: Long,
    val at: Instant,
    val type: String,
    val actor: String,
    val targetId: String?,
    val chainId: String?,
    val ok: Boolean,
    val reason: String?,
    val prevHash: String,
    val hash: String,
) {
    fun toJson(): JsonObject = mapOf(
        "seq" to seq,
        "at" to Instants.render(at),
        "type" to type,
        "actor" to actor,
        "targetId" to targetId,
        "chainId" to chainId,
        "ok" to ok,
        "reason" to reason,
        "prevHash" to prevHash,
        "hash" to hash,
    )

    companion object {
        val GENESIS = "0".repeat(64)

        fun body(
            seq: Long, at: Instant, type: String, actor: String,
            targetId: String?, chainId: String?, ok: Boolean, reason: String?,
            prevHash: String,
        ): String {
            val payload = mapOf(
                "seq" to seq,
                "at" to Instants.render(at),
                "type" to type,
                "actor" to actor,
                "targetId" to targetId,
                "chainId" to chainId,
                "ok" to ok,
                "reason" to reason,
                "prevHash" to prevHash,
            )
            return Json.write(payload, canonical = true)
        }

        fun fromJson(j: JsonObject) = AuditEvent(
            seq = (j["seq"] as Number).toLong(),
            at = Instants.parse(j["at"] as String),
            type = j["type"] as String,
            actor = j["actor"] as String,
            targetId = j["targetId"] as String?,
            chainId = j["chainId"] as String?,
            ok = j["ok"] as Boolean,
            reason = j["reason"] as String?,
            prevHash = j["prevHash"] as String,
            hash = j["hash"] as String,
        )
    }
}

data class ExceptionUse(
    val exceptionId: String,
    val chainId: String,
    val versionNo: Int,
    val clauseId: String,
) {
    fun toJson() = mapOf(
        "exceptionId" to exceptionId,
        "chainId" to chainId,
        "versionNo" to versionNo,
        "clauseId" to clauseId,
    )

    companion object {
        fun fromJson(j: JsonObject) = ExceptionUse(
            j["exceptionId"] as String,
            j["chainId"] as String,
            (j["versionNo"] as Number).toInt(),
            j["clauseId"] as String,
        )
    }
}

data class DecisionRecord(
    val id: Long,
    val at: Instant,
    val policyId: String,
    val policyVersion: Int,
    val fact: JsonObject,
    val hits: List<String>,
    val reliefs: List<ExceptionUse>,
    val remaining: List<String>,
    val conclusion: String,
    val fingerprint: String,
) {
    fun toJson(): JsonObject = mapOf(
        "id" to id,
        "at" to Instants.render(at),
        "policyId" to policyId,
        "policyVersion" to policyVersion,
        "fact" to fact,
        "hits" to hits,
        "reliefs" to reliefs.map { it.toJson() },
        "remaining" to remaining,
        "conclusion" to conclusion,
        "fingerprint" to fingerprint,
    )

    companion object {
        fun fromJson(j: JsonObject) = DecisionRecord(
            id = (j["id"] as Number).toLong(),
            at = Instants.parse(j["at"] as String),
            policyId = j["policyId"] as String,
            policyVersion = (j["policyVersion"] as Number).toInt(),
            fact = Json.obj(j["fact"]),
            hits = Json.arr(j["hits"]).map { it as String },
            reliefs = Json.arr(j["reliefs"]).map { ExceptionUse.fromJson(Json.obj(it)) },
            remaining = Json.arr(j["remaining"]).map { it as String },
            conclusion = j["conclusion"] as String,
            fingerprint = j["fingerprint"] as String,
        )

        fun computeFingerprint(
            at: Instant, policyId: String, policyVersion: Int, fact: JsonObject,
            hits: List<String>, reliefs: List<ExceptionUse>, remaining: List<String>,
            conclusion: String,
        ): String {
            val payload = mapOf(
                "at" to Instants.render(at),
                "policyId" to policyId,
                "policyVersion" to policyVersion,
                "fact" to fact,
                "hits" to hits.sorted(),
                "reliefs" to reliefs.map {
                    mapOf(
                        "chainId" to it.chainId,
                        "versionNo" to it.versionNo,
                        "clauseId" to it.clauseId,
                    )
                }.sortedBy { "${it["chainId"]}#${it["versionNo"]}#${it["clauseId"]}" },
                "remaining" to remaining.sorted(),
                "conclusion" to conclusion,
            )
            return Crypto.sha256Hex(Json.write(payload, canonical = true))
        }
    }
}

data class SubjectEntry(val id: String, val attrs: JsonObject) {
    fun toJson() = mapOf("id" to id, "attrs" to attrs)
    companion object {
        fun fromJson(j: JsonObject) = SubjectEntry(j["id"] as String, Json.obj(j["attrs"]))
    }
}
