package registry

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Local blob storage. A deleted blob leaves only a tombstone in state.json (summary survives). */
object BlobStore {
    fun write(directory: Path, blobId: String, content: ByteArray) {
        Files.createDirectories(directory)
        val target = directory.resolve("$blobId.bin")
        val tmp = directory.resolve("$blobId.tmp")
        Files.write(tmp, content)
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun read(directory: Path, blobId: String): ByteArray {
        val target = directory.resolve("$blobId.bin")
        if (!Files.exists(target)) throw NotFoundException("blob 内容已不存在: $blobId")
        return Files.readAllBytes(target)
    }

    fun delete(directory: Path, blobId: String) {
        Files.deleteIfExists(directory.resolve("$blobId.bin"))
    }

    fun exists(directory: Path, blobId: String): Boolean =
        Files.exists(directory.resolve("$blobId.bin"))
}
