package registry

import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Fresh isolated registry instance for tests, with a controllable simulated clock. */
class TestHarness {
    val root = Files.createTempDirectory("registry-test")
    val dataPath = root.resolve("data/state.json")
    val blobPath = root.resolve("blobs")
    val clock = ServiceClock()
    val store = FileStore(dataPath)
    val service = RegistryService(store, clock, blobPath)

    val base: Instant = Instant.parse("2026-03-10T08:00:00Z")

    val policy: PolicyVersion by lazy { policyWithTwoClauses() }

    init {
        clock.freezeAt(base)
    }

    fun policyWithTwoClauses(): PolicyVersion = service.editPolicy(
        actor = "admin",
        policyId = null,
        title = "测试策略",
        clauses = listOf(
            ClauseDraft("C1", "条款一", "high", "zone = 'dmz'", "DMZ 条款"),
            ClauseDraft("C2", "条款二", "critical", "dataLevel = 'restricted'", "受限数据条款")
        ),
        changeNote = "v1"
    )

    fun seedSubjects() {
        service.upsertSubject("admin", "s-dmz", "DMZ 主机", mapOf("zone" to "dmz", "dataLevel" to "restricted"))
        service.upsertSubject("admin", "s-internal", "内网主机", mapOf("zone" to "internal", "dataLevel" to "restricted"))
        (1..7).forEach { index ->
            val padded = index.toString().padStart(2, '0')
            service.upsertSubject("admin", "s-lab-$padded", "实验室 $padded", mapOf("zone" to "lab-$padded"))
        }
    }

    fun submittedException(
        actor: String = "carol",
        expr: String = "zone = 'dmz'",
        clauses: List<String> = listOf("C1"),
        from: Instant = base,
        until: Instant = base.plus(48, ChronoUnit.HOURS)
    ): ExceptionRecord = service.submitException(
        actor = actor,
        policyId = policy.policyId,
        policyVersion = 1,
        subjectExpr = expr,
        clauseIds = clauses,
        validFrom = from,
        validUntil = until,
        justification = "测试理由",
        evidenceSummary = "工单 T-1，风险已接受",
        blobId = null
    )

    fun activate(record: ExceptionRecord, first: String = "alice", second: String = "bob"): ExceptionRecord {
        service.approve(first, record.exceptionId, record.statusRevision)
        return service.approve(second, record.exceptionId, record.statusRevision + 1)
    }

    fun cleanup() {
        root.toFile().deleteRecursively()
    }
}

fun assertThrowsRegistry(block: () -> Unit): RegistryException {
    try {
        block()
    } catch (error: RegistryException) {
        return error
    }
    error("期望抛出 RegistryException")
}
