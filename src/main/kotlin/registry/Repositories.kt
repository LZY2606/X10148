package registry

import java.nio.file.Path

class PolicyRepository(private val storage: FileStorage) {
    fun all(): List<Policy> = storage.listJson(storage.policies).map { Policy.fromJson(it.second) }

    fun save(policy: Policy) {
        storage.writeJson(storage.policies.resolve("${policy.id}.v${policy.version}.json"), policy.toJson())
    }

    fun find(id: String, version: Int): Policy? =
        storage.readJsonOrNull(storage.policies.resolve("${id}.v$version.json"))?.let {
            Policy.fromJson(Json.obj(it))
        }

    fun versions(id: String): List<Policy> = all().filter { it.id == id }.sortedBy { it.version }

    fun latest(id: String): Policy? = versions(id).maxByOrNull { it.version }
}

class ExceptionRepository(private val storage: FileStorage) {
    fun all(): List<ExceptionRecord> = storage.listJson(storage.exceptions).map { ExceptionRecord.fromJson(it.second) }

    fun save(record: ExceptionRecord) {
        storage.writeJson(storage.exceptions.resolve("${record.id}.json"), record.toJson())
    }

    fun find(id: String): ExceptionRecord? =
        storage.readJsonOrNull(storage.exceptions.resolve("$id.json"))?.let {
            ExceptionRecord.fromJson(Json.obj(it))
        }

    fun chain(chainId: String): List<ExceptionRecord> =
        all().filter { it.chainId == chainId }.sortedBy { it.versionNo }
}

class SubjectRepository(private val storage: FileStorage) {
    fun all(): List<SubjectEntry> = storage.listJson(storage.subjects).map { SubjectEntry.fromJson(it.second) }

    fun save(entry: SubjectEntry) {
        storage.writeJson(storage.subjects.resolve("${entry.id}.json"), entry.toJson())
    }

    fun delete(id: String) = storage.deleteFile(storage.subjects.resolve("$id.json"))

    fun find(id: String): SubjectEntry? =
        storage.readJsonOrNull(storage.subjects.resolve("$id.json"))?.let {
            SubjectEntry.fromJson(Json.obj(it))
        }
}

class DecisionRepository(private val storage: FileStorage) {
    fun all(): List<DecisionRecord> =
        storage.listJson(storage.decisions).map { DecisionRecord.fromJson(it.second) }.sortedBy { it.id }

    fun save(record: DecisionRecord) {
        storage.writeJson(storage.decisions.resolve("%08d.json".format(record.id)), record.toJson())
    }
}

class AuditLog(private val storage: FileStorage) {
    fun events(): List<AuditEvent> =
        storage.listJson(storage.audit).map { AuditEvent.fromJson(it.second) }.sortedBy { it.seq }

    fun verifyChain(): List<String> {
        val errors = ArrayList<String>()
        var prev = AuditEvent.GENESIS
        for (event in events()) {
            if (event.prevHash != prev) errors.add("seq=${event.seq}: prevHash mismatch")
            val recomputed = Crypto.sha256Hex(
                AuditEvent.body(event.seq, event.at, event.type, event.actor, event.targetId,
                    event.chainId, event.ok, event.reason, event.prevHash)
            )
            if (recomputed != event.hash) errors.add("seq=${event.seq}: hash mismatch")
            prev = event.hash
        }
        return errors
    }

    fun append(
        type: String,
        actor: String,
        targetId: String?,
        chainId: String?,
        ok: Boolean,
        reason: String?,
        clock: Clock,
    ): AuditEvent {
        val meta = storage.readMeta()
        val seq = (meta["nextSeq"] as Number).toLong()
        val at = clock.now()
        val events = events()
        val prevHash = events.lastOrNull()?.hash ?: AuditEvent.GENESIS
        val body = AuditEvent.body(seq, at, type, actor, targetId, chainId, ok, reason, prevHash)
        val event = AuditEvent(seq, at, type, actor, targetId, chainId, ok, reason, prevHash, Crypto.sha256Hex(body))
        storage.writeJson(storage.audit.resolve("%08d.json".format(seq)), event.toJson())
        meta["nextSeq"] = seq + 1
        storage.writeMeta(meta)
        return event
    }
}
