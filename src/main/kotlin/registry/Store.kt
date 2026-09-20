package registry

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/** File-backed persistence: one JSON document written atomically (tmp + move). */
class FileStore(private val path: Path) {
    fun exists(): Boolean = Files.exists(path)

    fun load(): Snapshot {
        if (!Files.exists(path)) return emptySnapshot()
        val text = Files.readString(path, StandardCharsets.UTF_8)
        if (text.isBlank()) return emptySnapshot()
        return decode(Json.parse(text) as Map<*, *>)
    }

    fun save(snapshot: Snapshot) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, Json.stringify(encode(snapshot)), StandardCharsets.UTF_8)
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        fun emptySnapshot(): Snapshot = Snapshot(
            policies = emptyList(),
            subjects = emptyList(),
            exceptions = emptyList(),
            blobs = emptyList(),
            audit = emptyList(),
            decisions = emptyList(),
            clockBase = Instant.now(),
            clockOffsetSeconds = 0L,
            clockSimulating = false,
            auditSeq = 0L,
            counters = emptyMap()
        )

        @Suppress("UNCHECKED_CAST")
        fun decode(root: Map<*, *>): Snapshot = Snapshot(
            policies = (root["policies"] as List<*>).map { decodePolicy(it as Map<*, *>) },
            subjects = (root["subjects"] as List<*>).map { decodeSubject(it as Map<*, *>) },
            exceptions = (root["exceptions"] as List<*>).map { decodeException(it as Map<*, *>) },
            blobs = (root["blobs"] as List<*>).map { decodeBlob(it as Map<*, *>) },
            audit = (root["audit"] as List<*>).map { decodeAudit(it as Map<*, *>) },
            decisions = (root["decisions"] as List<*>).map { decodeDecision(it as Map<*, *>) },
            clockBase = Instant.parse(root["clockBase"] as String),
            clockOffsetSeconds = (root["clockOffsetSeconds"] as Number).toLong(),
            clockSimulating = root["clockSimulating"] as Boolean,
            simulatedAt = (root["simulatedAt"] as String?)?.let { Instant.parse(it) },
            auditSeq = (root["auditSeq"] as Number).toLong(),
            counters = (root["counters"] as Map<*, *>).entries.associate { it.key as String to (it.value as Number).toInt() }
        )

        private fun decodePolicy(map: Map<*, *>) = PolicyVersion(
            policyId = map["policyId"] as String,
            version = (map["version"] as Number).toInt(),
            title = map["title"] as String,
            clauses = (map["clauses"] as List<*>).map {
                val clause = it as Map<*, *>
                Clause(
                    id = clause["id"] as String,
                    title = clause["title"] as String,
                    severity = clause["severity"] as String,
                    predicateExpr = clause["predicateExpr"] as String,
                    rationale = clause["rationale"] as String
                )
            },
            createdAt = Instant.parse(map["createdAt"] as String),
            editedBy = map["editedBy"] as String,
            changeNote = map["changeNote"] as String,
            supersedesVersion = (map["supersedesVersion"] as Number?)?.toInt()
        )

        private fun decodeSubject(map: Map<*, *>) = SubjectRecord(
            subjectId = map["subjectId"] as String,
            displayName = map["displayName"] as String,
            attributes = (map["attributes"] as Map<*, *>).entries.associate { it.key as String to it.value as String }
        )

        private fun decodeBlob(map: Map<*, *>) = BlobRecord(
            blobId = map["blobId"] as String,
            fileName = map["fileName"] as String,
            mediaType = map["mediaType"] as String,
            sizeBytes = (map["sizeBytes"] as Number).toLong(),
            sha256 = map["sha256"] as String,
            uploadedAt = Instant.parse(map["uploadedAt"] as String),
            deleted = map["deleted"] as Boolean,
            deletedAt = (map["deletedAt"] as String?)?.let { Instant.parse(it) }
        )

        private fun decodeException(map: Map<*, *>) = ExceptionRecord(
            exceptionId = map["exceptionId"] as String,
            chainId = map["chainId"] as String,
            chainVersion = (map["chainVersion"] as Number).toInt(),
            revision = (map["revision"] as Number).toInt(),
            statusRevision = (map["statusRevision"] as Number?)?.toInt() ?: 1,
            renewedFrom = map["renewedFrom"] as String?,
            policyId = map["policyId"] as String,
            policyVersion = (map["policyVersion"] as Number).toInt(),
            subjectExpr = map["subjectExpr"] as String,
            clauseIds = (map["clauseIds"] as List<*>).map { it as String },
            validFrom = Instant.parse(map["validFrom"] as String),
            validUntil = Instant.parse(map["validUntil"] as String),
            justification = map["justification"] as String,
            evidenceSummary = map["evidenceSummary"] as String,
            blobId = map["blobId"] as String?,
            submittedBy = map["submittedBy"] as String,
            submittedAt = Instant.parse(map["submittedAt"] as String),
            approvals = (map["approvals"] as List<*>).map {
                val approval = it as Map<*, *>
                Approval(approval["reviewer"] as String, Instant.parse(approval["at"] as String))
            },
            status = ExceptionStatus.valueOf(map["status"] as String),
            activatedAt = (map["activatedAt"] as String?)?.let { Instant.parse(it) },
            revokedAt = (map["revokedAt"] as String?)?.let { Instant.parse(it) },
            revokedBy = map["revokedBy"] as String?,
            revokeReason = map["revokeReason"] as String?,
            renewedAt = (map["renewedAt"] as String?)?.let { Instant.parse(it) },
            renewedByChain = map["renewedByChain"] as String?,
            scopeMatchedCount = (map["scopeMatchedCount"] as Number).toInt(),
            scopeSamples = (map["scopeSamples"] as List<*>).map { it as String },
            scopeWarning = map["scopeWarning"] as Boolean
        )

        private fun decodeAudit(map: Map<*, *>) = AuditEvent(
            seq = (map["seq"] as Number).toLong(),
            at = Instant.parse(map["at"] as String),
            actor = map["actor"] as String,
            action = map["action"] as String,
            target = map["target"] as String,
            outcome = map["outcome"] as String,
            detail = (map["detail"] as Map<*, *>).entries.associate { it.key as String to it.value as String },
            sensitive = map["sensitive"] as? Boolean ?: false
        )

        private fun decodeDecision(map: Map<*, *>) = DecisionRecord(
            decisionId = map["decisionId"] as String,
            at = Instant.parse(map["at"] as String),
            policyId = map["policyId"] as String,
            policyVersion = (map["policyVersion"] as Number).toInt(),
            subjectId = map["subjectId"] as String,
            verdict = DecisionVerdict.valueOf(map["verdict"] as String),
            triggeredClauseIds = (map["triggeredClauseIds"] as List<*>).map { it as String },
            waivedClauseIds = (map["waivedClauseIds"] as List<*>).map { it as String },
            waiverExceptionIds = (map["waiverExceptionIds"] as List<*>).map { it as String },
            fingerprint = map["fingerprint"] as String
        )

        fun encode(snapshot: Snapshot): Map<String, Any?> = mapOf(
            "policies" to snapshot.policies.map(::encodePolicy),
            "subjects" to snapshot.subjects.map {
                mapOf(
                    "subjectId" to it.subjectId,
                    "displayName" to it.displayName,
                    "attributes" to it.attributes
                )
            },
            "exceptions" to snapshot.exceptions.map(::encodeException),
            "blobs" to snapshot.blobs.map {
                mapOf(
                    "blobId" to it.blobId,
                    "fileName" to it.fileName,
                    "mediaType" to it.mediaType,
                    "sizeBytes" to it.sizeBytes,
                    "sha256" to it.sha256,
                    "uploadedAt" to it.uploadedAt.toString(),
                    "deleted" to it.deleted,
                    "deletedAt" to it.deletedAt?.toString()
                )
            },
            "audit" to snapshot.audit.map {
                mapOf(
                    "seq" to it.seq,
                    "at" to it.at.toString(),
                    "actor" to it.actor,
                    "action" to it.action,
                    "target" to it.target,
                    "outcome" to it.outcome,
                    "detail" to it.detail,
                    "sensitive" to it.sensitive
                )
            },
            "decisions" to snapshot.decisions.map {
                mapOf(
                    "decisionId" to it.decisionId,
                    "at" to it.at.toString(),
                    "policyId" to it.policyId,
                    "policyVersion" to it.policyVersion,
                    "subjectId" to it.subjectId,
                    "verdict" to it.verdict.name,
                    "triggeredClauseIds" to it.triggeredClauseIds,
                    "waivedClauseIds" to it.waivedClauseIds,
                    "waiverExceptionIds" to it.waiverExceptionIds,
                    "fingerprint" to it.fingerprint
                )
            },
            "clockBase" to snapshot.clockBase.toString(),
            "clockOffsetSeconds" to snapshot.clockOffsetSeconds,
            "clockSimulating" to snapshot.clockSimulating,
            "simulatedAt" to snapshot.simulatedAt?.toString(),
            "auditSeq" to snapshot.auditSeq,
            "counters" to snapshot.counters
        )

        private fun encodePolicy(version: PolicyVersion) = mapOf(
            "policyId" to version.policyId,
            "version" to version.version,
            "title" to version.title,
            "clauses" to version.clauses.map {
                mapOf(
                    "id" to it.id,
                    "title" to it.title,
                    "severity" to it.severity,
                    "predicateExpr" to it.predicateExpr,
                    "rationale" to it.rationale
                )
            },
            "createdAt" to version.createdAt.toString(),
            "editedBy" to version.editedBy,
            "changeNote" to version.changeNote,
            "supersedesVersion" to version.supersedesVersion
        )

        private fun encodeException(record: ExceptionRecord) = mapOf(
            "exceptionId" to record.exceptionId,
            "chainId" to record.chainId,
            "chainVersion" to record.chainVersion,
            "revision" to record.revision,
            "statusRevision" to record.statusRevision,
            "renewedFrom" to record.renewedFrom,
            "policyId" to record.policyId,
            "policyVersion" to record.policyVersion,
            "subjectExpr" to record.subjectExpr,
            "clauseIds" to record.clauseIds,
            "validFrom" to record.validFrom.toString(),
            "validUntil" to record.validUntil.toString(),
            "justification" to record.justification,
            "evidenceSummary" to record.evidenceSummary,
            "blobId" to record.blobId,
            "submittedBy" to record.submittedBy,
            "submittedAt" to record.submittedAt.toString(),
            "approvals" to record.approvals.map {
                mapOf("reviewer" to it.reviewer, "at" to it.at.toString())
            },
            "status" to record.status.name,
            "activatedAt" to record.activatedAt?.toString(),
            "revokedAt" to record.revokedAt?.toString(),
            "revokedBy" to record.revokedBy,
            "revokeReason" to record.revokeReason,
            "renewedAt" to record.renewedAt?.toString(),
            "renewedByChain" to record.renewedByChain,
            "scopeMatchedCount" to record.scopeMatchedCount,
            "scopeSamples" to record.scopeSamples,
            "scopeWarning" to record.scopeWarning
        )
    }
}
