package registry

import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Seeds a fresh instance with a small data-classification policy, a directory of
 * subjects and one pending exception so the approval flow is visible immediately.
 */
object SeedData {
    fun installIfEmpty(service: RegistryService, store: FileStore) {
        if (store.exists() && store.load().policies.isNotEmpty()) return
        val now = service.clock.now()

        val subjects = listOf(
            SubjectRecord("host-web-01", "web 边界节点 01", mapOf(
                "role" to "web", "zone" to "dmz", "dataLevel" to "public", "owner" to "alice"
            )),
            SubjectRecord("host-web-02", "web 边界节点 02", mapOf(
                "role" to "web", "zone" to "dmz", "dataLevel" to "confidential", "owner" to "bob"
            )),
            SubjectRecord("host-db-01", "数据库节点 01", mapOf(
                "role" to "db", "zone" to "internal", "dataLevel" to "confidential", "owner" to "carol"
            )),
            SubjectRecord("host-db-02", "数据库节点 02", mapOf(
                "role" to "db", "zone" to "internal", "dataLevel" to "restricted", "owner" to "carol"
            )),
            SubjectRecord("host-lab-01", "实验节点 01", mapOf(
                "role" to "lab", "zone" to "lab", "dataLevel" to "internal", "owner" to "dave"
            )),
            SubjectRecord("host-lab-02", "实验节点 02", mapOf(
                "role" to "lab", "zone" to "lab", "dataLevel" to "internal", "owner" to "dave"
            ))
        )
        subjects.forEach { service.upsertSubject("system", it.subjectId, it.displayName, it.attributes) }

        val policy = service.editPolicy(
            actor = "system",
            policyId = null,
            title = "数据分类外发基线策略",
            clauses = listOf(
                ClauseDraft(
                    id = "C-DMZ-CONF",
                    title = "DMZ 不得处理机密数据",
                    severity = "high",
                    predicateExpr = "zone = 'dmz' and dataLevel = 'confidential'",
                    rationale = "DMZ 暴露面大，机密及以上数据不得落地或外发。"
                ),
                ClauseDraft(
                    id = "C-RESTRICT-EXPORT",
                    title = "受限数据禁止公网外发",
                    severity = "critical",
                    predicateExpr = "dataLevel = 'restricted'",
                    rationale = "受限数据仅允许在受控内网环境处理。"
                ),
                ClauseDraft(
                    id = "C-WEAK-TLS",
                    title = "禁止使用弱 TLS",
                    severity = "medium",
                    predicateExpr = "tlsVersion ~ '1.1'",
                    rationale = "TLS 1.1 及以下存在已知弱点。"
                )
            ),
            changeNote = "初始策略版本"
        )

        service.submitException(
            actor = "carol",
            policyId = policy.policyId,
            policyVersion = policy.version,
            subjectExpr = "role = 'lab'",
            clauseIds = listOf("C-WEAK-TLS"),
            validFrom = now.minus(1, ChronoUnit.HOURS),
            validUntil = now.plus(72, ChronoUnit.HOURS),
            justification = "实验室内一台旧探针仅支持 TLS 1.1，等待厂商固件升级窗口。",
            evidenceSummary = "固件升级工单 OPS-4417；风险接受书由实验室负责人签署；仅限 role=lab。",
            blobId = null
        )
    }
}
