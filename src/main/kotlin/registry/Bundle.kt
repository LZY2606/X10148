package registry

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Instance bundle used by export/import:
 *   state.json  - canonical full snapshot
 *   blobs/<id>.bin - live evidence bytes (tombstoned blobs omitted by design)
 *
 * Rebuilding from a bundle reproduces state transitions, decision fingerprints and
 * audit ordering because all timestamps, sequences and identifiers are carried over.
 */
data class Bundle(val snapshot: Snapshot, val blobs: Map<String, ByteArray>)

object BundleZip {
    fun write(bundle: Bundle): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(6)
            zip.putNextEntry(ZipEntry("state.json"))
            zip.write(Json.stringify(FileStore.encode(canonicalize(bundle.snapshot))).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            bundle.blobs.toSortedMap().forEach { (blobId, content) ->
                zip.putNextEntry(ZipEntry("blobs/$blobId.bin"))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun read(content: ByteArray): Bundle {
        var snapshot: Snapshot? = null
        val blobs = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(content)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val bytes = zip.readAllBytes()
                when {
                    entry.name == "state.json" ->
                        snapshot = FileStore.decode(Json.parse(String(bytes, Charsets.UTF_8)) as Map<*, *>)
                    entry.name.startsWith("blobs/") && entry.name.endsWith(".bin") ->
                        blobs[entry.name.removePrefix("blobs/").removeSuffix(".bin")] = bytes
                }
                entry = zip.nextEntry
            }
        }
        return Bundle(snapshot ?: throw ValidationException("导出包缺少 state.json"), blobs)
    }

    /** Deterministic ordering so identical instances produce byte-identical state.json. */
    fun canonicalize(snapshot: Snapshot): Snapshot = snapshot.copy(
        policies = snapshot.policies.sortedWith(compareBy({ it.policyId }, { it.version })),
        subjects = snapshot.subjects.sortedBy { it.subjectId },
        exceptions = snapshot.exceptions.sortedWith(
            compareBy({ it.chainId }, { it.chainVersion }, { it.exceptionId })
        ),
        blobs = snapshot.blobs.sortedBy { it.blobId },
        audit = snapshot.audit.sortedBy { it.seq },
        decisions = snapshot.decisions.sortedBy { it.decisionId }
    )
}
