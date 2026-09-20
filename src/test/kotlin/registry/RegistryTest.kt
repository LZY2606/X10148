package registry

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class RegistryTest {
    private lateinit var dir: Path
    private lateinit var clock: MutableClock
    private lateinit var service: RegistryService

    private val t0 = Instant.parse("2026-09-21T00:00:00Z")
    private val start = Instant.parse("2026-09-21T02:00:00Z")
    private val end = Instant.parse("2026-09-21T04:00:00Z")

    @BeforeEach
    fun setup() {
        dir = Files.createTempDirectory("registry-test")
        clock = MutableClock(t0)
        service = RegistryService(FileStorage(dir), clock, seed = false)
        listOf(
            SubjectEntry("db-01", mapOf("host" to "db-01", "team" to "platform", "env" to "prod")),
            SubjectEntry("db-02", mapOf("host" to "db-02", "team" to "platform", "env" to "prod")),
            SubjectEntry("web-01", mapOf("host" to "web-01", "team" to "web", "env" to "prod")),
        ).forEach { service.upsertSubject(it, "system") }
    }

    @AfterEach
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun makePolicy(clauses: List<Clause> = defaultClauses()): Policy =
        service.createPolicy("测试策略", "", clauses, actor = "admin")

    private fun defaultClauses() = listOf(
        Clause("c-tls", "强制TLS", "", "transport == 'plaintext'"),
        Clause("c-public", "禁止公网内部数据", "", "destination == 'public' && dataClass == 'internal'"),
        Clause("c-volume", "大批量外发", "", "egressBytes > 10485760"),
    )

    private fun submit(
        policy: Policy,
        expr: String = "host == 'db-01'",
        clauseIds: List<String> = listOf("c-volume"),
        submitter: String = "alice",
        startAt: Instant = start,
        endAt: Instant = end,
        summary: String = "审批单 T-1",
        blobId: String? = null,
    ): ExceptionRecord = service.submitException(
        RegistryService.SubmitRequest(
            policy.id, policy.version, expr, clauseIds, startAt, endAt, summary, blobId, submitter
        )
    )

    private fun activate(record: ExceptionRecord, r1: String = "carol", r2: String = "dave"): ExceptionRecord {
        service.confirmException(record.id, r1, record.rev)
        val mid = service.exceptions.find(record.id)!!
        return service.confirmException(record.id, r2, mid.rev)
    }

    private fun violatingFact(
        policy: Policy,
        host: String = "db-01",
        publicInternal: Boolean = true,
        bigEgress: Boolean = true,
    ): JsonObject = mapOf(
        "policyId" to policy.id,
        "policyVersion" to policy.version,
        "host" to host,
        "transport" to "tls",
        "destination" to if (publicInternal) "public" else "partner",
        "dataClass" to if (publicInternal) "internal" else "public",
        "egressBytes" to if (bigEgress) 20_000_000L else 100L,
    )

    @Test
    fun `条款级放宽只豁免明确列出的条款`() {
        val policy = makePolicy()
        val record = submit(policy)
        activate(record)
        clock.instant = Instant.parse("2026-09-21T03:00:00Z")

        val ex = DecisionEngine.evaluate(service, violatingFact(policy), clock.now(), persist = false)
        assertTrue(ex.hits.map { it.clauseId }.containsAll(listOf("c-public", "c-volume")))
        assertEquals(listOf("c-volume"), ex.reliefs.map { it.clauseId })
        assertEquals(listOf("c-public"), ex.remaining)
        assertEquals("DENIED", ex.conclusion)
    }

    @Test
    fun `放宽全部条款被拒绝，不能跳过整个策略`() {
        val policy = makePolicy()
        val error = assertThrows(ApiError::class.java) {
            submit(policy, clauseIds = listOf("c-tls", "c-public", "c-volume"))
        }
        assertEquals(ErrorKind.VALIDATION, error.kind)
        val rejected = service.auditLog.events().last()
        assertFalse(rejected.ok)
        assertTrue(rejected.type.endsWith("REJECTED"))
    }

    @Test
    fun `自审被拒绝`() {
        val policy = makePolicy()
        val record = submit(policy, submitter = "alice")
        val error = assertThrows(ApiError::class.java) {
            service.confirmException(record.id, "alice", record.rev)
        }
        assertEquals(ErrorKind.SELF_REVIEW, error.kind)
        val reloaded = service.exceptions.find(record.id)!!
        assertEquals(StoredState.PENDING, reloaded.storedState)
        assertTrue(reloaded.approvals.isEmpty())
    }

    @Test
    fun `同一人重复确认不增加计数`() {
        val policy = makePolicy()
        val record = submit(policy)
        service.confirmException(record.id, "carol", record.rev)
        val once = service.exceptions.find(record.id)!!
        assertEquals(1, once.approvals.size)
        val error = assertThrows(ApiError::class.java) {
            service.confirmException(record.id, "carol", once.rev)
        }
        assertEquals(ErrorKind.DUPLICATE_CONFIRMATION, error.kind)
        val again = service.exceptions.find(record.id)!!
        assertEquals(1, again.approvals.size)
        assertEquals(StoredState.PENDING, again.storedState)
        assertEquals(once.rev, again.rev)
    }

    @Test
    fun `区间结束瞬间已经失效`() {
        val policy = makePolicy()
        val record = submit(policy)
        activate(record)

        val volumeOnly = violatingFact(policy, publicInternal = false)
        val lastOk = DecisionEngine.evaluate(service, volumeOnly, end.minusSeconds(1), persist = false)
        assertEquals("ALLOWED_BY_EXCEPTION", lastOk.conclusion)
        assertEquals(listOf("c-volume"), lastOk.reliefs.map { it.clauseId })

        val atEnd = DecisionEngine.evaluate(service, volumeOnly, end, persist = false)
        assertEquals("DENIED", atEnd.conclusion)
        assertEquals(listOf("c-volume"), atEnd.remaining)
        assertEquals(0, atEnd.reliefs.size)
        assertEquals("expired", atEnd.ignoredExceptions.first().status)

        val late = DecisionEngine.evaluate(service, volumeOnly, end.plusSeconds(1), persist = false)
        assertEquals("DENIED", late.conclusion)
    }

    @Test
    fun `撤销后过去的决策记录不改变`() {
        val policy = makePolicy()
        val record = submit(policy)
        activate(record)
        clock.instant = Instant.parse("2026-09-21T03:00:00Z")
        val relievable = violatingFact(policy, publicInternal = false)
        val before = DecisionEngine.evaluate(service, relievable, clock.now(), persist = true)
        assertEquals("ALLOWED_BY_EXCEPTION", before.conclusion)
        val savedFingerprint = before.decision!!.fingerprint
        val savedId = before.decision.id

        val active = service.exceptions.find(record.id)!!
        service.revokeException(record.id, "boss", "事后发现材料不实", active.rev)
        assertEquals(StoredState.REVOKED, service.exceptions.find(record.id)!!.storedState)

        val afterNow = DecisionEngine.evaluate(service, relievable, clock.now(), persist = false)
        assertEquals("DENIED", afterNow.conclusion)
        assertEquals(0, afterNow.reliefs.size)

        val historical = service.decisions.all().first { it.id == savedId }
        assertEquals(savedFingerprint, historical.fingerprint)
        assertEquals("ALLOWED_BY_EXCEPTION", historical.conclusion)
    }

    @Test
    fun `续期必须创建新版本并指向旧例外，不能原地延长`() {
        val policy = makePolicy()
        val record = submit(policy)
        activate(record)
        val v1End = record.endAt

        val active = service.exceptions.find(record.id)!!
        val renewed = service.renewException(
            RegistryService.RenewRequest(
                exceptionId = record.id,
                actor = "alice",
                startAt = end,
                endAt = end.plusSeconds(7200),
                subjectExpr = null,
                relaxedClauseIds = null,
                evidenceSummary = null,
                evidenceBlobId = null,
                expectedRev = active.rev,
            )
        )
        assertEquals(record.chainId, renewed.chainId)
        assertEquals(2, renewed.versionNo)
        assertEquals(record.id, renewed.renewedFrom!!.id)
        assertEquals(1, renewed.renewedFrom.versionNo)
        assertEquals(StoredState.PENDING, renewed.storedState)
        assertEquals(1, renewed.rev)

        val old = service.exceptions.find(record.id)!!
        assertEquals(v1End, old.endAt)
        assertEquals(StoredState.ACTIVE, old.storedState)

        val chain = service.exceptions.chain(record.chainId)
        assertEquals(listOf(1, 2), chain.map { it.versionNo })
    }

    @Test
    fun `并发冲突以版本号裁决，失败也留下不含材料的审计事件`() {
        val policy = makePolicy()
        val record = submit(policy, summary = "敏感材料内容 SENSITIVE-XYZ")
        val staleRev = record.rev

        service.confirmException(record.id, "carol", record.rev)
        val conflict = assertThrows(ApiError::class.java) {
            service.confirmException(record.id, "dave", staleRev)
        }
        assertEquals(ErrorKind.CONFLICT, conflict.kind)

        val reloaded = service.exceptions.find(record.id)!!
        assertEquals(1, reloaded.approvals.size)

        val event = service.auditLog.events().last()
        assertFalse(event.ok)
        assertFalse(event.reason!!.contains("SENSITIVE"))
        assertFalse(event.toString().contains("SENSITIVE-XYZ"))
    }

    @Test
    fun `并发续期冲突时只有一个新版本`() {
        val policy = makePolicy()
        val record = submit(policy)
        activate(record)
        val current = service.exceptions.find(record.id)!!

        val first = service.renewException(
            RegistryService.RenewRequest(record.id, "alice", end, end.plusSeconds(3600),
                null, null, null, null, current.rev)
        )
        assertEquals(2, first.versionNo)
        // 另一客户端仍拿着旧版本号发起续期：版本号裁决失败
        val error = assertThrows(ApiError::class.java) {
            service.renewException(
                RegistryService.RenewRequest(record.id, "bob", end, end.plusSeconds(7200),
                    null, null, null, null, current.rev)
            )
        }
        assertTrue(error.kind == ErrorKind.CONFLICT || error.kind == ErrorKind.CHAIN_CONFLICT)
        assertEquals(1, service.exceptions.chain(record.chainId).count { it.versionNo == 2 })
    }

    @Test
    fun `删除 blob 后历史仍保留摘要`() {
        val policy = makePolicy()
        val blobId = service.storage.saveBlob("审批单正文 SECRET".toByteArray())
        val record = submit(policy, blobId = blobId, summary = "摘要：变更窗口 T-1 的审批说明")
        activate(record)
        clock.instant = Instant.parse("2026-09-21T03:00:00Z")
        DecisionEngine.evaluate(service, violatingFact(policy, publicInternal = false), clock.now(), persist = true)

        val blobs = BlobService(service)
        blobs.delete(blobId, "admin")
        assertFalse(service.storage.blobExists(blobId))
        assertThrows(ApiError::class.java) { blobs.download(blobId) }

        val reloaded = service.exceptions.find(record.id)!!
        assertEquals("摘要：变更窗口 T-1 的审批说明", reloaded.evidenceSummary)
        assertEquals(blobId, reloaded.evidenceBlobId)
        val decision = service.decisions.all().single()
        assertEquals("ALLOWED_BY_EXCEPTION", decision.conclusion)

        val ex = DecisionEngine.evaluate(service, violatingFact(policy, publicInternal = false), clock.now(), persist = false)
        assertEquals("摘要：变更窗口 T-1 的审批说明", ex.reliefs.single().reason)
    }

    @Test
    fun `时钟推进驱动 scheduled 到 active 到 expired 的派生状态`() {
        val policy = makePolicy()
        val record = submit(policy, startAt = start, endAt = end)
        activate(record)
        val id = record.id

        assertEquals(ViewStatus.SCHEDULED, service.exceptions.find(id)!!.statusAt(start.minusSeconds(1)))
        assertEquals(ViewStatus.ACTIVE, service.exceptions.find(id)!!.statusAt(start))
        assertEquals(ViewStatus.ACTIVE, service.exceptions.find(id)!!.statusAt(end.minusSeconds(1)))
        assertEquals(ViewStatus.EXPIRED, service.exceptions.find(id)!!.statusAt(end))
    }

    @Test
    fun `对象表达式交集过大时给出稳定样例`() {
        repeat(12) { n ->
            service.upsertSubject(
                SubjectEntry("bulk-$n", mapOf("host" to "bulk-$n", "team" to "platform", "env" to "prod")),
                "system"
            )
        }
        val policy = makePolicy()
        val record = submit(policy, expr = "team == 'platform'")
        val scope = record.scope!!
        assertTrue(scope.broad)
        assertTrue(scope.matched > ScopeSnapshot.BROAD_LIMIT)
        assertEquals(ScopeSnapshot.SAMPLE_SIZE, scope.sampleIds.size)
        val again = ScopeAnalyzer.analyze("team == 'platform'", service.subjects.all()).snapshot()
        assertEquals(scope.sampleIds, again.sampleIds)
    }

    @Test
    fun `导出后重建实例，指纹与审计顺序一致`() {
        val policy = makePolicy()
        val blobId = service.storage.saveBlob("审批正文".toByteArray())
        val record = submit(policy, blobId = blobId)
        activate(record)
        clock.instant = Instant.parse("2026-09-21T03:00:00Z")
        val original = DecisionEngine.evaluate(service, violatingFact(policy), clock.now(), persist = true)
        val originalAudit = service.auditLog.events().map { it.seq to it.hash }

        val zipBytes = Backup.exportZip(service)
        val target = Files.createTempDirectory("registry-restore")
        target.toFile().deleteRecursively()
        val restored = Backup.importInto(target, zipBytes, MutableClock(clock.instant))

        assertTrue(restored.auditLog.verifyChain().isEmpty())
        assertEquals(originalAudit, restored.auditLog.events().map { it.seq to it.hash })
        val restoredDecision = restored.decisions.all().single()
        assertEquals(original.fingerprint, restoredDecision.fingerprint)

        val replayed = DecisionEngine.evaluate(restored, violatingFact(policy), clock.instant, persist = false)
        assertEquals(original.fingerprint, replayed.fingerprint)
        assertNotEquals(restored.storage.root, service.storage.root)
        target.toFile().deleteRecursively()
    }

    @Test
    fun `区间结束后激活被拒绝`() {
        val policy = makePolicy()
        val record = submit(policy)
        clock.instant = end
        val error = assertThrows(ApiError::class.java) {
            service.confirmException(record.id, "carol", record.rev)
        }
        assertEquals(ErrorKind.INTERVAL_CLOSED, error.kind)
        assertEquals(StoredState.PENDING, service.exceptions.find(record.id)!!.storedState)
    }

    @Test
    fun `策略编辑生成版本链，例外固定在所选版本`() {
        val v1 = makePolicy()
        val record = submit(v1)
        activate(record)
        val v2 = service.editPolicy(
            v1.id, "测试策略-修订", "",
            defaultClauses() + Clause("c-new", "新条款", "", "destination == 'partner'"),
            "admin"
        )
        assertEquals(2, v2.version)
        assertEquals(v1.id, v2.id)

        val bound = service.exceptions.find(record.id)!!
        assertEquals(1, bound.policyVersion)

        clock.instant = Instant.parse("2026-09-21T03:00:00Z")
        val onV1 = DecisionEngine.evaluate(service, violatingFact(v1, publicInternal = false), clock.now(), persist = false)
        assertEquals("ALLOWED_BY_EXCEPTION", onV1.conclusion)

        val factV2 = violatingFact(v1, publicInternal = false)
        val onV2 = DecisionEngine.evaluate(service, factV2, clock.now(),
            policyVersionOverride = 2, persist = false)
        assertEquals(2, onV2.policyVersion)
        assertEquals("DENIED", onV2.conclusion)
        // v2 上没有任何例外，命中的条款都无人放行
        assertTrue(onV2.remaining.contains("c-volume"))
        assertTrue(onV2.reliefs.isEmpty())
    }
}
