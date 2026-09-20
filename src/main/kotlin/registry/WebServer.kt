package registry

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors

class WebServer(
    private val service: RegistryService,
    private val blobs: BlobService,
    host: String,
    port: Int,
) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0)

    init {
        server.executor = Executors.newFixedThreadPool(8)
        server.createContext("/") { exchange ->
            try {
                route(exchange)
            } catch (e: ApiError) {
                val status = when (e.kind) {
                    ErrorKind.NOT_FOUND, ErrorKind.BLOB_MISSING -> 404
                    ErrorKind.SELF_REVIEW -> 403
                    ErrorKind.CONFLICT, ErrorKind.DUPLICATE_CONFIRMATION,
                    ErrorKind.INTERVAL_CLOSED, ErrorKind.CHAIN_CONFLICT -> 409
                    ErrorKind.WRONG_STATE -> 422
                    ErrorKind.VALIDATION -> 400
                }
                writeJson(exchange, status, mapOf("error" to e.kind.name, "message" to (e.message ?: "")))
            } catch (e: Exception) {
                writeJson(exchange, 500, mapOf("error" to "INTERNAL", "message" to (e.message ?: "")))
            } finally {
                exchange.close()
            }
        }
    }

    fun start() {
        server.start()
    }

    fun stop() {
        server.stop(0)
    }

    private fun route(exchange: HttpExchange) {
        val method = exchange.requestMethod
        val path = exchange.requestURI.path.trimEnd('/').ifEmpty { "/" }
        if (method == "GET" && (path == "/" || path == "/index.html")) {
            return staticResource(exchange, "/web/index.html", "text/html; charset=utf-8")
        }
        if (method == "GET" && path == "/app.js") {
            return staticResource(exchange, "/web/app.js", "application/javascript; charset=utf-8")
        }
        if (method == "GET" && path == "/api/health") {
            return writeJson(exchange, 200, mapOf(
                "ok" to true,
                "serverTimeUtc" to Instants.render(service.clock.now()),
                "dataDir" to service.storage.root.toAbsolutePath().toString(),
            ))
        }
        if (method == "GET" && path == "/api/policies") {
            return writeJson(exchange, 200, mapOf("policies" to service.policies.all()
                .sortedWith(compareBy({ it.id }, { it.version }))
                .map { it.toJson() }))
        }
        if (method == "POST" && path == "/api/policies") {
            val body = readBody(exchange)
            val j = Json.obj(Json.parse(body))
            val actor = (j["actor"] as? String) ?: "admin"
            val basedOn = (j["basedOnVersion"] as? Number)?.let {
                service.policies.find(j["id"] as String, it.toInt())
            }
            val policy = service.createPolicy(
                name = j["name"] as String,
                description = (j["description"] as? String) ?: "",
                clauses = Json.arr(j["clauses"]).map { Clause.fromJson(Json.obj(it)) },
                actor = actor,
                basedOn = basedOn,
            )
            return writeJson(exchange, 201, policy.toJson())
        }
        if (method == "GET" && path.startsWith("/api/policies/")) {
            val parts = path.removePrefix("/api/policies/").split("/")
            val id = parts[0]
            if (parts.size == 1) {
                return writeJson(exchange, 200, mapOf(
                    "versions" to service.policies.versions(id).map { it.toJson() }))
            }
        }
        if (method == "GET" && path == "/api/exceptions") {
            return writeJson(exchange, 200, mapOf(
                "exceptions" to service.exceptions.all()
                    .sortedWith(compareBy({ it.chainId }, { it.versionNo }))
                    .map { renderException(it) }))
        }
        if (method == "POST" && path == "/api/exceptions") {
            val j = Json.obj(Json.parse(readBody(exchange)))
            val record = service.submitException(
                RegistryService.SubmitRequest(
                    policyId = j["policyId"] as String,
                    policyVersion = (j["policyVersion"] as? Number)?.toInt(),
                    subjectExpr = j["subjectExpr"] as String,
                    relaxedClauseIds = Json.arr(j["relaxedClauseIds"]).map { it as String },
                    startAt = Instants.parse(j["startAt"] as String),
                    endAt = Instants.parse(j["endAt"] as String),
                    evidenceSummary = (j["evidenceSummary"] as? String) ?: "",
                    evidenceBlobId = j["evidenceBlobId"] as String?,
                    submitter = j["submitter"] as String,
                )
            )
            return writeJson(exchange, 201, renderException(record))
        }
        val excMatch = Regex("^/api/exceptions/([A-Za-z0-9-]+)/(approve|revoke|renew)$").matchEntire(path)
        if (excMatch != null && method == "POST") {
            val (id, action) = excMatch.destructured
            val j = Json.obj(Json.parse(readBody(exchange)))
            val expectedRev = (j["expectedRev"] as Number).toLong()
            val updated = when (action) {
                "approve" -> service.confirmException(id, j["reviewer"] as String, expectedRev)
                "revoke" -> service.revokeException(id, j["reviewer"] as String,
                    (j["reason"] as? String) ?: "", expectedRev)
                "renew" -> service.renewException(
                    RegistryService.RenewRequest(
                        exceptionId = id,
                        actor = j["actor"] as String,
                        startAt = Instants.parse(j["startAt"] as String),
                        endAt = Instants.parse(j["endAt"] as String),
                        subjectExpr = j["subjectExpr"] as String?,
                        relaxedClauseIds = j["relaxedClauseIds"]?.let { v -> Json.arr(v).map { it as String } },
                        evidenceSummary = j["evidenceSummary"] as String?,
                        evidenceBlobId = j["evidenceBlobId"] as String?,
                        expectedRev = expectedRev,
                    )
                )
                else -> throw ApiError(ErrorKind.NOT_FOUND, "未知操作")
            }
            return writeJson(exchange, 200, renderException(updated))
        }
        if (method == "POST" && path == "/api/decisions") {
            val j = Json.obj(Json.parse(readBody(exchange)))
            val at = (j["at"] as? String)?.let { Instants.parse(it) } ?: service.clock.now()
            val persist = (j["persist"] as? Boolean) ?: true
            val fact = Json.obj(j["fact"])
            val explanation = DecisionEngine.evaluate(
                service, fact, at,
                policyVersionOverride = (j["policyVersion"] as? Number)?.toInt(),
                persist = persist,
            )
            return writeJson(exchange, 200, renderExplanation(explanation))
        }
        if (method == "GET" && path == "/api/decisions") {
            return writeJson(exchange, 200, mapOf(
                "decisions" to service.decisions.all().map { it.toJson() }))
        }
        if (method == "GET" && path == "/api/audit") {
            return writeJson(exchange, 200, mapOf(
                "events" to service.auditLog.events().map { it.toJson() },
                "verify" to service.auditLog.verifyChain(),
            ))
        }
        if (method == "GET" && path == "/api/subjects") {
            return writeJson(exchange, 200, mapOf("subjects" to service.subjects.all().map { it.toJson() }))
        }
        if (method == "POST" && path == "/api/subjects") {
            val j = Json.obj(Json.parse(readBody(exchange)))
            val entry = SubjectEntry(j["id"] as String, Json.obj(j["attrs"]))
            service.upsertSubject(entry, (j["actor"] as? String) ?: "admin")
            return writeJson(exchange, 201, entry.toJson())
        }
        if (method == "POST" && path == "/api/scope-preview") {
            val j = Json.obj(Json.parse(readBody(exchange)))
            val result = ScopeAnalyzer.analyze(j["subjectExpr"] as String, service.subjects.all())
            return writeJson(exchange, 200, result.snapshot().toJson())
        }
        if (method == "POST" && path == "/api/blobs") {
            val j = Json.obj(Json.parse(readBody(exchange)))
            val contentBytes = java.util.Base64.getDecoder().decode(j["contentBase64"] as String)
            val info = blobs.upload(contentBytes, (j["actor"] as? String) ?: "admin",
                (j["fileName"] as? String) ?: "blob")
            return writeJson(exchange, 201, mapOf("id" to info.id, "exists" to info.exists, "size" to info.size))
        }
        val blobMatch = Regex("^/api/blobs/([0-9a-f]{64})$").matchEntire(path)
        if (blobMatch != null) {
            val id = blobMatch.groupValues[1]
            if (method == "GET") {
                val data = blobs.download(id)
                exchange.responseHeaders.set("Content-Type", "application/octet-stream")
                exchange.sendResponseHeaders(200, data.size.toLong())
                exchange.responseBody.use { it.write(data) }
                return
            }
            if (method == "DELETE") {
                blobs.delete(id, exchange.requestHeaders.getFirst("X-Actor") ?: "admin")
                return writeJson(exchange, 200, mapOf("deleted" to id))
            }
        }
        if (method == "GET" && path == "/api/export") {
            val data = Backup.exportZip(service)
            exchange.responseHeaders.set("Content-Type", "application/zip")
            exchange.responseHeaders.set("Content-Disposition", "attachment; filename=\"registry-export.zip\"")
            exchange.sendResponseHeaders(200, data.size.toLong())
            exchange.responseBody.use { it.write(data) }
            return
        }
        writeJson(exchange, 404, mapOf("error" to "NOT_FOUND", "message" to path))
    }

    private fun renderException(record: ExceptionRecord): JsonObject {
        val base = record.toJson().toMutableMap()
        base["status"] = record.statusAt(service.clock.now()).name.lowercase()
        val blob = record.evidenceBlobId
        base["evidenceBlobPresent"] = if (blob == null) null else service.storage.blobExists(blob)
        return base
    }

    private fun renderExplanation(ex: DecisionExplanation): JsonObject {
        return mapOf(
            "policyId" to ex.policyId,
            "policyVersion" to ex.policyVersion,
            "at" to Instants.render(ex.at),
            "conclusion" to ex.conclusion,
            "persisted" to ex.persisted,
            "fingerprint" to ex.fingerprint,
            "decisionId" to ex.decision?.id,
            "normalHits" to ex.hits.map { mapOf("clauseId" to it.clauseId, "title" to it.title, "predicate" to it.predicate) },
            "exceptionReliefs" to ex.reliefs.map {
                mapOf(
                    "clauseId" to it.clauseId,
                    "exceptionId" to it.exceptionId,
                    "chainId" to it.chainId,
                    "versionNo" to it.versionNo,
                    "subjectExpr" to it.subjectExpr,
                    "evidenceSummary" to it.reason,
                )
            },
            "ignoredExceptions" to ex.ignoredExceptions.map {
                mapOf("exceptionId" to it.exceptionId, "chainId" to it.chainId,
                    "versionNo" to it.versionNo, "status" to it.status, "reason" to it.reason)
            },
            "remainingViolations" to ex.remaining,
            "explanation" to buildExplanationText(ex),
        )
    }

    private fun buildExplanationText(ex: DecisionExplanation): String {
        val parts = ArrayList<String>()
        if (ex.hits.isEmpty()) parts.add("事实未命中任何条款，直接通过。")
        else {
            parts.add("正常命中条款: ${ex.hits.joinToString(", ") { it.clauseId }}")
            if (ex.reliefs.isNotEmpty())
                parts.add("例外放行条款: ${ex.reliefs.joinToString(", ") { "${it.clauseId}<-${it.exceptionId}@v${it.versionNo}" }}")
            if (ex.ignoredExceptions.isNotEmpty())
                parts.add("未采纳例外: ${ex.ignoredExceptions.joinToString(", ") { "${it.exceptionId}(${it.reason})" }}")
            parts.add("仍然违规: ${if (ex.remaining.isEmpty()) "无" else ex.remaining.joinToString(", ")}")
        }
        parts.add("结论: ${conclusionText(ex.conclusion)}")
        return parts.joinToString("\n")
    }

    private fun conclusionText(c: String) = when (c) {
        "CLEAN" -> "通过（无命中）"
        "ALLOWED_BY_EXCEPTION" -> "通过（命中均由有效例外条款级放行）"
        "DENIED" -> "拒绝（存在未被例外覆盖的条款命中）"
        else -> c
    }

    private fun staticResource(exchange: HttpExchange, resource: String, contentType: String) {
        val bytes = WebServer::class.java.getResourceAsStream(resource)
            ?.use { it.readAllBytes() }
            ?: throw ApiError(ErrorKind.NOT_FOUND, resource)
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun readBody(exchange: HttpExchange): String =
        exchange.requestBody.use { it.readBytes() }.toString(StandardCharsets.UTF_8)

    private fun writeJson(exchange: HttpExchange, status: Int, value: Any?) {
        val bytes = Json.write(value).toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}

fun buildServer(dataDir: Path, host: String, port: Int, clock: Clock = SystemClock): Pair<WebServer, RegistryService> {
    val storage = FileStorage(dataDir)
    val service = RegistryService(storage, clock)
    val blobs = BlobService(service)
    val webServer = WebServer(service, blobs, host, port)
    return webServer to service
}
