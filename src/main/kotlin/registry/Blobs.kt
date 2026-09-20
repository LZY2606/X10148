package registry

data class BlobInfo(val id: String, val exists: Boolean, val size: Long?)

class BlobService(private val service: RegistryService) {
    fun upload(content: ByteArray, actor: String, fileName: String): BlobInfo = service.storage.withLock {
        if (content.isEmpty()) throw ApiError(ErrorKind.VALIDATION, "材料内容为空")
        val id = service.storage.saveBlob(content)
        service.auditLog.append("BLOB_UPLOAD", actor, id, null, true,
            "name=$fileName,bytes=${content.size}", service.clock)
        BlobInfo(id, true, content.size.toLong())
    }

    fun delete(id: String, actor: String): Unit = service.storage.withLock {
        if (!service.storage.blobExists(id))
            throw ApiError(ErrorKind.BLOB_MISSING, "blob 不存在或已删除").also {
                service.auditLog.append("BLOB_DELETE_REJECTED", actor, id, null, false,
                    "BLOB_MISSING:blob 不存在或已删除", service.clock)
            }
        service.storage.deleteBlob(id)
        val referenced = service.exceptions.all().filter { it.evidenceBlobId == id }.map { it.id }
        // 历史记录保留 evidenceSummary，不删除也不改动例外；仅记录引用快照
        service.auditLog.append("BLOB_DELETE", actor, id, null, true,
            "referencesKept=${referenced.size}", service.clock)
    }

    fun info(id: String): BlobInfo {
        val path = service.storage.blobPath(id)
        val exists = service.storage.blobExists(id)
        return BlobInfo(id, exists, if (exists) java.nio.file.Files.size(path) else null)
    }

    fun download(id: String): ByteArray =
        service.storage.readBlob(id) ?: throw ApiError(ErrorKind.BLOB_MISSING, "blob 已删除，历史记录仅保留摘要")
}
