package registry

class Pages4(private val service: RegistryService) {
    private fun snap() = service.snapshot()

    private fun layout4(active: String, title: String, block: StringBuilder.() -> Unit): String {
        val layout = Layout(title, active)
        layout.append(buildString(block))
        return layout.render(service.clock)
    }

    // ---- Directory -------------------------------------------------------

    fun directoryPage(error: String? = null, prefillSubject: String = ""): String = layout4("/directory", "对象目录") {
        append("<div class=\"panel\"><h2>对象目录</h2>")
        if (error != null) append("<div class=\"flash flash-err\">${e(error)}</div>")
        append("""<table><tr><th>对象 ID</th><th>显示名</th><th>事实属性（键=值，每行一个）</th><th></th></tr>""")
        snap().subjects.sortedBy { it.subjectId }.forEach { subject ->
            append("<tr><td class=\"mono\">${e(subject.subjectId)}</td><td>${e(subject.displayName)}</td>")
            val attrChips = subject.attributes.entries.joinToString("") { entry ->
                "<span>" + e(entry.key) + "=" + e(entry.value) + "</span>"
            }
            append("<td><div class=\"chips\">$attrChips</div></td>")
            append("<td><form class=\"inline-form\" method=\"post\" action=\"/directory/delete\">")
            append("<input type=\"hidden\" name=\"subjectId\" value=\"${e(subject.subjectId)}\">")
            append("<button class=\"btn-small btn-danger\" type=\"submit\">删除</button></form></td></tr>")
        }
        append("</table></div>")
        append("""<div class="panel"><h2>新增 / 更新对象</h2>
          <form method="post" action="/directory/save">
          <div class="row"><div><label>对象 ID</label><input type="text" name="subjectId" value="${e(prefillSubject)}"></div>
          <div><label>显示名</label><input type="text" name="displayName"></div></div>
          <label>事实属性</label><textarea name="attributes" placeholder="zone=dmz&#10;dataLevel=confidential&#10;owner=bob"></textarea>
          <p class="muted">subject.id 会自动注入为对象 ID；事实评估页还可以临时追加事实。</p>
          <p><button>保存对象</button></p></form></div>""")
    }

    // ---- Decide ----------------------------------------------------------

    fun decidePage(
        result: DecisionResult? = null,
        error: String? = null,
        prefill: DecisionForm = DecisionForm("", "", "", "")
    ): String = layout4("/decide", "事实评估") {
        val snapshot = snap()
        append("<div class=\"panel\"><h2>输入事实，观察策略结论</h2>")
        if (error != null) append("<div class=\"flash flash-err\">${e(error)}</div>")
        append("""<form method="post" action="/decide">
          <div class="row">
            <div><label>策略版本</label><select name="policyVersion">""")
        snapshot.policies.groupBy { it.policyId }.toSortedMap().forEach { (policyId, versions) ->
            val latest = versions.maxBy { it.version }
            append("<optgroup label=\"${e(policyId)} — ${e(latest.title)}\">")
            versions.sortedByDescending { it.version }.forEach { version ->
                val selected = prefill.policyVersion == "$policyId@${version.version}"
                append("<option value=\"${e(policyId)}@${version.version}\" ${if (selected) "selected" else ""}>v${version.version}</option>")
            }
            append("</optgroup>")
        }
        append("""</select></div>
            <div><label>对象 ID（取自目录，也可直接给新 ID）</label><input type="text" name="subjectId" value="${e(prefill.subjectId)}"></div>
            <div><label>评估者</label><input type="text" name="actor" value="${e(prefill.actor)}"></div>
          </div>
          <label>额外事实（每行 key=value，与目录属性合并）</label>
          <textarea name="facts" placeholder="zone=dmz&#10;dataLevel=confidential">${e(prefill.facts)}</textarea>
          <p class="muted">评估使用当前服务端/模拟时钟，并留下决策审计；结论与解释分开呈现。</p>
          <p><button type="submit">给出策略结论</button></p></form></div>""")

        if (result != null) {
            append("<div class=\"panel\"><h2>结论：")
            append("<span class=\"badge b-${result.verdict}\">${result.verdict}</span>")
            append(""" <span class="muted">@ ${e(formatInstant(result.at))} · ${e(result.policy.policyId)}/v${result.policy.version} · 对象 ${e(result.subjectId)}</span></h2>""")
            result.dispositions.forEach { disposition ->
                val cssClass = when {
                    !disposition.triggered -> "idle"
                    disposition.enforced -> "enforced"
                    else -> "waived"
                }
                append("<div class=\"disposition $cssClass\">")
                append("<strong>${e(disposition.clause.id)}</strong> · ${e(disposition.clause.title)} · <span class=\"badge b-${e(disposition.clause.severity)}\">${e(disposition.clause.severity)}</span> ")
                when {
                    !disposition.triggered -> append("<span class=\"muted\">未命中</span>")
                    disposition.enforced -> append("<span class=\"badge b-DENY\">正常命中，按策略执行</span>")
                    else -> append("<span class=\"badge b-ALLOW\">例外放行</span>")
                }
                append("<div>${e(disposition.reason)}</div>")
                append("<div class=\"muted\">表达式：<code>${e(disposition.clause.predicateExpr)}</code> — ${e(disposition.clause.rationale)}</div>")
                append("</div>")
            }
            append("<h3>决策指纹（规范输入的 SHA-256，导出重建后保持一致）</h3>")
            append("<div class=\"fingerprint mono\">${e(result.record.fingerprint)}</div>")
            append("<p class=\"muted\">决策 ID：${e(result.record.decisionId)}</p></div>")
        }
    }

