package registry

import java.net.URLEncoder
import java.time.Instant

class Pages(private val service: RegistryService) {
    private fun snap() = service.snapshot()

    private fun statusBadge(status: ExceptionStatus): String =
        """<span class="badge b-$status">$status</span>"""

    private fun layout(active: String, title: String, block: StringBuilder.() -> Unit): String {
        val layout = Layout(title, active)
        layout.append(buildString(block))
        return layout.render(service.clock)
    }

    private fun clauseChips(ids: List<String>): String =
        """<div class="chips">${ids.joinToString("") { id -> "<span>" + e(id) + "</span>" }}</div>"""

    // ---- Dashboard -------------------------------------------------------

    fun dashboard(): String = layout("/", "总览") {
        val snap = snap()
        val now = service.clock.now()
        append("<div class=\"panel\"><h2>实例总览</h2>")
        append("<table><tr><th>策略版本</th><th>例外总数</th><th>pending</th><th>active</th><th>expired</th><th>revoked</th><th>superseded</th><th>审计事件</th><th>评估记录</th></tr><tr>")
        append("<td>${snap.policies.size}</td>")
        ExceptionStatus.entries.forEach { status ->
            append("<td>${snap.exceptions.count { service.effectiveStatus(it, now) == status }}</td>")
        }
        append("<td>${snap.audit.size}</td><td>${snap.decisions.size}</td></tr></table>")
        append("<p class=\"muted\">服务端时间（UTC）：${e(formatInstant(now))}。区间结束瞬间即失效；撤销与到期不改变过去的决策记录。</p></div>")

        append("<div class=\"panel\"><h2>最近例外</h2>")
        val recent = snap.exceptions.sortedByDescending { it.submittedAt }.take(8)
        append(exceptionTable(recent, now, chainColumn = false))
        append("</div>")

        append("<div class=\"panel\"><h2>最近决策</h2>")
        append("""<table><tr><th>时间</th><th>对象</th><th>策略版本</th><th>结论</th><th>正常命中</th><th>例外放行</th></tr>""")
        snap.decisions.sortedByDescending { it.at }.take(8).forEach { decision ->
            append("<tr><td class=\"mono\">${e(formatInstant(decision.at))}</td><td>${e(decision.subjectId)}</td>")
            append("<td class=\"mono\">${e(decision.policyId)}/v${decision.policyVersion}</td>")
            append("<td><span class=\"badge b-${decision.verdict}\">${decision.verdict}</span></td>")
            append("<td>${clauseChips(decision.triggeredClauseIds)}</td>")
            append("<td>${clauseChips(decision.waivedClauseIds)}</td></tr>")
        }
        append("</table></div>")
    }

    // ---- Policies --------------------------------------------------------

    fun policiesPage(): String = layout("/policies", "策略") {
        val snap = snap()
        append("<div class=\"panel\"><h2>策略版本</h2>")
        if (snap.policies.isEmpty()) {
            append("<p class=\"muted\">还没有策略，请先创建。</p>")
        }
        snap.policies.groupBy { it.policyId }.toSortedMap().forEach { (policyId, versions) ->
            val latest = versions.maxBy { it.version }
            append("<h3 class=\"mono\">${e(policyId)} — ${e(latest.title)}（最新 v${latest.version}，共 ${versions.size} 个版本）</h3>")
            append("""<table><tr><th>版本</th><th>标题</th><th>条款数</th><th>编辑人</th><th>编辑时间</th><th>说明</th><th></th></tr>""")
            versions.sortedByDescending { it.version }.forEach { version ->
                append("<tr><td>v${version.version}</td><td>${e(version.title)}</td><td>${version.clauses.size}</td>")
                append("<td>${e(version.editedBy)}</td><td class=\"mono\">${e(formatInstant(version.createdAt))}</td>")
                append("<td>${e(version.changeNote)}</td>")
                append("<td><a class=\"btn btn-small btn-secondary\" href=\"/policies/edit?policy=${urlEnc(policyId)}&version=${version.version}\">基于此版本编辑</a></td></tr>")
            }
            append("</table>")
        }
        append("<p><a class=\"btn\" href=\"/policies/edit\">+ 新建策略</a></p></div>")
    }

