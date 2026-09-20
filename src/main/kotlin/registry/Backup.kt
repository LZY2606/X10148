package registry

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

object Backup {
    fun exportZip(service: RegistryService): ByteArray = service.storage.withLock {
        val baos = java.io.ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            val root = service.storage.root
            val allowedDirs = setOf("policies", "exceptions", "audit", "decisions", "subjects", "blobs")
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { path ->
                    val relative = root.relativize(path).toString().replace(java.io.File.separatorChar, '/')
                    val top = relative.substringBefore('/')
                    if (top !in allowedDirs && relative != "meta.json") return@forEach
                    if (relative.startsWith(".") || relative.contains("/.")) return@forEach
                    zip.putNextEntry(ZipEntry(relative))
                    path.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        service.auditLog.append("EXPORT", "system", null, null, true, null, service.clock)
        baos.toByteArray()
    }

    fun importInto(targetRoot: Path, zipBytes: ByteArray, clock: Clock): RegistryService {
        Files.createDirectories(targetRoot)
        require(Files.list(targetRoot).use { it.findAny().isEmpty }) { "目标目录必须为空才能重建" }
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = entry.name
                if (name.contains("..")) throw IllegalStateException("非法条目: $name")
                val out = targetRoot.resolve(name)
                Files.createDirectories(out.parent)
                out.outputStream().use { input.copyTo(it) }
                input.closeEntry()
            }
        }
        return RegistryService(FileStorage(targetRoot), clock, seed = false)
    }
}
