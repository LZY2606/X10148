package registry

import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class RevokeHistoryInstantTest {

    @Test
    fun `revoked exception does not apply after revocation but applies at a past evaluation instant`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            val exception = harness.submittedException(
                expr = "zone = 'dmz'",
                clauses = listOf("C1", "C2"),
                from = harness.base,
                until = harness.base.plus(72, ChronoUnit.HOURS)
            )
            val active = harness.activate(exception)

            val whileActive = harness.base.plusSeconds(100)
            val allowedThen = harness.service.decide(
                "reviewer", active.policyId, 1, "s-dmz", at = whileActive, persist = false
            )
            assertEquals(DecisionVerdict.ALLOW, allowedThen.verdict)

            val revokedAt = harness.base.plus(10, ChronoUnit.MINUTES)
            harness.clock.freezeAt(revokedAt)
            harness.service.revoke("sec", active.exceptionId, active.statusRevision, "撤销")

            val afterRevoke = harness.service.decide(
                "reviewer", active.policyId, 1, "s-dmz", at = revokedAt.plusSeconds(1), persist = false
            )
            assertEquals(DecisionVerdict.DENY, afterRevoke.verdict)
            assertEquals(emptyList(), afterRevoke.record.waivedClauseIds)

            // Replaying the past instant must reproduce the original explanation.
            val replay = harness.service.decide(
                "reviewer", active.policyId, 1, "s-dmz", at = whileActive, persist = false
            )
            assertEquals(DecisionVerdict.ALLOW, replay.verdict)
            assertEquals(allowedThen.record.fingerprint, replay.record.fingerprint)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `pending or superseded exceptions never grant waivers`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            val exception = harness.submittedException(clauses = listOf("C1", "C2"))
            val pending = harness.service.decide(
                "reviewer", exception.policyId, 1, "s-dmz", at = harness.base.plusSeconds(10), persist = false
            )
            assertEquals(DecisionVerdict.DENY, pending.verdict)

            val active = harness.activate(exception)
            harness.service.renew(
                "carol", active.exceptionId, active.statusRevision,
                harness.base.plusSeconds(20), harness.base.plusSeconds(3600),
                "", "T-2", null
            )
            // Old (now superseded) no longer applies even inside its old interval.
            val replay = harness.service.decide(
                "reviewer", active.policyId, 1, "s-dmz", at = harness.base.plusSeconds(10), persist = false
            )
            assertEquals(DecisionVerdict.DENY, replay.verdict)
        } finally {
            harness.cleanup()
        }
    }
}