    fun policyEditPage(policyId: String?, version: Int?, error: String? = null, form: PolicyForm? = null): String =
        layout("/policies", "编辑策略") {
        val base = if (policyId != null && version != null) service.policyVersion(policyId, version) else null
            val draftClauses = form?.clauses ?: base?.clauses?.map {
                ClauseDraft(it.id, it.title, it.severity, it.predicateExpr, it.rationale)
            } ?: listOf(ClauseDraft("C-NEW", "新条款", "high", "", ""))
        val heading = if (base == null) "新建策略" else "基于 ${e(policyId)} v$version 创建新版本"
        append("<div class=\"panel\"><h2>$heading</h2>")
        if (error != null) append("<div class=\"flash flash-err\">${e(error)}</div>")
        append("""<form method="post" action="/policies/save">""")
        if (policyId != null) append("<input type=\"hidden\" name=\"policyId\" value=\"${e(policyId)}\">")
        val idInputValue = form?.policyIdInput ?: policyId ?: ""
        val disabledAttr = if (policyId != null) "disabled" else ""
        val titleValue = form?.title ?: base?.title ?: ""
        val noteValue = form?.changeNote ?: ""
        val actorValue = form?.actor ?: "alice"
        append("<label>策略 ID（新建时可留空自动生成）</label>")
        append("<input type=\"text\" name=\"policyIdInput\" value=\"${e(idInputValue)}\" $disabledAttr>")
        append("<label>标题</label><input type=\"text\" name=\"title\" value=\"${e(titleValue)}\">")
        append("<label>变更说明</label><input type=\"text\" name=\"changeNote\" value=\"${e(noteValue)}\" placeholder=\"例如：收紧 DMZ 条款\">")
        append("<label>编辑人（操作者）</label><input type=\"text\" name=\"actor\" value=\"${e(actorValue)}\">")
            append("<h3>条款（表达式使用对象事实，例如 zone = 'dmz' and dataLevel = 'confidential'）</h3>")
            append("<div id=\"clauses\">")
            draftClauses.forEachIndexed { index, clause -> clauseFields(this, index, clause) }
            append("</div>")
            append("<input type=\"hidden\" name=\"clauseCount\" value=\"${draftClauses.size}\">")
            append("""<p><button type="button" class="btn btn-secondary" onclick="addClause()">+ 添加条款</button></p>""")
            append("<p><button type=\"submit\">保存为新版本</button> <a class=\"btn btn-secondary\" href=\"/policies\">取消</a></p>")
            append("</form></div>")
            append(clauseScript())
        }

    private fun clauseFields(builder: StringBuilder, index: Int, clause: ClauseDraft) {
        fun append(content: String) = builder.append(content)
        append("""<div class="panel" style="background:#fbfdff">""")
        append("<div class=\"row\"><div><label>条款 ID</label><input type=\"text\" name=\"cid$index\" value=\"${e(clause.id)}\"></div>")
        append("<div><label>标题</label><input type=\"text\" name=\"ctitle$index\" value=\"${e(clause.title)}\"></div>")
        append("<div><label>严重度</label><select name=\"cseverity$index\">")
        listOf("low", "normal", "medium", "high", "critical").forEach { severity ->
            val selectedAttr = if (severity == clause.severity) "selected" else ""
            append("<option value=\"$severity\" $selectedAttr>$severity</option>")
        }
        append("</select></div></div>")
        append("<label>命中表达式</label><input type=\"text\" name=\"cexpr$index\" value=\"${e(clause.predicateExpr)}\">")
        append("<label>条款说明</label><textarea name=\"crationale$index\">${e(clause.rationale)}</textarea>")
        append("</div>")
    }

    private fun clauseScript(): String = """
<script>
function addClause(){
  const wrap=document.getElementById('clauses');
  const index=wrap.children.length;
  const div=document.createElement('div');
  div.className='panel';div.style.background='#fbfdff';
  div.innerHTML =
    '<div class="row"><div><label>条款 ID</label><input type="text" name="cid'+index+'" value="C-NEW' + (index+1) + '"></div>' +
    '<div><label>标题</label><input type="text" name="ctitle'+index+'" value="新条款"></div>' +
    '<div><label>严重度</label><select name="cseverity'+index+'">' +
    '<option>low</option><option>normal</option><option selected>medium</option><option>high</option><option>critical</option>' +
    '</select></div></div>' +
    '<label>命中表达式</label><input type="text" name="cexpr'+index+'" value="">' +
    '<label>条款说明</label><textarea name="crationale'+index+'"></textarea>';
  wrap.appendChild(div);
}
</script>"""

    fun exceptionTable(records: List<ExceptionRecord>, now: Instant, chainColumn: Boolean): String = buildString {
        append("""<table><tr><th>例外 ID</th>${if (chainColumn) "<th>链</th>" else ""}<th>策略版本</th><th>放宽条款</th><th>对象表达式</th><th>提交者</th><th>生效区间 (UTC)</th><th>状态</th><th></th></tr>""")
        if (records.isEmpty()) append("<tr><td colspan=\"9\" class=\"muted\">暂无例外</td></tr>")
        records.forEach { record ->
            append("<tr><td class=\"mono\"><a href=\"/exceptions/${urlEnc(record.exceptionId)}\">${e(record.exceptionId.take(18))}…</a></td>")
            if (chainColumn) append("<td class=\"mono\">${e(record.chainId.take(14))}… v${record.chainVersion}</td>")
            append("<td class=\"mono\">${e(record.policyId)}/v${record.policyVersion}</td>")
            append("<td>${clauseChips(record.clauseIds)}</td>")
            append("<td><code>${e(record.subjectExpr)}</code></td>")
            append("<td>${e(record.submittedBy)}</td>")
            append("<td class=\"mono\">${e(formatInstant(record.validFrom))}<br>→ ${e(formatInstant(record.validUntil))}</td>")
            append("<td>${statusBadge(service.effectiveStatus(record, now))}</td>")
            append("<td><a class=\"btn btn-small\" href=\"/exceptions/${urlEnc(record.exceptionId)}\">详情</a></td></tr>")
        }
        append("</table>")
    }
}

data class PolicyForm(
    val policyIdInput: String,
    val title: String,
    val changeNote: String,
    val actor: String,
    val clauses: List<ClauseDraft>
)

fun urlEnc(value: String): String = URLEncoder.encode(value, "UTF-8")
