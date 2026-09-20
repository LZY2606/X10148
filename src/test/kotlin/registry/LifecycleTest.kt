package registry

import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LifecycleTest {

    // ---- 撤销不改变过去的决策记录 ----------------------------------------

    @Test
    fun `revocation stops future waivers but historical decisions remain`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            val exception = harness.submittedException(expr = "zone = 'dmz'", clauses = listOf("C1", "C2"))
            val active = harness.activate(exception)

            val before = harness.service.decide("reviewer", active.policyId, 1, "s-dmz")
            assertEquals(DecisionVerdict.ALLOW, before.verdict)
            val historicalFingerprint = before.record.fingerprint

            harness.service.revoke("security-lead", active.exceptionId, active.statusRevision, "规则重新评估")
            val stored = harness.service.exception(active.exceptionId)!!
            assertEquals(ExceptionStatus.REVOKED, stored.status)

            val after = harness.service.decide("reviewer", active.policyId, 1, "s-dmz")
            assertEquals(DecisionVerdict.DENY, after.verdict)
            assertEquals(emptyList(), after.record.waivedClauseIds)

            // Past decision is retained verbatim.
            val history = harness.service.snapshot().decisions.first { it.decisionId == before.record.decisionId }
            assertEquals(historicalFingerprint, history.fingerprint)
            assertEquals(DecisionVerdict.ALLOW, history.verdict)
        } finally {
            harness.cleanup()
        }
    }

    // ---- 续期必须创建新版本并指向旧例外 ----------------------------------

    @Test
    fun `renewal creates a new chain version pointing at the old one`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            val exception = harness.submittedException(
                from = harness.base,
                until = harness.base.plus(48, ChronoUnit.HOURS),
                clauses = listOf("C1")
            )
            val active = harness.activate(exception)
            val newUntil = harness.base.plus(96, ChronoUnit.HOURS)

            val (superseded, renewed) = harness.service.renew(
                actor = "carol",
                exceptionId = active.exceptionId,
                expectedStatusRevision = active.statusRevision,
                validFrom = harness.base.plusSeconds(1),
                validUntil = newUntil,
                justification = "",
                evidenceSummary = "续期工单 T-2",
                blobId = null
            )

            assertEquals(ExceptionStatus.SUPERSEDED, superseded.status)
            assertEquals(ExceptionStatus.PENDING, renewed.status)
            assertEquals(active.exceptionId, renewed.renewedFrom)
            assertEquals(active.chainId, renewed.chainId)
            assertEquals(2, renewed.chainVersion)
            assertNotEquals(active.exceptionId, renewed.exceptionId)
            // Old interval is untouched (no in-place extension).
            assertEquals(active.validUntil, superseded.validUntil)

            val chain = harness.service.chain(active.chainId)
            assertEquals(listOf(1, 2), chain.map { it.chainVersion })
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `renewed version needs two approvals before granting waivers`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            val exception = harness.submittedException(clauses = listOf("C1", "C2"))
            val active = harness.activate(exception)
            val (_, renewed) = harness.service.renew(
                "carol", active.exceptionId, active.statusRevision,
                harness.base, harness.base.plus(72, ChronoUnit.HOURS), "", "T-2", null
            )
            val pendingDecision = harness.service.decide(
                "reviewer", active.policyId, 1, "s-dmz", at = harness.base.plusSeconds(10), persist = false
            )
            // Old version superseded, new one still pending.
            assertEquals(DecisionVerdict.DENY, pendingDecision.verdict)

            harness.service.approve("alice", renewed.exceptionId, renewed.statusRevision)
            val renewed2 = harness.service.approve("bob", renewed.exceptionId, renewed.statusRevision + 1)
            assertEquals(ExceptionStatus.ACTIVE, renewed2.status)
            val allowed = harness.service.decide(
                "reviewer", active.policyId, 1, "s-dmz", at = harness.base.plusSeconds(10), persist = false
            )
            assertEquals(DecisionVerdict.ALLOW, allowed.verdict)
            assertEquals(renewed.exceptionId, allowed.waived.first().exceptionId)
        } finally {
            harness.cleanup()
        }
    }

    // ---- 并发冲突：状态版本号裁决 ----------------------------------------

    @Test
    fun `stale status revision loses the activation race`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            val exception = harness.submittedException()
            // First approval bumps status revision.
            val afterFirst = harness.service.approve("alice", exception.exceptionId, exception.statusRevision)
            val staleRevision = exception.statusRevision

            val conflict = assertThrowsRegistry {
                harness.service.approve("bob", exception.exceptionId, staleRevision)
            }
            assertTrue(conflict is ConflictException)
            assertEquals(staleRevision, (conflict as ConflictException).expectedRevision)
            assertEquals(afterFirst.statusRevision, conflict.actualRevision)

            // A fresh request with the current revision succeeds and activates.
            val activated = harness.service.approve("bob", exception.exceptionId, afterFirst.statusRevision)
            assertEquals(ExceptionStatus.ACTIVE, activated.status)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `concurrent revoke and renew are arbitrated by revision and both leave audit events`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            val exception = harness.submittedException(clauses = listOf("C1", "C2"))
            val active = harness.activate(exception)
            val auditBefore = harness.service.snapshot().audit.size

            harness.service.revoke("sec", active.exceptionId, active.statusRevision, "紧急撤销")
            val renewError = assertThrowsRegistry {
                harness.service.renew(
                    "carol", active.exceptionId, active.statusRevision,
                    harness.base, harness.base.plus(72, ChronoUnit.HOURS), "", "T-2", null
                )
            }
            // Revoke already bumped the status revision, so the stale renew loses the race.
            assertTrue(renewError is ConflictException)
            assertTrue(renewError.message!!.contains("版本冲突"))

            val audit = harness.service.snapshot().audit
            assertTrue(audit.size >= auditBefore + 2)
            val failedRenew = audit.last { it.action == "exception.renew" && it.outcome.startsWith("failure") }
            // Failure audit must not carry evidence material.
            assertTrue(failedRenew.detail.keys.none { it.contains("summary", ignoreCase = true) })
            assertEquals("carol", failedRenew.actor)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `failed approval still appends a non-sensitive audit event`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            val exception = harness.submittedException(actor = "carol")
            assertThrowsRegistry {
                harness.service.approve("carol", exception.exceptionId, 1)
            }
            val event = harness.service.snapshot().audit.last { it.action == "exception.approve" }
            assertTrue(event.outcome.startsWith("failure"))
            assertTrue(event.detail["reason"]!!.contains("提交者不能审核"))
        } finally {
            harness.cleanup()
        }
    }
}
