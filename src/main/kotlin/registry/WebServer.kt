package registry

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant

class WebServer(
    private val service: RegistryService,
    host: String,
    port: Int
) {
    private val pages = Pages(service)
    private val pages2 = Pages2(service)
    private val pages3 = Pages3(service)
    private val pages4 = Pages4(service)
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0)

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(8)
    }

    fun start() {
        server.start()
        println("策略例外登记站 已启动: http://${server.address.hostString.ifBlank { "127.0.0.1" }}:${server.address.port}")
    }

    fun stop() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        try {
            route(exchange)
        } catch (error: RegistryException) {
            respond(exchange, 409, error.message ?: "请求被拒绝", "text/plain; charset=utf-8")
        } catch (error: Exception) {
            error.printStackTrace()
            respond(exchange, 500, "服务器内部错误: ${error.message}", "text/plain; charset=utf-8")
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String, contentType: String = "text/html; charset=utf-8") {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun respondBytes(exchange: HttpExchange, status: Int, bytes: ByteArray, contentType: String, fileName: String? = null) {
        exchange.responseHeaders.set("Content-Type", contentType)
        if (fileName != null) {
            exchange.responseHeaders.set("Content-Disposition", "attachment; filename=\"$fileName\"")
        }
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun redirect(exchange: HttpExchange, location: String) {
        exchange.responseHeaders.set("Location", location)
        exchange.sendResponseHeaders(303, -1)
        exchange.responseBody.close()
    }

    private fun bodyText(exchange: HttpExchange): String {
        val length = exchange.requestHeaders.getFirst("Content-length")?.toIntOrNull() ?: 0
        if (length == 0) return ""
        return String(exchange.requestBody.readNBytes(length), StandardCharsets.UTF_8)
    }

    private fun bodyBytes(exchange: HttpExchange): ByteArray {
        val length = exchange.requestHeaders.getFirst("Content-length")?.toIntOrNull() ?: 0
        return exchange.requestBody.readNBytes(length)
    }

    private fun multipart(exchange: HttpExchange): MultipartForm {
        val contentType = exchange.requestHeaders.getFirst("Content-Type") ?: ""
        return MultipartParser.parse(bodyBytes(exchange), contentType)
    }

    private fun route(exchange: HttpExchange) {
        val uri = exchange.requestURI
        val path = uri.path
        val query = parseQuery(uri)
        val method = exchange.requestMethod
        when {
            method == "GET" && path == "/" -> respond(exchange, 200, pages.dashboard())
            method == "GET" && path == "/policies" -> respond(exchange, 200, pages.policiesPage())
            method == "GET" && path == "/policies/edit" -> {
                val policyId = query["policy"]
                val version = query["version"]?.toIntOrNull()
                respond(exchange, 200, pages.policyEditPage(policyId, version))
            }
            method == "POST" && path == "/policies/save" -> savePolicy(exchange)

            method == "GET" && path == "/exceptions" -> respond(exchange, 200, pages2.exceptionsPage())
            method == "POST" && path == "/exceptions/preview" -> previewScope(exchange)
            method == "POST" && path == "/exceptions/submit" -> submitException(exchange)
            method == "GET" && path.startsWith("/exceptions/") -> exceptionDetail(exchange, path)
            method == "POST" && path.endsWith("/approve") -> approve(exchange, path)
            method == "POST" && path.endsWith("/revoke") -> revoke(exchange, path)
            method == "POST" && path.endsWith("/renew") -> renew(exchange, path)

            method == "GET" && path.startsWith("/blobs/") -> downloadBlob(exchange, path)
            method == "POST" && path.matches(Regex("^/blobs/[^/]+/delete$")) -> deleteBlob(exchange, path)

            method == "GET" && path == "/directory" -> respond(exchange, 200, pages4.directoryPage())
            method == "POST" && path == "/directory/save" -> saveSubject(exchange)
            method == "POST" && path == "/directory/delete" -> deleteSubject(exchange)

            method == "GET" && path == "/decide" -> respond(exchange, 200, pages4.decidePage())
            method == "POST" && path == "/decide" -> decide(exchange)

            method == "GET" && path == "/audit" -> respond(exchange, 200, pages4.auditPage())
            method == "GET" && path == "/clock" -> respond(exchange, 200, pages4.clockPage())
            method == "POST" && path == "/clock/freeze" -> freezeClock(exchange)
            method == "POST" && path == "/clock/advance" -> advanceClock(exchange)
            method == "POST" && path == "/clock/reset" -> resetClock(exchange)

            method == "GET" && path == "/bundle" -> respond(exchange, 200, pages4.bundlePage())
            method == "GET" && path == "/bundle/export" -> exportBundle(exchange)
            method == "POST" && path == "/bundle/import" -> importBundle(exchange)
            else -> respond(exchange, 404, "未找到路径: $path", "text/plain; charset=utf-8")
        }
    }

    private fun parseQuery(uri: URI): Map<String, String> {
        val raw = uri.rawQuery ?: return emptyMap()
        return raw.split("&").filter { it.isNotBlank() }.associate { pair ->
            val eq = pair.indexOf('=')
            val key = if (eq < 0) pair else pair.substring(0, eq)
            val value = if (eq < 0) "" else pair.substring(eq + 1)
            java.net.URLDecoder.decode(key, "UTF-8") to java.net.URLDecoder.decode(value, "UTF-8")
        }
    }

    private fun exceptionIdFromPath(path: String, suffix: String): String {
        val prefix = "/exceptions/"
        val raw = path.removePrefix(prefix).removeSuffix(suffix)
        return java.net.URLDecoder.decode(raw, "UTF-8")
    }

    // ---- Handlers --------------------------------------------------------

    private fun savePolicy(exchange: HttpExchange) {
        val form = parseFormUrlEncoded(bodyText(exchange))
        val repeated = Regex("""name="(cid|ctitle|cseverity|cexpr|crationale)"[^>]*>""")
        try {
            val fields = form.toMultiFieldOrder()
            val drafts = buildClauseDrafts(form)
            val policyId = form["policyId"]?.takeIf { it.isNotBlank() }
            service.editPolicy(
                actor = form.getOr("actor") { "alice" },
                policyId = policyId,
                title = form.getOr("title") { "" },
                clauses = drafts,
                changeNote = form.getOr("changeNote") { "" }
            )
            redirect(exchange, "/policies")
        } catch (error: RegistryException) {
            val prefill = PolicyForm(
                policyIdInput = form["policyIdInput"] ?: form["policyId"] ?: "",
                title = form["title"] ?: "",
                changeNote = form["changeNote"] ?: "",
                actor = form["actor"] ?: "alice",
                clauses = buildClauseDrafts(form, lenient = true)
            )
            respond(exchange, 400, pages.policyEditPage(form["policyId"], null, error.message, prefill))
        }
    }

    private fun buildClauseDrafts(form: Map<String, String>, lenient: Boolean = false): List<ClauseDraft> {
        // URL-encoded repeated keys collapse; the UI uses unique indexed names? It does not,
        // so the page must submit indexed fields. The form fields are cid#index.
        val indices = form.keys.mapNotNull {
            if (it.startsWith("cid")) it.removePrefix("cid").toIntOrNull() else null
        }.sorted()
        if (indices.isEmpty() && lenient) return emptyList()
        return indices.map { index ->
            ClauseDraft(
                id = form["cid$index"] ?: "",
                title = form["ctitle$index"] ?: "",
                severity = form["cseverity$index"] ?: "normal",
                predicateExpr = form["cexpr$index"] ?: "",
                rationale = form["crationale$index"] ?: ""
            )
        }
    }

    private fun previewScope(exchange: HttpExchange) {
        val form = multipart(exchange)
        val expr = form.textOr("subjectExpr", "")
        try {
            val preview = service.previewScope(expr)
            respond(exchange, 200, pages2.exceptionsPage(preview = preview, previewExpr = expr))
        } catch (error: RegistryException) {
            respond(exchange, 400, pages2.exceptionsPage(error = error.message, previewExpr = expr))
        }
    }

    private fun submitException(exchange: HttpExchange) {
        val form = multipart(exchange)
        try {
            val (policyId, version) = splitPolicyRef(form.textOr("policyVersion", ""))
            val clauseIds = form.allText("clause")
            val blobId = form.file("evidence")?.takeIf { it.bytes.isNotEmpty() }?.let { part ->
                service.uploadBlob(form.textOr("actor", "anonymous"), part.fileName ?: "evidence", part.mediaType ?: "", part.bytes).blobId
            }
            val record = service.submitException(
                actor = form.textOr("actor", ""),
                policyId = policyId,
                policyVersion = version,
                subjectExpr = form.textOr("subjectExpr", ""),
                clauseIds = clauseIds,
                validFrom = parseLocalInput(form.textOr("validFrom", "")),
                validUntil = parseLocalInput(form.textOr("validUntil", "")),
                justification = form.textOr("justification", ""),
                evidenceSummary = form.textOr("evidenceSummary", ""),
                blobId = blobId
            )
            redirect(exchange, "/exceptions/${urlEnc(record.exceptionId)}")
        } catch (error: RegistryException) {
            respond(exchange, 400, pages2.exceptionsPage(error = error.message, previewExpr = form.textOr("subjectExpr", "")))
        }
    }

    private fun exceptionDetail(exchange: HttpExchange, path: String) {
        val exceptionId = exceptionIdFromPath(path, "")
        respond(exchange, 200, pages3.exceptionDetailPage(exceptionId))
    }

    private fun approve(exchange: HttpExchange, path: String) {
        val exceptionId = exceptionIdFromPath(path, "/approve")
        val form = parseFormUrlEncoded(bodyText(exchange))
        val expected = form["expected"]?.toIntOrNull() ?: 0
        try {
            service.approve(form.getOr("actor") { "" }, exceptionId, expected)
            redirect(exchange, "/exceptions/${urlEnc(exceptionId)}")
        } catch (error: RegistryException) {
            respond(exchange, 400, pages3.exceptionDetailPage(exceptionId, actor = form["actor"] ?: "", error = error.message))
        }
    }

    private fun revoke(exchange: HttpExchange, path: String) {
        val exceptionId = exceptionIdFromPath(path, "/revoke")
        val form = parseFormUrlEncoded(bodyText(exchange))
        val expected = form["expected"]?.toIntOrNull() ?: 0
        try {
            service.revoke(form.getOr("actor") { "" }, exceptionId, expected, form.getOr("reason") { "" })
            redirect(exchange, "/exceptions/${urlEnc(exceptionId)}")
        } catch (error: RegistryException) {
            respond(exchange, 400, pages3.exceptionDetailPage(exceptionId, error = error.message))
        }
    }

    private fun renew(exchange: HttpExchange, path: String) {
        val exceptionId = exceptionIdFromPath(path, "/renew")
        val form = multipart(exchange)
        val expected = form.textOr("expected", "0").toIntOrNull() ?: 0
        try {
            val blobPart = form.file("evidence")?.takeIf { it.bytes.isNotEmpty() }
            val blobId = blobPart?.let {
                service.uploadBlob(form.textOr("actor", "anonymous"), it.fileName ?: "evidence", it.mediaType ?: "", it.bytes).blobId
            }
            val (_, renewed) = service.renew(
                actor = form.textOr("actor", ""),
                exceptionId = exceptionId,
                expectedStatusRevision = expected,
                validFrom = parseLocalInput(form.textOr("validFrom", "")),
                validUntil = parseLocalInput(form.textOr("validUntil", "")),
                justification = form.textOr("justification", ""),
                evidenceSummary = form.textOr("evidenceSummary", ""),
                blobId = blobId
            )
            redirect(exchange, "/exceptions/${urlEnc(renewed.exceptionId)}")
        } catch (error: RegistryException) {
            respond(exchange, 400, pages3.exceptionDetailPage(exceptionId, error = error.message))
        }
    }

    private fun deleteBlob(exchange: HttpExchange, path: String) {
        val blobId = java.net.URLDecoder.decode(path.removePrefix("/blobs/").removeSuffix("/delete"), "UTF-8")
        val form = parseFormUrlEncoded(bodyText(exchange))
        val exceptionId = form.getOr("exceptionId") { "" }
        try {
            service.deleteBlob(form.getOr("actor") { "web" }, blobId)
            redirect(exchange, "/exceptions/${urlEnc(exceptionId)}")
        } catch (error: RegistryException) {
            respond(exchange, 400, pages3.exceptionDetailPage(exceptionId, error = error.message))
        }
    }

    private fun downloadBlob(exchange: HttpExchange, path: String) {
        val blobId = java.net.URLDecoder.decode(path.removePrefix("/blobs/"), "UTF-8")
        val pair = service.readBlob(blobId)
        if (pair == null) {
            respond(exchange, 404, "blob 不存在", "text/plain; charset=utf-8")
            return
        }
        val (blob, bytes) = pair
        if (blob.deleted || bytes.isEmpty()) {
            respond(exchange, 410, "blob 已删除；历史记录仍保留摘要", "text/plain; charset=utf-8")
            return
        }
        respondBytes(exchange, 200, bytes, blob.mediaType, blob.fileName)
    }

    private fun saveSubject(exchange: HttpExchange) {
        val form = parseFormUrlEncoded(bodyText(exchange))
        try {
            service.upsertSubject(
                actor = "web",
                subjectId = form.getOr("subjectId") { "" },
                displayName = form.getOr("displayName") { "" },
                attributes = parseAttributes(form.getOr("attributes") { "" })
            )
            redirect(exchange, "/directory")
        } catch (error: RegistryException) {
            respond(exchange, 400, pages4.directoryPage(error.message, form["subjectId"] ?: ""))
        }
    }

    private fun deleteSubject(exchange: HttpExchange) {
        val form = parseFormUrlEncoded(bodyText(exchange))
        service.deleteSubject("web", form.getOr("subjectId") { "" })
        redirect(exchange, "/directory")
    }

    private fun decide(exchange: HttpExchange) {
        val form = parseFormUrlEncoded(bodyText(exchange))
        val prefill = DecisionForm(
            actor = form["actor"] ?: "",
            subjectId = form["subjectId"] ?: "",
            facts = form["facts"] ?: "",
            policyVersion = form["policyVersion"] ?: ""
        )
        try {
            val (policyId, version) = splitPolicyRef(form.getOr("policyVersion") { "" })
            val result = service.decide(
                actor = form.getOr("actor") { "" },
                policyId = policyId,
                policyVersion = version,
                subjectId = form.getOr("subjectId") { "" },
                extraFacts = parseAttributes(form.getOr("facts") { "" })
            )
            respond(exchange, 200, pages4.decidePage(result = result, prefill = prefill))
        } catch (error: RegistryException) {
            respond(exchange, 400, pages4.decidePage(error = error.message, prefill = prefill))
        }
    }

    private fun freezeClock(exchange: HttpExchange) {
        val form = parseFormUrlEncoded(bodyText(exchange))
        try {
            val at = service.freezeClock(form.getOr("actor") { "reviewer" }, parseLocalInput(form.getOr("at") { "" }))
            respond(exchange, 200, pages4.clockPage("时钟已冻结到 ${formatInstant(at)}"))
        } catch (error: RegistryException) {
            respond(exchange, 400, pages4.clockPage(error.message, error = true))
        }
    }

    private fun advanceClock(exchange: HttpExchange) {
        val form = parseFormUrlEncoded(bodyText(exchange))
        try {
            val seconds = form["seconds"]?.toLongOrNull() ?: 0L
            val at = service.advanceClock(form.getOr("actor2") { "reviewer" }, seconds)
            respond(exchange, 200, pages4.clockPage("时钟已推进到 ${formatInstant(at)}，到期状态已重算"))
        } catch (error: RegistryException) {
            respond(exchange, 400, pages4.clockPage(error.message, error = true))
        }
    }

    private fun resetClock(exchange: HttpExchange) {
        val form = parseFormUrlEncoded(bodyText(exchange))
        val at = service.resetClock(form.getOr("actor") { "reviewer" })
        respond(exchange, 200, pages4.clockPage("已恢复真实 UTC 时钟：${formatInstant(at)}"))
    }

    private fun exportBundle(exchange: HttpExchange) {
        respondBytes(exchange, 200, service.exportBundle(), "application/zip", "instance.zip")
    }

    private fun importBundle(exchange: HttpExchange) {
        val form = multipart(exchange)
        val part = form.file("bundle")
        try {
            if (part == null || part.bytes.isEmpty()) throw ValidationException("请选择 instance.zip")
            val snapshot = service.importBundle(form.textOr("actor", "admin"), part.bytes)
            respond(exchange, 200, pages4.bundlePage("实例已重建：${snapshot.policies.size} 个策略版本、${snapshot.exceptions.size} 条例外、${snapshot.audit.size} 条审计事件"))
        } catch (error: RegistryException) {
            respond(exchange, 400, pages4.bundlePage("导入失败：${error.message}", error = true))
        }
    }

    // ---- Helpers ---------------------------------------------------------

    private fun splitPolicyRef(value: String): Pair<String, Int?> {
        if ("@" !in value) throw ValidationException("请选择策略版本")
        val (policyId, rawVersion) = value.split("@", limit = 2)
        return policyId to rawVersion.toIntOrNull()
    }

    private fun parseAttributes(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) throw ValidationException("事实行必须是 key=value：$line")
            result[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
        }
        return result
    }

    private fun Map<String, String>.getOr(key: String, default: () -> String): String = this[key] ?: default()

    @Suppress("unused")
    private fun Map<String, String>.toMultiFieldOrder(): Map<String, String> = this
}
