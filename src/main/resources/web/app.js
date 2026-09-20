"use strict";
const $ = (s, r=document)=>r.querySelector(s);
const $$ = (s, r=document)=>[...r.querySelectorAll(s)];
const esc = s => String(s??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
async function api(path, opts={}){
  const res = await fetch(path, Object.assign({headers:{"Content-Type":"application/json","X-Actor":currentActor()}}, opts));
  const text = await res.text();
  let data = text ? JSON.parse(text) : {};
  if(!res.ok){ const e = new Error(data.message||res.statusText); e.kind=data.error; throw e; }
  return data;
}
function toast(msg, ok=true){
  const d=document.createElement("div"); d.className=ok?"ok":"err"; d.textContent=msg;
  $("#toast").appendChild(d); setTimeout(()=>d.remove(), 4500);
}
let SIM_AT = "";
function localToInstant(v){
  // datetime-local 以浏览器本地时区选择，统一转 UTC ISO
  if(!v) return v;
  if(v.endsWith("Z")) return v;
  const d=new Date(v.length===16?v+":00":v);
  return d.toISOString();
}

function currentActor(){ return localStorage.getItem("actor")||"admin"; }
function simAt(){ const v=$("#simClock").value.trim(); return v||""; }
function fmt(iso){ if(!iso) return "—"; return iso.replace("T"," ").replace("Z"," Z").replace(/\.\d+ /," "); }
const STATUS_CLASS={pending:"t-pending",scheduled:"t-scheduled",active:"t-active",expired:"t-expired",revoked:"t-revoked"};
function statusTag(s){return `<span class="tag ${STATUS_CLASS[s]||""}">${esc(s)}</span>`;}
function clauseTag(id){return `<span class="pill mono">${esc(id)}</span>`;}
function fpShort(fp){return fp?`<span class="mono small muted">${fp.slice(0,12)}…</span>`:"—";}

const TABS = [
  ["policies","策略"],
  ["exceptions","例外"],
  ["decide","事实决策"],
  ["chains","版本链"],
  ["audit","审计链"],
  ["subjects","目录"],
  ["data","数据"],
];
function buildNav(){
  $("#nav").innerHTML = TABS.map(([id,name],i)=>`<button data-tab="${id}" class="${i===0?"active":""}">${name}</button>`).join("");
  $$("#nav button").forEach(b=>b.onclick=()=>switchTab(b.dataset.tab));
}
function switchTab(id){
  $$("#nav button").forEach(b=>b.classList.toggle("active",b.dataset.tab===id));
  RENDERERS[id]();
}
const RENDERERS = {};

RENDERERS.policies = async function(){
  const main = $("#main");
  main.innerHTML = `<div class="tab active"><div class="card">
    <h2>策略版本（编辑即生成新版本，不覆盖旧版本）</h2>
    <div id="polList" class="scroll"></div>
  </div>
  <div class="card"><h2 id="polFormTitle">新建策略</h2>
    <div class="row"><div><label>策略 ID（新建时可留空自动生成；填已有 ID 即基于最新版编辑）</label><input id="pId" placeholder="pol-xxxx 或留空"/></div>
    <div><label>名称</label><input id="pName" value="出站数据访问基线"/></div></div>
    <label>说明</label><input id="pDesc"/>
    <h3>条款（谓词为真 = 命中违规；只能逐条列出）</h3>
    <div id="clauseEditor"></div>
    <div style="margin-top:8px;display:flex;gap:8px">
      <button class="sec" id="addClause">+ 条款</button>
      <button class="act" id="savePolicy">保存（生成新版本）</button>
      <span class="small muted" id="editHint"></span>
    </div>
  </div></div>`;
  const data = await api("/api/policies");
  const grouped = {};
  data.policies.forEach(p=>{(grouped[p.id]??=[]).push(p);});
  $("#polList").innerHTML = Object.entries(grouped).map(([id,vs])=>{
    vs.sort((a,b)=>a.version-b.version);
    const latest = vs[vs.length-1];
    return `<div class="clausebox"><b class="mono">${esc(id)}</b> v${latest.version} — ${esc(latest.name)}
      <span class="small muted">（${vs.length} 个版本）</span>
      <div style="margin-top:6px">${latest.clauses.map(c=>clauseTag(c.id)).join(" ")}</div>
      <details style="margin-top:6px"><summary>查看全部版本与条款</summary>${
        vs.map(v=>`<div class="clausebox"><b>v${v.version}</b> ${esc(v.name)}
          ${v.basedOnVersion?`<span class="pill">基于 v${v.basedOnVersion}</span>`:""}
          <div class="small muted">${fmt(v.createdAt)}</div>
          <table><tr><th>条款</th><th>标题</th><th>谓词</th></tr>
          ${v.clauses.map(c=>`<tr><td class="mono">${esc(c.id)}</td><td>${esc(c.title)}</td><td class="mono">${esc(c.predicate)}</td></tr>`).join("")}
          </table></div>`).join("")
      }</details>
      <button class="sec" style="margin-top:6px" data-edit="${esc(id)}">基于最新版编辑</button>
    </div>`;
  }).join("") || `<p class="muted">还没有策略</p>`;
  $$("[data-edit]").forEach(b=>b.onclick=async()=>{
    const p = data.policies.filter(x=>x.id===b.dataset.edit).sort((a,b)=>b.version-a.version)[0];
    $("#pId").value=p.id; $("#pName").value=p.name; $("#pDesc").value=p.description;
    $("#editHint").textContent=`保存后将成为 ${p.id} v${p.version+1}`;
    renderClauseEditor(p.clauses.map(c=>({...c})));
  });
  function renderClauseEditor(clauses){
    $("#clauseEditor").innerHTML = clauses.map((c,i)=>clauseRowHtml(c,i)).join("");
    bindClauseRows(clauses);
  }
  function clauseRowHtml(c,i){
    return `<div class="clausebox" data-row="${i}">
      <div class="row">
        <div style="flex:0 0 130px"><label>条款 ID</label><input class="c-id" value="${esc(c.id||"")}"/></div>
        <div style="flex:0 0 200px"><label>标题</label><input class="c-title" value="${esc(c.title||"")}"/></div>
        <div><label>谓词（为真=违规）</label><input class="c-pred" value="${esc(c.predicate||"")}"/></div>
      </div>
      <label>描述</label><input class="c-desc" value="${esc(c.description||"")}"/>
      <button class="sec c-del" style="margin-top:6px">删除条款</button></div>`;
  }
  function bindClauseRows(clauses){
    $$("#clauseEditor [data-row]").forEach((row,idx)=>{
      $(".c-del",row).onclick=()=>{clauses.splice(idx,1);renderClauseEditor(clauses);};
      $(".c-id",row).oninput=e=>clauses[idx].id=e.target.value;
      $(".c-title",row).oninput=e=>clauses[idx].title=e.target.value;
      $(".c-pred",row).oninput=e=>clauses[idx].predicate=e.target.value;
      $(".c-desc",row).oninput=e=>clauses[idx].description=e.target.value;
    });
  }
  renderClauseEditor([]);
  $("#addClause").onclick=()=>{
    const rows=collect();
    rows.push({id:"",title:"",description:"",predicate:""});
    renderClauseEditor(rows);
  };
  function collect(){
    return $$("#clauseEditor [data-row]").map(row=>({
      id:$(".c-id",row).value.trim(),title:$(".c-title",row).value.trim(),
      description:$(".c-desc",row).value,predicate:$(".c-pred",row).value.trim()}));
  }
  $("#savePolicy").onclick=async()=>{
    const clauses=collect();
    try{
      const body={name:$("#pName").value.trim(),description:$("#pDesc").value,clauses,actor:currentActor()};
      const id=$("#pId").value.trim(); if(id){body.id=id; const latest=grouped[id]?.slice(-1)[0]; if(latest) body.basedOnVersion=latest.version;}
      const p=await api("/api/policies",{method:"POST",body:JSON.stringify(body)});
      toast(`已保存 ${p.id} v${p.version}`); RENDERERS.policies();
    }catch(e){toast("保存失败: "+e.message,false);}
  };
};

async function loadPolicies(){ return (await api("/api/policies")).policies; }
async function loadExceptions(){ return (await api("/api/exceptions")).exceptions; }

RENDERERS.exceptions = async function(){
  const main=$("#main");
  main.innerHTML=`<div class="tab active">
    <div class="card"><h2>例外记录（pending → active → expired / revoked；UTC 区间右端点开区间）</h2>
      <div id="excList" class="scroll"></div></div>
    <div class="card"><h2>提交新例外</h2>
      <div class="row">
        <div><label>策略版本</label><select id="fPolicy"></select></div>
        <div><label>提交者</label><input id="fSubmitter" value="alice"/></div>
      </div>
      <label>适用对象表达式（如 host == 'db-01' 或 team == 'web' && env == 'prod'）</label>
      <input id="fExpr" value="host == 'db-01'"/>
      <div style="margin:6px 0"><button class="sec" id="fScopeBtn">预览受影响范围</button> <span id="fScope" class="small"></span></div>
      <label>允许偏离的具体条款（不得选择全部条款）</label>
      <div id="fClauses"></div>
      <div class="row">
        <div><label>生效开始 UTC</label><input id="fStart" type="datetime-local" step="1"/></div>
        <div><label>生效结束 UTC（结束瞬间已失效）</label><input id="fEnd" type="datetime-local" step="1"/></div>
      </div>
      <div class="row">
        <div><label>证明材料摘要（随历史永久保留）</label><textarea id="fSummary" rows="2"></textarea></div>
      </div>
      <div class="row">
        <div><label>本地材料文件（存本地 blob，仅摘要进入记录）</label><input id="fFile" type="file"/></div>
      </div>
      <button class="act" id="fSubmit" style="margin-top:8px">提交例外</button>
    </div></div>`;
  const policies=await loadPolicies();
  const sel=$("#fPolicy");
  policies.slice().sort((a,b)=>a.id.localeCompare(b.id)||b.version-a.version)
    .forEach(p=>{const o=document.createElement("option");o.value=`${p.id}|${p.version}`;
      o.textContent=`${p.id} v${p.version} — ${p.name}`;sel.appendChild(o);});
  function currentPolicy(){const [id,v]=sel.value.split("|");return policies.find(p=>p.id===id&&p.version===+v);}
  function renderClauseChecks(){
    const p=currentPolicy();
    $("#fClauses").innerHTML=p.clauses.map(c=>`<label style="color:var(--txt);display:inline-block;margin-right:14px">
      <input type="checkbox" class="f-clause" value="${esc(c.id)}" style="width:auto"/>
      <span class="mono">${esc(c.id)}</span> <span class="small muted">${esc(c.title)}</span></label>`).join("");
  }
  sel.onchange=renderClauseChecks; renderClauseChecks();
  $("#fScopeBtn").onclick=async()=>{
    try{const r=await api("/api/scope-preview",{method:"POST",body:JSON.stringify({subjectExpr:$("#fExpr").value})});
      if(!r.validExpression){$("#fScope").innerHTML=`<span class="bad-text">表达式无效：${esc(r.error)}</span>`;return;}
      $("#fScope").innerHTML=`命中 <b>${r.matched}</b>/${r.totalSubjects}，稳定样例: ${
        r.sampleIds.map(esc).join(", ")||"(空)"} ${r.broad?'<span class="warn-text">⚠ 交集过大</span>':'<span class="ok-text">范围收敛</span>'}`;
    }catch(e){$("#fScope").textContent="预览失败: "+e.message;}
  };
  $("#fStart").value="2026-09-21T02:00"; $("#fEnd").value="2026-09-22T02:00";
  $("#fSummary").value="变更窗口审批单（请填写编号与限制说明）";
  $("#fSubmit").onclick=async()=>{
    const [pid,pv]=sel.value.split("|");
    const clauses=$$(".f-clause:checked").map(c=>c.value);
    let blobId=null;
    const f=$("#fFile").files[0];
    try{
      if(f){
        const b64=btoa(String.fromCharCode(...new Uint8Array(await f.arrayBuffer())));
        const up=await api("/api/blobs",{method:"POST",body:JSON.stringify({contentBase64:b64,fileName:f.name,actor:currentActor()})});
        blobId=up.id;
      }
      const rec=await api("/api/exceptions",{method:"POST",body:JSON.stringify({
        policyId:pid,policyVersion:+pv,submitter:$("#fSubmitter").value.trim(),
        subjectExpr:$("#fExpr").value,relaxedClauseIds:clauses,
        startAt:localToInstant($("#fStart").value),endAt:localToInstant($("#fEnd").value),
        evidenceSummary:$("#fSummary").value,evidenceBlobId:blobId})});
      toast(`已提交 ${rec.id}（pending，需要两名不同审核者）`);
      RENDERERS.exceptions();
    }catch(e){toast("提交失败: "+e.message,false);}
  };
  await renderExceptionList();
};

async function renderExceptionList(){
  const exceptions=await loadExceptions();
  const at=simAt();
  $("#excList").innerHTML=`<table><thead><tr>
    <th>例外</th><th>链/版本</th><th>策略</th><th>对象表达式</th><th>放宽条款</th>
    <th>区间(UTC)</th><th>提交/审核</th><th>状态${at?'<br><span class=warn-text>(观察时钟)</span>':''}</th><th>材料</th><th>rev</th><th>操作</th>
  </tr></thead><tbody>${exceptions.map(r=>{
    const s=at?viewStatus(r,at):r.status;
    return `<tr>
    <td class="mono">${esc(r.id)}<br><span class="small muted">${fmt(r.createdAt)}</span></td>
    <td class="mono">${esc(r.chainId.slice(0,12))}<br>v${r.versionNo}${r.renewedFrom?` <span class="pill">续自 v${r.renewedFrom.versionNo}</span>`:""}</td>
    <td class="mono small">${esc(r.policyId)}@v${r.policyVersion}</td>
    <td class="mono small">${esc(r.subjectExpr)}${r.scope?`<div class="small ${r.scope.broad?'warn-text':'muted'}">命中 ${r.scope.matched}/${r.scope.totalSubjects}${r.scope.sampleIds.length?`；样例 ${r.scope.sampleIds.map(esc).join(",")}`:""}</div>`:""}</td>
    <td>${r.relaxedClauseIds.map(clauseTag).join(" ")}</td>
    <td class="small mono">${fmt(r.startAt)}<br>→ ${fmt(r.endAt)}</td>
    <td class="small">${esc(r.submitter)}<br>${r.approvals.map(a=>`✔${esc(a.reviewer)}`).join(" ")||'<span class=muted>待审核</span>'}</td>
    <td>${statusTag(s)}${r.revokedBy?`<div class="small muted">${esc(r.revokedBy)}: ${esc(r.revokeReason||"")}</div>`:""}</td>
    <td class="small">${esc((r.evidenceSummary||"").slice(0,30))}…<br>${
      r.evidenceBlobId?(r.evidenceBlobPresent?`<a href="/api/blobs/${r.evidenceBlobId}">blob</a>`:'<span class="bad-text">blob 已删除（摘要保留）</span>'):'<span class=muted>无</span>'}</td>
    <td class="mono">${r.rev}</td>
    <td class="small" style="white-space:nowrap">
      <button data-approve="${esc(r.id)}" data-rev="${r.rev}" ${r.storedState!=="PENDING"?"disabled":""}>审核</button>
      <button data-revoke="${esc(r.id)}" data-rev="${r.rev}" ${r.storedState==="REVOKED"?"disabled":""}>撤销</button>
      <button data-renew="${esc(r.id)}" data-rev="${r.rev}" ${r.storedState==="PENDING"?"disabled":""}>续期</button>
    </td></tr>`;}).join("")}</tbody></table>`;
  $$("[data-approve]").forEach(b=>b.onclick=()=>approve(b.dataset.approve,+b.dataset.rev));
  $$("[data-revoke]").forEach(b=>b.onclick=()=>revoke(b.dataset.revoke,+b.dataset.rev));
  $$("[data-renew]").forEach(b=>b.onclick=()=>renew(b.dataset.renew,+b.dataset.rev));
}
function viewStatus(r,at){
  if(r.storedState==="REVOKED")return "revoked";
  if(r.storedState==="PENDING")return "pending";
  const start=r.startAt, end=r.endAt;
  if(at<start)return "scheduled";
  if(at>=end)return "expired";
  return "active";
}
async function approve(id,rev){
  const reviewer=prompt("审核者用户名（不能是提交者；需两名不同审核者依次确认）",currentActor());
  if(!reviewer)return; localStorage.setItem("actor",reviewer);
  try{await api(`/api/exceptions/${id}/approve`,{method:"POST",body:JSON.stringify({reviewer,expectedRev:rev})});
    toast("确认成功"); RENDERERS.exceptions();
  }catch(e){toast("确认失败: "+e.message,false); RENDERERS.exceptions();}
}
async function revoke(id,rev){
  const reviewer=prompt("撤销者用户名",currentActor()); if(!reviewer)return;
  const reason=prompt("撤销原因","违反使用条件"); if(reason===null)return;
  try{await api(`/api/exceptions/${id}/revoke`,{method:"POST",body:JSON.stringify({reviewer,reason,expectedRev:rev})});
    toast("已撤销（不改变过去的决策记录）"); RENDERERS.exceptions();
  }catch(e){toast("撤销失败: "+e.message,false); RENDERERS.exceptions();}
}
async function renew(id,rev){
  const start=prompt("新版本生效开始 UTC（例：2026-09-23T00:00:00Z）","2026-09-23T00:00:00Z"); if(!start)return;
  const end=prompt("新版本生效结束 UTC（必须创建新版本，不能原地延长）","2026-09-24T00:00:00Z"); if(!end)return;
  const actor=prompt("续期提交者",currentActor()); if(!actor)return;
  try{const r=await api(`/api/exceptions/${id}/renew`,{method:"POST",body:JSON.stringify({
    actor,startAt:start,endAt:end,expectedRev:rev})});
    toast(`续期版本已创建: ${r.id} v${r.versionNo}（pending，需重新双审）`); RENDERERS.exceptions();
  }catch(e){toast("续期失败: "+e.message,false); RENDERERS.exceptions();}
}

RENDERERS.decide = async function(){
  const policies=await loadPolicies();
  $("#main").innerHTML=`<div class="tab active">
    <div class="card"><h2>输入事实，观察策略解释</h2>
      <div class="row">
        <div><label>策略版本</label><select id="dPolicy">${policies.map(p=>`<option value="${p.id}|${p.version}">${p.id} v${p.version}</option>`).join("")}</select></div>
        <div><label>决策时刻 UTC（留空=服务器当前；可模拟历史/未来）</label><input id="dAt" placeholder="2026-09-21T03:00:00Z"/></div>
        <div style="flex:0 0 160px"><label>是否落决策记录</label>
          <select id="dPersist"><option value="true">保存（真实决策）</option><option value="false">仅试算（不入库）</option></select></div>
      </div>
      <label>事实 JSON（决策上下文；policyId/policyVersion 可用上方下拉覆盖）</label>
      <textarea id="dFact" rows="9" class="mono"></textarea>
      <div style="margin-top:8px;display:flex;gap:8px;align-items:center">
        <button class="act" id="dRun">给出策略结论</button>
        <span class="small muted" id="dHint"></span>
      </div>
    </div>
    <div class="card" id="dResult" style="display:none"></div>
    <div class="card"><h2>历史决策（不可变；撤销/到期不改变这里）</h2><div id="dHistory" class="scroll"></div></div>
  </div>`;
  const defaultFact={host:"db-01",team:"platform",env:"prod",transport:"tls",
    destination:"public",dataClass:"internal",egressBytes:20_000_000};
  $("#dFact").value=JSON.stringify(defaultFact,null,2);
  const seed=policies.find(p=>p.clauses.some(c=>c.id==="c-volume"));
  if(seed){$("#dPolicy").value=`${seed.id}|${seed.version}`;}
  $("#dRun").onclick=runDecision;
  await renderHistory();
};
async function runDecision(){
  let fact;
  try{fact=JSON.parse($("#dFact").value);}catch(e){toast("事实 JSON 解析失败: "+e.message,false);return;}
  const [pid,pv]=$("#dPolicy").value.split("|");
  fact.policyId=pid; fact.policyVersion=+pv;
  const at=$("#dAt").value.trim();
  try{
    const r=await api("/api/decisions",{method:"POST",body:JSON.stringify({
      fact,at:at||undefined,persist:$("#dPersist").value==="true"})});
    renderDecision(r); renderHistory();
  }catch(e){toast("决策失败: "+e.message,false);}
}
function renderDecision(r){
  const card=$("#dResult"); card.style.display="block";
  const cls=r.conclusion==="DENIED"?"t-deny":"t-allow";
  const label={CLEAN:"通过：无命中",ALLOWED_BY_EXCEPTION:"通过：例外条款级放行",DENIED:"拒绝：仍有未覆盖命中"}[r.conclusion];
  card.innerHTML=`<h2>结论 <span class="tag ${cls}">${label}</span> <span class="muted small">@ ${fmt(r.at)}</span></h2>
  <div class="small muted">决策指纹：<span class="mono">${r.fingerprint}</span>${r.persisted?`（记录 #${r.decisionId}）`:"（试算未入库）"}</div>
  <div class="row" style="margin-top:10px">
    <div class="clausebox"><h3>① 正常命中（与例外无关）</h3>
      ${r.normalHits.length?`<table>${r.normalHits.map(h=>`<tr><td class="mono">${esc(h.clauseId)}</td><td>${esc(h.title)}</td><td class="small muted mono">${esc(h.predicate)}</td></tr>`).join("")}</table>`:'<p class="muted small">无命中</p>'}</div>
    <div class="clausebox"><h3>② 例外放行（仅放宽明确列出的条款）</h3>
      ${r.exceptionReliefs.length?`<table><tr><th>条款</th><th>例外</th><th>版本</th><th>证明摘要</th></tr>${
        r.exceptionReliefs.map(x=>`<tr><td class="mono">${esc(x.clauseId)}</td><td class="mono">${esc(x.exceptionId)}</td><td>v${x.versionNo}</td><td class="small">${esc(x.evidenceSummary)}</td></tr>`).join("")}</table>`
        :'<p class="muted small">没有可采纳的放行</p>'}
      ${r.ignoredExceptions.length?`<h3>看到但未采纳的例外</h3><table>${r.ignoredExceptions.map(x=>`<tr><td class="mono">${esc(x.exceptionId)} v${x.versionNo}</td><td>${statusTag(x.status)}</td><td class="small warn-text">${esc(x.reason)}</td></tr>`).join("")}</table>`:""}
    </div>
    <div class="clausebox"><h3>③ 仍然违规的条款</h3>
      ${r.remainingViolations.length?r.remainingViolations.map(clauseTag).join(" "):'<p class="ok-text small">无 → 可放行</p>'}</div>
  </div>
  <h3>文字解释</h3><pre class="box">${esc(r.explanation)}</pre>`;
}
async function renderHistory(){
  const data=await api("/api/decisions");
  const list=data.decisions.slice().reverse();
  $("#dHistory").innerHTML=list.length?`<table><tr><th>#</th><th>时刻 UTC</th><th>策略</th><th>命中</th><th>放行</th><th>残留</th><th>结论</th><th>指纹</th></tr>${
    list.map(d=>`<tr><td>${d.id}</td><td class="small mono">${fmt(d.at)}</td><td class="mono small">${esc(d.policyId)}@v${d.policyVersion}</td>
      <td>${d.hits.map(clauseTag).join(" ")||"—"}</td>
      <td>${d.reliefs.map(u=>clauseTag(u.clauseId)).join(" ")||"—"}</td>
      <td>${d.remaining.map(clauseTag).join(" ")||"—"}</td>
      <td><span class="tag ${d.conclusion==="DENIED"?"t-deny":"t-allow"}">${d.conclusion}</span></td>
      <td>${fpShort(d.fingerprint)}</td></tr>`).join("")}</table>`
    :'<p class="muted small">暂无历史决策</p>';
}

RENDERERS.chains = async function(){
  const exceptions=await loadExceptions();
  const chains={};
  exceptions.forEach(e=>{(chains[e.chainId]??=[]).push(e);});
  $("#main").innerHTML=`<div class="tab active"><div class="card"><h2>例外版本链（续期 = 新版本指向旧版本，旧版本区间不可原地延长）</h2>
    <div id="chainView"></div></div>
    <div class="card"><h2>策略版本链</h2><div id="polChain"></div></div></div>`;
  $("#chainView").innerHTML=Object.entries(chains).map(([cid,vs])=>{
    vs.sort((a,b)=>a.versionNo-b.versionNo);
    return `<div class="clausebox"><b class="mono">链 ${esc(cid)}</b>
      <div style="display:flex;gap:8px;align-items:stretch;flex-wrap:wrap;margin-top:8px">${
      vs.map((v,i)=>`<div style="border:1px solid var(--line);border-radius:8px;padding:8px;min-width:230px;background:var(--panel2)">
        <div>${statusTag(viewStatus(v,simAt()||new Date().toISOString().replace(/\.\d+Z$/,"Z")))} <b>v${v.versionNo}</b> <span class="mono small muted">${esc(v.id)}</span></div>
        <div class="small mono">${fmt(v.startAt)}<br>→ ${fmt(v.endAt)}</div>
        <div class="small">条款: ${v.relaxedClauseIds.map(clauseTag).join(" ")}</div>
        ${v.renewedFrom?`<div class="small muted">续自 ${esc(v.renewedFrom.id)} v${v.renewedFrom.versionNo}</div>`:"<div class='small muted'>初始版本</div>"}
      </div>${i<vs.length-1?'<div style="align-self:center;font-size:20px;color:var(--acc)">→</div>':""}`).join("")}
      </div></div>`;
  }).join("")||'<p class="muted">无例外</p>';
  const policies=await loadPolicies();
  const pg={};policies.forEach(p=>(pg[p.id]??=[]).push(p));
  $("#polChain").innerHTML=Object.entries(pg).map(([id,vs])=>{
    vs.sort((a,b)=>a.version-b.version);
    return `<div class="clausebox"><b class="mono">${esc(id)}</b><div class="small muted">${esc(vs[vs.length-1].name)}</div>
      <div class="small">${vs.map(v=>`v${v.version}${v.basedOnVersion?`(←v${v.basedOnVersion})`:""}`).join(" → ")}</div></div>`;
  }).join("");
};

RENDERERS.audit = async function(){
  const data=await api("/api/audit");
  $("#main").innerHTML=`<div class="tab active"><div class="card">
    <h2>审计链（append-only 哈希链；失败请求也留事件，但不含证明材料正文）</h2>
    <p>${data.verify.length?`<span class="bad-text">链校验异常: ${esc(data.verify.join("; "))}</span>`:'<span class="ok-text">✔ 哈希链完整且顺序一致</span>'}
    <span class="small muted">（共 ${data.events.length} 条；最新在底部）</span></p>
    <div class="scroll"><table><tr><th>#</th><th>UTC 时间</th><th>事件</th><th>操作者</th><th>对象</th><th>结果</th><th>原因/备注</th><th>哈希</th></tr>${
      data.events.map(e=>`<tr><td>${e.seq}</td><td class="small mono">${fmt(e.at)}</td><td class="mono">${esc(e.type)}</td>
        <td>${esc(e.actor)}</td><td class="small mono">${esc(e.targetId||"—")}${e.chainId?`<div class="muted">${esc(e.chainId.slice(0,10))}</div>`:""}</td>
        <td>${e.ok?'<span class="ok-text">成功</span>':'<span class="bad-text">失败</span>'}</td>
        <td class="small ${e.ok?'muted':'warn-text'}">${esc(e.reason||"")}</td>
        <td class="mono small muted">${e.hash.slice(0,10)}…</td></tr>`).join("")}
    </table></div></div></div>`;
};

RENDERERS.subjects = async function(){
  const data=await api("/api/subjects");
  $("#main").innerHTML=`<div class="tab active"><div class="card"><h2>适用对象目录（用于估算对象表达式交集与稳定样例）</h2>
    <table><tr><th>ID</th><th>属性 JSON</th><th></th></tr>${
      data.subjects.map(s=>`<tr><td class="mono">${esc(s.id)}</td><td class="mono small">${esc(JSON.stringify(s.attrs))}</td><td></td></tr>`).join("")}</table></div>
    <div class="card"><h2>新增 / 覆盖对象</h2>
      <div class="row"><div><label>ID</label><input id="sId"/></div>
      <div style="flex:3"><label>属性 JSON</label><input id="sAttrs" class="mono" value='{"host":"db-03","team":"platform","env":"prod"}'/></div></div>
      <button class="act" id="sSave" style="margin-top:8px">保存对象</button></div></div>`;
  $("#sSave").onclick=async()=>{
    try{await api("/api/subjects",{method:"POST",body:JSON.stringify({
      id:$("#sId").value.trim(),attrs:JSON.parse($("#sAttrs").value),actor:currentActor()})});
      toast("已保存"); RENDERERS.subjects();
    }catch(e){toast("保存失败: "+e.message,false);}
  };
};

RENDERERS.data = async function(){
  $("#main").innerHTML=`<div class="tab active"><div class="card"><h2>导出 / 重建</h2>
    <p class="small muted">导出为 data 目录的 zip。将其解压到一个空目录并用 <code>--data</code> 指向后，状态转换、决策指纹与审计顺序完全一致。</p>
    <a class="act" style="display:inline-block;text-decoration:none;padding:8px 14px;border-radius:6px" href="/api/export">⬇ 导出全部数据 (zip)</a>
  </div>
  <div class="card"><h2>证明材料 blob 管理</h2>
    <p class="small muted">blob 可单独删除；删除后例外与历史决策仍保留证明摘要，但无法再下载正文。</p>
    <div id="blobList"></div></div></div>`;
  const exceptions=await loadExceptions();
  const rows=[...new Map(exceptions.filter(e=>e.evidenceBlobId).map(e=>[e.evidenceBlobId,e])).values()];
  $("#blobList").innerHTML=rows.length?`<table><tr><th>blob</th><th>引用例外</th><th>摘要（永久保留）</th><th>状态</th><th></th></tr>${
    rows.map(e=>`<tr><td class="mono small">${esc(e.evidenceBlobId.slice(0,16))}…</td>
      <td class="mono small">${esc(e.id)} v${e.versionNo}</td>
      <td class="small">${esc(e.evidenceSummary)}</td>
      <td>${e.evidenceBlobPresent?'<span class="ok-text">存在</span>':'<span class="bad-text">已删除（摘要保留）</span>'}</td>
      <td>${e.evidenceBlobPresent?`<button data-del="${esc(e.evidenceBlobId)}">删除 blob</button>`:""}</td></tr>`).join("")}</table>`
    :'<p class="muted small">暂无 blob</p>';
  $$("[data-del]").forEach(b=>b.onclick=async()=>{
    if(!confirm("删除后不可恢复下载，但历史记录继续保留摘要。确认？"))return;
    try{await api(`/api/blobs/${b.dataset.del}`,{method:"DELETE"});toast("blob 已删除");RENDERERS.data();}
    catch(e){toast("删除失败: "+e.message,false);RENDERERS.data();}
  });
};

async function refreshHealth(){
  try{const h=await api("/api/health");
    $("#health").innerHTML=`服务器 UTC <b>${fmt(h.serverTimeUtc)}</b> · data <span class="mono">${esc(h.dataDir)}</span>`;
    $("#realClock").textContent="实时: "+h.serverTimeUtc.slice(11,19);
  }catch(e){$("#health").textContent="服务不可用";}
}

buildNav();
RENDERERS.policies();
refreshHealth();
setInterval(refreshHealth,15000);
$("#clockNow").onclick=()=>api("/api/health").then(h=>{$("#simClock").value=h.serverTimeUtc;});
$("#clockReset").onclick=()=>{$("#simClock").value="";toast("观察时钟已重置为服务器实时");
  const active=$("#nav button.active"); if(active) switchTab(active.dataset.tab);};
$("#simClock").addEventListener("change",()=>{const active=$("#nav button.active");if(active)switchTab(active.dataset.tab);});
