package registry

import java.time.Instant

class Pages2(private val service: RegistryService) {
    private fun snap() = service.snapshot()
    private fun statusBadge(status: ExceptionStatus) =
        """<span class="badge b-$status">$status</span>"""

    fun layout2(active: String, title: String, block: StringBuilder.() -> Unit): String {
        val layout = Layout(title, active)
        layout.append(buildString(block))
        return layout.render(service.clock)
    }

    // ---- Exceptions list + submit form ----------------------------------

    fun exceptionsPage(preview: ScopePreview? = null, previewExpr: String? = null, error: String? = null): String =
        layout2("/exceptions", "例外") {
            val snap = snap()
            val now = service.clock.now()
            append("<div class=\"panel\"><h2>策略例外（按链分组）</h2>")
            append(Pages(service).exceptionTable(
                snap.exceptions.sortedWith(compareByDescending<ExceptionRecord> { it.submittedAt }.thenBy { it.exceptionId }),
                now, chainColumn = true
            ))
            append("</div>")

            append("<div class=\"panel\"><h2>提交新例外</h2>")
            if (error != null) append("<div class=\"flash flash-err\">${e(error)}</div>")
            append("<p class=\"muted\">例外只能放宽明确列出的具体条款，无法跳过整个策略。激活需要两名不同审核者依次确认。</p>")
            append("""<form method="post" action="/exceptions/submit" enctype="multipart/form-data">""")
            append("<div class=\"row\"><div><label>策略版本</label>")
            append("<select name=\"policyVersion\">")
            snap.policies.groupBy { it.policyId }.toSortedMap().forEach { (policyId, versions) ->
                val latest = versions.maxBy { it.version }
                append("<optgroup label=\"${e(policyId)} — ${e(latest.title)}\">")
                versions.sortedByDescending { it.version }.forEach { version ->
                    append("<option value=\"${e(policyId)}@${version.version}\">v${version.version}（${e(formatInstant(version.createdAt))}）</option>")
                }
                append("</optgroup>")
            }
            append("</select></div>")
            append("<div><label>提交者</label><input type=\"text\" name=\"actor\" value=\"carol\"></div></div>")
            append("<label>适用对象表达式（例如 role = 'lab' 或 zone = 'dmz' and owner = 'bob'）</label>")
            val exprValue = previewExpr ?: ""
            append("<input type=\"text\" name=\"subjectExpr\" value=\"${e(exprValue)}\">")
            append("""<p><button formaction="/exceptions/preview" formnovalidate class="btn btn-secondary" type="submit" formmethod="post">预览受影响范围</button></p>""")
            if (preview != null) scopePreviewBox(this, preview)
            append("<label>允许偏离的具体条款（必须勾选，至少一项）</label>")
            append("<div id=\"clauseBox\" class=\"chips\"></div>")
            append("<div class=\"row\"><div><label>生效开始 (UTC)</label><input type=\"datetime-local\" name=\"validFrom\" value=\"${e(toLocalInput(now.plusSeconds(60)))}\"></div>")
            append("<div><label>生效结束 (UTC，结束瞬间即失效)</label><input type=\"datetime-local\" name=\"validUntil\" value=\"${e(toLocalInput(now.plusSeconds(72 * 3600L)))}\"></div></div>")
            append("<label>偏离理由</label><textarea name=\"justification\" placeholder=\"业务背景、补偿控制措施…\"></textarea>")
            append("<label>证明材料摘要（仅摘要长期保存）</label><textarea name=\"evidenceSummary\" placeholder=\"工单号、审批人、风险接受结论…\"></textarea>")
            append("<label>证明材料本地 blob（可选，文件仅保存在本地 blobs/ 目录）</label>")
            append("<input type=\"file\" name=\"evidence\">")
            append("<p><button type=\"submit\">提交进入 pending</button></p></form></div>")
            append(clauseListScript(snap))
        }

    private fun scopePreviewBox(builder: StringBuilder, preview: ScopePreview) {
        fun append(content: Any?) = builder.append(content)
        val previewClass = if (preview.warning) "warning-box" else "flash flash-ok"
        append("<div class=\"$previewClass\">")
        append("受影响对象 ${preview.matchedCount} 个；稳定样例（按对象 ID 排序取前 ${RegistryService.SAMPLE_LIMIT} 个）：")
        val sampleChips = preview.samples.joinToString("") { sample -> "<span>" + e(sample) + "</span>" }
        append("<div class=\"chips\">$sampleChips</div>")
        if (preview.warning) append("<strong>交集较大：</strong>该表达式覆盖范围广，请确认是否符合最小化原则。")
        append("</div>")
    }

    private fun clauseListScript(snap: Snapshot): String {
        val groups = snap.policies.groupBy { it.policyId }.map { (policyId, versions) ->
            policyId to versions.maxBy { it.version }
        }
        val policyData = groups.joinToString(",") { (policyId, version) ->
            val items = version.clauses.joinToString(",") { clause ->
                val jsId = clause.id.replace("\\", "\\\\").replace("'", "\\'")
                val jsTitle = clause.title.replace("\\", "\\\\").replace("'", "\\'")
                "{id:'$jsId',title:'$jsTitle'}"
            }
            "'$policyId@${version.version}':[$items]"
        }
        return "<script>const POLICIES = {$policyData};" +
            "function renderClauses(){" +
            "const key=document.querySelector('select[name=policyVersion]').value;" +
            "const box=document.getElementById('clauseBox');box.innerHTML='';" +
            "(POLICIES[key]||[]).forEach(function(c){" +
            "var label=document.createElement('label');" +
            "label.style.margin='4px 0';" +
            "label.innerHTML='<input type=\"checkbox\" name=\"clause\" value=\"'+c.id+'\"> <code>'+c.id+'</code> '+c.title;" +
            "box.appendChild(label);});}" +
            "document.querySelector('select[name=policyVersion]').addEventListener('change',renderClauses);" +
            "renderClauses();</script>"
    }
}
