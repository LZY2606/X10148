package registry

import java.time.Instant

object SeedData {
    fun install(service: RegistryService) {
        val t0 = Instant.parse("2026-09-20T00:00:00Z")
        val policy = service.createPolicy(
            name = "出站数据访问基线",
            description = "演示策略：对出站连接、加密与公网发布做最小约束",
            clauses = listOf(
                Clause(
                    "c-tls",
                    "强制加密传输",
                    "访问外部目标必须使用 TLS",
                    "transport == 'plaintext'",
                ),
                Clause(
                    "c-public",
                    "禁止公网发布内部数据",
                    "destination 为 public 且携带内部数据时违规",
                    "destination == 'public' && dataClass == 'internal'",
                ),
                Clause(
                    "c-volume",
                    "大批量外发限制",
                    "外发字节数超过 10485760 需审批",
                    "egressBytes > 10485760",
                ),
            ),
            actor = "system",
            at = t0,
        )

        val directory = listOf(
            SubjectEntry("host-db-01", mapOf("host" to "db-01", "team" to "platform", "env" to "prod")),
            SubjectEntry("host-db-02", mapOf("host" to "db-02", "team" to "platform", "env" to "prod")),
            SubjectEntry("host-web-01", mapOf("host" to "web-01", "team" to "web", "env" to "prod")),
            SubjectEntry("host-web-02", mapOf("host" to "web-02", "team" to "web", "env" to "prod")),
            SubjectEntry("host-stage-01", mapOf("host" to "stage-01", "team" to "web", "env" to "stage")),
            SubjectEntry("host-dev-01", mapOf("host" to "dev-01", "team" to "web", "env" to "dev")),
        )
        directory.forEach { service.upsertSubject(it, actor = "system") }

        val blobId = service.storage.saveBlob(
            ("迁移窗口审批单\n申请人: alice\n窗口: 2026-09-21 02:00-04:00 UTC\n批准人: carol,dave")
                .toByteArray(Charsets.UTF_8)
        )
        val exc = service.submitException(
            RegistryService.SubmitRequest(
                policyId = policy.id,
                policyVersion = policy.version,
                subjectExpr = "host == 'db-01'",
                relaxedClauseIds = listOf("c-volume"),
                startAt = Instant.parse("2026-09-21T02:00:00Z"),
                endAt = Instant.parse("2026-09-22T02:00:00Z"),
                evidenceSummary = "迁移窗口审批单 #CHG-2042，限 db-01 大批量外发，窗口 48h",
                evidenceBlobId = blobId,
                submitter = "alice",
            )
        )
        service.confirmException(exc.id, "carol", exc.rev)
        service.confirmException(exc.let { service.exceptions.find(it.id)!! }.id, "dave",
            service.exceptions.find(exc.id)!!.rev)
    }
}