    // ---- Audit / chains --------------------------------------------------

    fun auditPage(): String = layout4("/audit", "版本/审计链") {
        val snapshot = snap()
        append("<div class=\"panel\"><h2>策略版本链</h2>")
        append("""<table><tr><th>策略</th><th>版本</th><th>基于</th><th>编辑人</th><th>时间</th><th>说明</th></tr>""")
        snapshot.policies.sortedWith(compareBy({ it.policyId }, { it.version })).forEach { version ->
            append("<tr><td class=\"mono\">${e(version.policyId)}</td><td>v${version.version}</td>")
            val baseVersion = version.supersedesVersion?.let { "v$it" } ?: "—"
            append("<td>$baseVersion</td>")
            append("<td>${e(version.editedBy)}</td><td class=\"mono\">${e(formatInstant(version.createdAt))}</td>")
            append("<td>${e(version.changeNote)}</td></tr>")
        }
        append("</table></div>")

        append("<div class=\"panel\"><h2>例外版本链</h2>")
        append("""<table><tr><th>链</th><th>链版本</th><th>例外 ID</th><th>续期自</th><th>状态</th></tr>""")
        snapshot.exceptions.sortedWith(compareBy({ it.chainId }, { it.chainVersion })).forEach { record ->
            append("<tr><td class=\"mono\">${e(record.chainId.take(20))}…</td><td>v${record.chainVersion}</td>")
            append("<td class=\"mono\"><a href=\"/exceptions/${urlEnc(record.exceptionId)}\">${e(record.exceptionId.take(20))}…</a></td>")
            val renewedRef = record.renewedFrom?.take(18)?.let { e(it) + "…" } ?: "—"
            append("<td class=\"mono\">$renewedRef</td>")
            append("<td><span class=\"badge b-${record.status}\">${record.status}</span></td></tr>")
        }
        append("</table></div>")

        append("<div class=\"panel\"><h2>决策记录</h2>")
        append("""<table><tr><th>时间</th><th>对象</th><th>策略版本</th><th>结论</th><th>命中</th><th>放行</th><th>指纹</th></tr>""")
        snapshot.decisions.sortedByDescending { it.at }.forEach { decision ->
            append("<tr><td class=\"mono\">${e(formatInstant(decision.at))}</td><td>${e(decision.subjectId)}</td>")
            append("<td class=\"mono\">${e(decision.policyId)}/v${decision.policyVersion}</td>")
            append("<td><span class=\"badge b-${decision.verdict}\">${decision.verdict}</span></td>")
            append("<td class=\"mono\" style=\"max-width:180px\">${e(decision.triggeredClauseIds.joinToString(","))}</td>")
            append("<td class=\"mono\" style=\"max-width:180px\">${e(decision.waivedClauseIds.joinToString(","))}</td>")
            append("<td class=\"mono\" style=\"max-width:150px\">${e(decision.fingerprint.take(16))}…</td></tr>")
        }
        append("</table></div>")

        append("<div class=\"panel\"><h2>审计链（严格按序号）</h2>")
        append("""<table><tr><th>#</th><th>时间</th><th>操作者</th><th>动作</th><th>对象</th><th>结果</th><th>详情（不含敏感材料）</th></tr>""")
        snapshot.audit.sortedByDescending { it.seq }.take(300).forEach { event ->
            append("<tr><td>${event.seq}</td><td class=\"mono\">${e(formatInstant(event.at))}</td>")
            append("<td>${e(event.actor)}</td><td class=\"mono\">${e(event.action)}</td>")
            append("<td class=\"mono\">${e(event.target)}</td>")
            val outcomeClass = if (event.outcome.startsWith("failure")) "REVOKED" else "ACTIVE"
            append("<td><span class=\"badge b-$outcomeClass\" style=\"font-size:11px\">${e(event.outcome)}</span></td>")
            append("<td class=\"mono\" style=\"max-width:360px\">${e(event.detail.entries.joinToString(", ") { "${it.key}=${it.value}" })}</td></tr>")
        }
        append("</table></div>")
    }

