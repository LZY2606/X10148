package registry

import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageClockBundleTest {

    // ---- blob 删除后历史仍保留摘要 ----------------------------------------

    @Test
    fun `deleting a blob removes bytes but keeps evidence summary in history`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            val content = "签字版风险接受书".toByteArray()
            val blob = harness.service.uploadBlob("carol", "acceptance.txt", "text/plain", content)
            val exception = harness.service.submitException(
                actor = "carol",
                policyId = harness.policy.policyId,
                policyVersion = 1,
                subjectExpr = "zone = 'dmz'",
                clauseIds = listOf("C1"),
                validFrom = harness.base,
                validUntil = harness.base.plus(48, ChronoUnit.HOURS),
                justification = "J",
                evidenceSummary = "工单 T-9，签字版见 blob",
                blobId = blob.blobId
            )

            val loaded = harness.service.readBlob(blob.blobId)!!
            assertEquals(content.toList(), loaded.second.toList())

            val deleted = harness.service.deleteBlob("admin", blob.blobId)
            assertTrue(deleted.deleted)
            assertNotNull(deleted.deletedAt)

            // The bytes are gone...
            val after = harness.service.readBlob(blob.blobId)!!
            assertEquals(0, after.second.size)
            assertTrue(after.first.deleted)
            // ...but the exception history keeps the summary.
            val stored = harness.service.exception(exception.exceptionId)!!
            assertEquals("工单 T-9，签字版见 blob", stored.evidenceSummary)
        } finally {
            harness.cleanup()
        }
    }

    // ---- 时钟推进触发到期状态转换 ----------------------------------------

    @Test
    fun `advancing the clock expires active exceptions and persists the transition`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            val exception = harness.submittedException(
                from = harness.base,
                until = harness.base.plus(1, ChronoUnit.HOURS),
                clauses = listOf("C1")
            )
            harness.activate(exception)

            val auditSize = harness.service.snapshot().audit.size
            harness.service.advanceClock("reviewer", 3600)

            val stored = harness.service.exception(exception.exceptionId)!!
            assertEquals(ExceptionStatus.EXPIRED, stored.status)
            val expireEvent = harness.service.snapshot().audit.first { it.action == "exception.expire" }
            assertEquals(exception.exceptionId, expireEvent.target)

            // Reload from disk: status transition is durable.
            val rebuiltService = RegistryService(harness.store, ServiceClock(), harness.blobPath)
            val reloaded = rebuiltService.exception(exception.exceptionId)!!
            assertEquals(ExceptionStatus.EXPIRED, reloaded.status)
            assertTrue(harness.service.snapshot().audit.size > auditSize)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `clock only moves forward`() {
        val harness = TestHarness()
        try {
            val error = assertThrowsRegistry { harness.service.advanceClock("x", -10) }
            assertTrue(error.message!!.contains("向未来推进"))
        } finally {
            harness.cleanup()
        }
    }

    // ---- 交集过大时展示稳定样例 ------------------------------------------

    @Test
    fun `broad scope reports a warning and deterministic stable samples`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            // 7 s-lab-* + s-dmz has zone dmz, s-internal internal; expr zone ~ 'lab' matches 7.
            val preview = harness.service.previewScope("zone ~ 'lab'")
            assertEquals(7, preview.matchedCount)
            assertTrue(preview.warning)
            assertEquals(RegistryService.SAMPLE_LIMIT, preview.samples.size)
            assertEquals(preview.samples, preview.samples.sorted())
            val again = harness.service.previewScope("zone ~ 'lab'")
            assertEquals(preview.samples, again.samples)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `narrow scope shows no warning`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            val preview = harness.service.previewScope("zone = 'dmz'")
            assertEquals(1, preview.matchedCount)
            assertEquals(listOf("s-dmz"), preview.samples)
            assertTrue(!preview.warning)
        } finally {
            harness.cleanup()
        }
    }

    // ---- 导出重建：状态、指纹、审计顺序一致 -------------------------------

    @Test
    fun `exported bundle rebuilds an identical instance`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            val exception = harness.submittedException(expr = "zone = 'dmz'", clauses = listOf("C1", "C2"))
            harness.activate(exception)
            val decision1 = harness.service.decide("reviewer", exception.policyId, 1, "s-dmz")
            harness.service.advanceClock("reviewer", 49 * 3600L)
            val decision2 = harness.service.decide(
                "reviewer", exception.policyId, 1, "s-dmz", at = harness.base.plus(50, ChronoUnit.HOURS), persist = true
            )
            assertEquals(DecisionVerdict.DENY, decision2.verdict)

            val exported = harness.service.exportBundle()

            // Rebuild into a brand-new instance directory.
            val root2 = java.nio.file.Files.createTempDirectory("registry-rebuild")
            try {
                val store2 = FileStore(root2.resolve("data/state.json"))
                val blobPath2 = root2.resolve("blobs")
                val clock2 = ServiceClock()
                val rebuilt = RegistryService(store2, clock2, blobPath2)
                rebuilt.importBundle("admin", exported)

                val original = harness.service.snapshot()
                val copy = rebuilt.snapshot()

                assertEquals(original.policies, copy.policies)
                assertEquals(original.exceptions.map { it.exceptionId to it.status },
                    copy.exceptions.map { it.exceptionId to it.status })
                assertEquals(original.audit.map { it.seq }, copy.audit.map { it.seq })
                // Audit order is strictly preserved.
                assertEquals(copy.audit.sortedBy { it.seq }, copy.audit)

                // Decision fingerprints reproduce for identical canonical inputs.
                val reproduced = rebuilt.decide(
                    "reviewer", exception.policyId, 1, "s-dmz",
                    at = decision1.at, persist = false
                )
                assertEquals(decision1.record.fingerprint, reproduced.record.fingerprint)
                assertEquals(decision1.verdict, reproduced.verdict)

                // Simulated clock settings are restored.
                assertTrue(clock2.isSimulating())
                assertEquals(harness.clock.simulatedAt(), clock2.simulatedAt())

                // A second export produces identical state.json content.
                val export2 = rebuilt.exportBundle()
                assertEquals(stateJson(exported), stateJson(export2))
            } finally {
                root2.toFile().deleteRecursively()
            }
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `export rebuild preserves blob bytes and keeps tombstones summarized`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            val live = harness.service.uploadBlob("carol", "live.bin", "application/octet-stream", "AAAA".toByteArray())
            val gone = harness.service.uploadBlob("carol", "gone.bin", "application/octet-stream", "BBBB".toByteArray())
            harness.service.deleteBlob("admin", gone.blobId)

            val exported = harness.service.exportBundle()
            val root2 = java.nio.file.Files.createTempDirectory("registry-rebuild-blob")
            try {
                val rebuilt = RegistryService(
                    FileStore(root2.resolve("data/state.json")), ServiceClock(), root2.resolve("blobs")
                )
                rebuilt.importBundle("admin", exported)
                assertEquals("AAAA".toByteArray().toList(), rebuilt.readBlob(live.blobId)!!.second.toList())
                val deleted = rebuilt.readBlob(gone.blobId)!!
                assertTrue(deleted.first.deleted)
                assertEquals(0, deleted.second.size)
            } finally {
                root2.toFile().deleteRecursively()
            }
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `audit seq is gapless and monotonic across successful and failed operations`() {
        val harness = TestHarness()
        try {
            harness.seedSubjects()
            assertThrowsRegistry { harness.submittedException(clauses = emptyList()) }
            harness.submittedException()
            val seq = harness.service.snapshot().audit.map { it.seq }
            assertEquals(seq.sorted(), seq)
            assertEquals(seq.toSet().size, seq.size)
            assertEquals((1L..seq.size.toLong()).toList(), seq)
        } finally {
            harness.cleanup()
        }
    }

    private fun stateJson(bundleBytes: ByteArray): String {
        val bundle = BundleZip.read(bundleBytes)
        return Json.stringify(FileStore.encode(BundleZip.canonicalize(bundle.snapshot)))
    }
}
