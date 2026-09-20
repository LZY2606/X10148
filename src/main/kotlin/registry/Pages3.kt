package registry

class Pages3(private val service: RegistryService) {
    private fun statusBadge(status: ExceptionStatus) =
        """<span class="badge b-$status">$status</span>"""

    fun layout3(active: String, title: String, block: StringBuilder.() -> Unit): String {
        val layout = Layout(title, active)
        layout.append(buildString(block))
        return layout.render(service.clock)
    }

    fun exceptionDetailPage(
        exceptionId: String,
        actor: String = "",
        error: String? = null
    ): String {
        val record = service.exception(exceptionId)
            ?: return layout3("/exceptions", "未找到") {
                append("<div class=\"panel\"><p>例外不存在：${e(exceptionId)}</p><a class=\"btn\" href=\"/exceptions\">返回列表</a></div>")
            }
        val now = service.clock.now()
        val effective = service.effectiveStatus(record, now)
        val chain = service.chain(record.chainId)
        return layout3("/exceptions", "例外详情") {
            append("<div class=\"panel\"><h2>例外 ${e(record.exceptionId)}</h2>")
            if (error != null) append("<div class=\"flash flash-err\">${e(error)}</div>")
            append("""<table>
              <tr><th>链</th><td class="mono">${e(record.chainId)} · 链版本 v${record.chainVersion}</td></tr>
              <tr><th>策略版本</th><td class="mono">${e(record.policyId)} / v${record.policyVersion}</td></tr>
              <tr><th>当前状态</th><td>${statusBadge(effective)}（存储状态 ${record.status}，状态版本号 ${record.statusRevision}）</td></tr>
              <tr><th>放宽条款</th><td><div class="chips">${record.clauseIds.joinToString("") { item -> "<span>" + e(item) + "</span>" }}</div></td></tr>
              <tr><th>对象表达式</th><td><code>${e(record.subjectExpr)}</code></td></tr>
              <tr><th>提交时间 / 提交者</th><td class="mono">${e(formatInstant(record.submittedAt))} · ${e(record.submittedBy)}</td></tr>
              <tr><th>生效区间</th><td class="mono">${e(formatInstant(record.validFrom))} → ${e(formatInstant(record.validUntil))}（结束瞬间已失效）</td></tr>
              <tr><th>偏离理由</th><td>${e(record.justification)}</td></tr>
              <tr><th>证明材料摘要</th><td>${e(record.evidenceSummary)}</td></tr>
            </table></div>""")

            append("<div class=\"panel\"><h2>受影响范围（提交时固化）</h2>")
            append("<p>命中对象 ${record.scopeMatchedCount} 个；稳定样例：</p><div class=\"chips\">")
            append(record.scopeSamples.joinToString("") { item -> "<span>" + e(item) + "</span>" })
            append("</div>")
            if (record.scopeWarning) append("<div class=\"warning-box\">该表达式交集较大，审批时请重点确认最小化范围。</div>")
            append("</div>")

            append("<div class=\"panel\"><h2>证明材料 blob</h2>")
            if (record.blobId == null) {
                append("<p class=\"muted\">未关联文件，仅有摘要。</p>")
            } else {
                val blob = service.snapshot().blobs.firstOrNull { it.blobId == record.blobId }
                if (blob == null) {
                    append("<p class=\"muted\">blob 元数据缺失。</p>")
                } else if (blob.deleted) {
                    append("<div class=\"warning-box\">blob ${e(blob.blobId)} 已于 ${e(formatInstant(blob.deletedAt))} 删除；历史记录仍保留上方摘要与哈希 ${e(blob.sha256)}。</div>")
                } else {
                    append("""<table><tr><th>文件</th><th>类型</th><th>大小</th><th>SHA-256</th><th>上传时间</th><th></th></tr><tr>
                      <td>${e(blob.fileName)}</td><td>${e(blob.mediaType)}</td><td>${blob.sizeBytes}</td>
                      <td class="mono">${e(blob.sha256)}</td><td class="mono">${e(formatInstant(blob.uploadedAt))}</td>
                      <td><a class="btn btn-small btn-secondary" href="/blobs/${urlEnc(blob.blobId)}">下载</a>
                        <form class="inline-form" method="post" action="/blobs/${urlEnc(blob.blobId)}/delete">
                          <input type="hidden" name="exceptionId" value="${e(record.exceptionId)}">
                          <button class="btn-small btn-danger" type="submit">删除 blob（保留摘要）</button>
                        </form></td></tr></table>""")
                }
            }
            append("</div>")

            append("<div class=\"panel\"><h2>双审核激活</h2>")
            append("<p>需要两名与提交者不同的审核者依次确认；同一人重复确认不增加计数。</p>")
            if (record.approvals.isNotEmpty()) {
                append("<ol>")
                record.approvals.forEach { append("<li>${e(it.reviewer)} · <span class=\"mono\">${e(formatInstant(it.at))}</span></li>") }
                append("</ol>")
            } else append("<p class=\"muted\">尚无确认。</p>")
            if (effective == ExceptionStatus.PENDING) {
                append("""<form method="post" action="/exceptions/${urlEnc(record.exceptionId)}/approve">
                  <input type="hidden" name="expected" value="${record.statusRevision}">
                  <label>审核者</label><input type="text" name="actor" value="${e(actor)}" placeholder="不能是提交者 ${e(record.submittedBy)}">
                  <p><button type="submit">确认（${record.approvals.size}/${RegistryService.REQUIRED_APPROVALS}）</button></p></form>""")
            }
            append("</div>")

            if (effective == ExceptionStatus.PENDING || effective == ExceptionStatus.ACTIVE) {
                append("""<div class="panel"><h2>撤销</h2>
                  <form method="post" action="/exceptions/${urlEnc(record.exceptionId)}/revoke">
                  <input type="hidden" name="expected" value="${record.statusRevision}">
                  <label>撤销者</label><input type="text" name="actor">
                  <label>撤销原因</label><textarea name="reason"></textarea>
                  <p><button class="btn-danger" type="submit">撤销例外</button></p></form>
                  <p class="muted">撤销不改变过去的决策记录。</p></div>""")
            }

            if (effective == ExceptionStatus.ACTIVE) {
                append("""<div class="panel"><h2>续期（创建新版本）</h2>
                  <form method="post" action="/exceptions/${urlEnc(record.exceptionId)}/renew" enctype="multipart/form-data">
                  <input type="hidden" name="expected" value="${record.statusRevision}">
                  <p class="muted">续期不能原地延长：旧版本标记为 superseded，新版本进入 pending 并指向旧版本。</p>
                  <div class="row"><div><label>续期者</label><input type="text" name="actor"></div>
                  <div><label>新生效开始 (UTC)</label><input type="datetime-local" name="validFrom" value="${e(toLocalInput(now))}"></div>
                  <div><label>新生效结束 (UTC)</label><input type="datetime-local" name="validUntil" value="${e(toLocalInput(now.plusSeconds(72 * 3600L)))}"></div></div>
                  <label>补充理由（可留空沿用旧理由）</label><textarea name="justification"></textarea>
                  <label>新的证明材料摘要</label><textarea name="evidenceSummary"></textarea>
                  <label>新的证明材料 blob（可选）</label><input type="file" name="evidence">
                  <p><button type="submit">创建续期新版本</button></p></form></div>""")
            }

            append("<div class=\"panel\"><h2>版本链</h2>")
            append("""<table><tr><th>链版本</th><th>例外 ID</th><th>状态</th><th>区间结束</th><th>续期自</th></tr>""")
            chain.sortedBy { it.chainVersion }.forEach { link ->
                append("<tr><td>v${link.chainVersion}</td>")
                append("<td class=\"mono\"><a href=\"/exceptions/${urlEnc(link.exceptionId)}\">${e(link.exceptionId.take(20))}…</a></td>")
                append("<td>${statusBadge(service.effectiveStatus(link, now))}</td>")
                append("<td class=\"mono\">${e(formatInstant(link.validUntil))}</td>")
                val renewedCell = link.renewedFrom?.let { e(it.take(18)) + "…" } ?: "—"
                append("<td class=\"mono\">$renewedCell</td></tr>")
            }
            append("</table></div>")
            append("<p><a class=\"btn btn-secondary\" href=\"/exceptions\">返回列表</a></p>")
        }
    }
}
