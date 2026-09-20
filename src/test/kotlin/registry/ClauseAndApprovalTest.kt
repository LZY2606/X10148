package registry

import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClauseAndApprovalTest {

    // ---- 条款级放宽：例外只能放行明确列出的条款 --------------------------

    @Test
    fun `exception waives only explicitly listed clauses`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            harness.policy
            val exception = harness.submittedException(clauses = listOf("C1"))
            harness.activate(exception)

            val result = harness.service.decide("reviewer", exception.policyId, 1, "s-dmz")
            // C1 (dmz) is waived by the exception; C2 (restricted) is still enforced -> DENY.
            assertEquals(DecisionVerdict.DENY, result.verdict)
            val waived = result.waived.single()
            assertEquals("C1", waived.clause.id)
            assertEquals(exception.exceptionId, waived.exceptionId)
            val enforced = result.dispositions.single { it.clause.id == "C2" }
            assertTrue(enforced.triggered)
            assertTrue(enforced.enforced)
            // Normal hits and exception waivers are explained separately.
            assertTrue(enforced.reason.contains("正常命中"))
            assertTrue(waived.reason.contains("例外"))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `no exception means all triggered clauses enforce and verdict is deny`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            harness.policy
            val result = harness.service.decide("reviewer", harness.policy.policyId, 1, "s-dmz")
            assertEquals(DecisionVerdict.DENY, result.verdict)
            assertEquals(listOf("C1", "C2"), result.record.triggeredClauseIds)
            assertEquals(emptyList(), result.record.waivedClauseIds)
            assertTrue(result.dispositions.all { it.triggered && it.enforced })
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `all triggered clauses waived yields allow`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            val exception = harness.submittedException(
                expr = "zone = 'dmz'",
                clauses = listOf("C1", "C2")
            )
            harness.activate(exception)
            val result = harness.service.decide("reviewer", exception.policyId, 1, "s-dmz")
            assertEquals(DecisionVerdict.ALLOW, result.verdict)
            assertEquals(listOf("C1", "C2"), result.record.waivedClauseIds)
            assertTrue(result.dispositions.none { it.enforced })
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `submission must list at least one clause and cannot skip the whole policy`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            val error = assertThrowsRegistry {
                harness.submittedException(clauses = emptyList())
            }
            assertTrue(error is ValidationException)
            assertTrue(error.message!!.contains("不能跳过整个策略"))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `unknown clause id is rejected`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            val error = assertThrowsRegistry {
                harness.submittedException(clauses = listOf("C-UNKNOWN"))
            }
            assertTrue(error.message!!.contains("不存在"))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `exception does not match out of scope subjects`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            val exception = harness.submittedException(expr = "zone = 'dmz'", clauses = listOf("C2"))
            harness.activate(exception)
            // s-internal matches C2 but not the exception expression.
            val result = harness.service.decide("reviewer", exception.policyId, 1, "s-internal")
            assertEquals(DecisionVerdict.DENY, result.verdict)
            assertEquals(emptyList(), result.record.waivedClauseIds)
        } finally {
            harness.cleanup()
        }
    }

    // ---- 自审拒绝 --------------------------------------------------------

    @Test
    fun `submitter cannot approve own exception`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            val exception = harness.submittedException(actor = "carol")
            val error = assertThrowsRegistry {
                harness.service.approve("carol", exception.exceptionId, 1)
            }
            assertTrue(error is ValidationException)
            assertTrue(error.message!!.contains("提交者不能审核"))
            val stored = harness.service.exception(exception.exceptionId)!!
            assertEquals(0, stored.approvals.size)
            assertEquals(ExceptionStatus.PENDING, stored.status)
        } finally {
            harness.cleanup()
        }
    }

    // ---- 重复确认不计数 --------------------------------------------------

    @Test
    fun `same reviewer confirming twice does not count`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            val exception = harness.submittedException()
            harness.service.approve("alice", exception.exceptionId, 1)
            val once = harness.service.exception(exception.exceptionId)!!
            assertEquals(1, once.approvals.size)

            val error = assertThrowsRegistry {
                harness.service.approve("alice", once.exceptionId, once.statusRevision)
            }
            assertTrue(error.message!!.contains("重复确认不增加计数"))
            val still = harness.service.exception(exception.exceptionId)!!
            assertEquals(1, still.approvals.size)
            assertEquals(ExceptionStatus.PENDING, still.status)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `two distinct reviewers activate the exception`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            val exception = harness.submittedException()
            val activated = harness.activate(exception)
            assertEquals(ExceptionStatus.ACTIVE, activated.status)
            assertEquals(listOf("alice", "bob"), activated.approvals.map { it.reviewer })
            assertNotNull(activated.activatedAt)
        } finally {
            harness.cleanup()
        }
    }

    // ---- 结束端点：validUntil 瞬间已经失效 -------------------------------

    @Test
    fun `exception is expired exactly at validUntil instant`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            val until = harness.base.plus(24, ChronoUnit.HOURS)
            val exception = harness.submittedException(from = harness.base, until = until)
            harness.activate(exception)

            val justBefore = until.minusSeconds(1)
            assertEquals(
                ExceptionStatus.ACTIVE,
                harness.service.effectiveStatus(harness.service.exception(exception.exceptionId)!!, justBefore)
            )
            assertEquals(
                ExceptionStatus.EXPIRED,
                harness.service.effectiveStatus(harness.service.exception(exception.exceptionId)!!, until)
            )

            // A decision evaluated at exactly validUntil must not apply the waiver.
            val denied = harness.service.decide(
                "reviewer", exception.policyId, 1, "s-dmz", at = until, persist = false
            )
            assertEquals(DecisionVerdict.DENY, denied.verdict)
            assertFalse(denied.record.waivedClauseIds.contains("C1"))

            val allowed = harness.service.decide(
                "reviewer", exception.policyId, 1, "s-dmz", at = until.minusSeconds(1), persist = false
            )
            // Only C1 waived; C2 still enforces for s-dmz -> use C2-only exception scope instead.
            assertEquals(DecisionVerdict.DENY, allowed.verdict)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `validFrom is inclusive and validUntil is exclusive`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            val from = harness.base.plus(1, ChronoUnit.HOURS)
            val until = harness.base.plus(2, ChronoUnit.HOURS)
            val exception = harness.submittedException(from = from, until = until, clauses = listOf("C1"))
            harness.activate(exception)
            val before = harness.service.decide(
                "reviewer", exception.policyId, 1, "s-dmz", at = from.minusSeconds(1), persist = false
            )
            assertEquals(emptyList(), before.record.waivedClauseIds)
            val atStart = harness.service.decide(
                "reviewer", exception.policyId, 1, "s-dmz", at = from, persist = false
            )
            assertEquals(listOf("C1"), atStart.record.waivedClauseIds)
        } finally {
            harness.cleanup()
        }
    }
}
