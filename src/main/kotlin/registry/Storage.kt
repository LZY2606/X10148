package registry

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

class StorageException(message: String) : RuntimeException(message)

class FileStorage(val root: Path) {
    val policies = root.resolve("policies")
    val exceptions = root.resolve("exceptions")
    val audit = root.resolve("audit")
    val decisions = root.resolve("decisions")
    val subjects = root.resolve("subjects")
    val blobs = root.resolve("blobs")
    val metaFile = root.resolve("meta.json")

    private val writeLock = ReentrantLock()

    init {
        listOf(policies, exceptions, audit, decisions, subjects, blobs).forEach {
            Files.createDirectories(it)
        }
        if (!metaFile.exists()) {
            writeJson(metaFile, mapOf("instanceId" to UUID.randomUUID().toString(), "nextSeq" to 1L, "nextDecision" to 1L))
        }
    }

    fun <T> withLock(action: () -> T): T {
        writeLock.lock()
        try {
            return action()
        } finally {
            writeLock.unlock()
        }
    }

    fun readMeta(): MutableMap<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return (Json.parse(Files.readString(metaFile, StandardCharsets.UTF_8)) as JsonObject).toMutableMap()
    }

    fun writeMeta(meta: Map<String, Any?>) = writeJson(metaFile, meta)

    fun writeJson(path: Path, value: Any?) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling(".${path.fileName}.tmp-${UUID.randomUUID()}")
        Files.writeString(tmp, Json.write(value), StandardCharsets.UTF_8)
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun readJsonOrNull(path: Path): Any? =
        if (path.exists()) Json.parse(Files.readString(path, StandardCharsets.UTF_8)) else null

    fun listJson(dir: Path): List<Pair<String, JsonObject>> =
        if (dir.exists()) dir.listDirectoryEntries("*.json").map {
            it.fileName.name.removeSuffix(".json") to Json.obj(readJsonOrNull(it))
        }.sortedBy { it.first }
        else emptyList()

    fun deleteFile(path: Path) {
        Files.deleteIfExists(path)
    }

    // ---- blobs ----

    fun saveBlob(content: ByteArray): String {
        val hash = Crypto.sha256Hex(content.toString(StandardCharsets.UTF_8))
        val path = blobs.resolve("$hash.bin")
        if (!path.exists()) {
            val tmp = blobs.resolve(".$hash.tmp-${UUID.randomUUID()}")
            Files.write(tmp, content)
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        return hash
    }

    fun blobPath(id: String): Path {
        require(id.matches(Regex("[0-9a-f]{64}"))) { "bad blob id" }
        return blobs.resolve("$id.bin")
    }

    fun blobExists(id: String): Boolean = blobPath(id).exists() && blobPath(id).isRegularFile()

    fun readBlob(id: String): ByteArray? {
        val p = blobPath(id)
        if (!p.exists()) return null
        return p.inputStream().use { input ->
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            out.toByteArray()
        }
    }

    fun deleteBlob(id: String): Boolean = Files.deleteIfExists(blobPath(id))
}