    // ---- Clock -----------------------------------------------------------

    fun clockPage(message: String? = null, error: Boolean = false): String = layout4("/clock", "时钟") {
        val now = service.clock.now()
        val (simulating, simulated, realNow) = service.clock.snapshot()
        append("<div class=\"panel\"><h2>服务端时钟（UTC）</h2>")
        if (message != null) append("<div class=\"flash ${if (error) "flash-err" else "flash-ok"}\">${e(message)}</div>")
        append("<p>判定时间：<strong class=\"mono\">${e(formatInstant(now))}</strong> ")
        append(if (simulating) "（模拟冻结于 ${e(formatInstant(simulated))}）" else "（真实 UTC 时间）")
        append("</p><p class=\"muted\">真实 UTC 参考：${e(formatInstant(realNow))}。区间结束瞬间即失效；模拟时间下到期产生的状态转换与真实模式规则相同。</p>")
        append("""<form method="post" action="/clock/freeze">
          <div class="row"><div><label>冻结到 (UTC)</label><input type="datetime-local" name="at" value="${e(toLocalInput(now))}"></div>
          <div style="align-self:end"><label>操作者</label><input type="text" name="actor" value="reviewer"></div></div>
          <p><button type="submit">冻结模拟时间</button></p></form>""")
        append("""<form method="post" action="/clock/advance">
          <div class="row"><div><label>向未来推进秒数</label><input type="text" name="seconds" value="3600"></div>
          <div style="align-self:end"><label>操作者</label><input type="text" name="actor2" value="reviewer"></div></div>
          <p><button class="btn btn-secondary" type="submit">推进时钟（触发到期扫描）</button></p></form>""")
        append("""<form method="post" action="/clock/reset"><input type="hidden" name="actor" value="reviewer">
          <button class="btn btn-danger" type="submit">恢复真实 UTC 时钟</button></form></div>""")
    }

    // ---- Bundle ----------------------------------------------------------

    fun bundlePage(message: String? = null, error: Boolean = false): String = layout4("/bundle", "导入导出") {
        append("<div class=\"panel\"><h2>导出实例</h2>")
        if (message != null) append("<div class=\"flash ${if (error) "flash-err" else "flash-ok"}\">${e(message)}</div>")
        append("<p>导出包包含完整 state.json 与未删除的本地 blob。重建后状态转换、决策指纹与审计顺序保持一致；已删除 blob 只保留摘要与哈希。</p>")
        append("""<p><a class="btn" href="/bundle/export">下载 instance.zip</a></p></div>""")
        append("""<div class="panel"><h2>从导出包重建实例</h2>
          <form method="post" action="/bundle/import" enctype="multipart/form-data">
          <label>操作者</label><input type="text" name="actor" value="admin">
          <label>instance.zip</label><input type="file" name="bundle" accept=".zip">
          <p class=\"warning-box\">导入会整体替换当前实例（含审计链与时钟设置）。</p>
          <p><button class="btn-danger" type="submit">导入并重建</button></p></form></div>""")
    }
}

data class DecisionForm(
    val actor: String,
    val subjectId: String,
    val facts: String,
    val policyVersion: String
)
