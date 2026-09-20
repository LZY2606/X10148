package registry

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)

fun formatInstant(instant: Instant?): String = if (instant == null) "—" else DISPLAY_TIME.format(instant)

fun htmlEscape(value: Any?): String {
    if (value == null) return ""
    val builder = StringBuilder(value.toString().length + 16)
    for (char in value.toString()) {
        when (char) {
            '<' -> builder.append("&lt;")
            '>' -> builder.append("&gt;")
            '&' -> builder.append("&amp;")
            '"' -> builder.append("&quot;")
            '\'' -> builder.append("&#39;")
            else -> builder.append(char)
        }
    }
    return builder.toString()
}

fun e(value: Any?): String = htmlEscape(value)

/** `yyyy-MM-ddTHH:mm` value suitable for <input type="datetime-local"> interpreted as UTC. */
fun toLocalInput(instant: Instant?): String {
    if (instant == null) return ""
    return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(ZoneOffset.UTC).format(instant)
}

fun parseLocalInput(value: String): Instant {
    val trimmed = value.trim()
    return if (trimmed.length == 16) Instant.parse("${trimmed}:00Z")
    else Instant.parse(trimmed.replace(' ', 'T').let { if (it.endsWith("Z")) it else "${it}Z" })
}

class Layout(val title: String, val activeNav: String) {
    private val body = StringBuilder()

    fun append(content: String) {
        body.append(content)
    }

    fun render(clock: ServiceClock, flash: String? = null, flashKind: String = "ok"): String {
        val (simulating, simulated, _) = clock.snapshot()
        val clockBanner = if (simulating) {
            """<div class="clock-banner">模拟时钟：${e(formatInstant(simulated))} —— 当前查看的是模拟时间下的状态，到期不会改写真实数据</div>"""
        } else ""
        val flashHtml = if (flash.isNullOrBlank()) "" else
            """<div class="flash flash-$flashKind">${e(flash)}</div>"""
        return """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${e(title)} · 策略例外登记站</title>
<style>
:root { --bg:#f5f7fa; --panel:#ffffff; --ink:#1f2933; --muted:#64748b;
  --line:#d9e0e8; --brand:#1d4ed8; --brand-dark:#1e40af; --ok:#15803d;
  --warn:#b45309; --bad:#b91c1c; --chip:#eef2ff; }
* { box-sizing:border-box; }
body { margin:0; font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif;
  background:var(--bg); color:var(--ink); font-size:14px; line-height:1.55; }
header { background:var(--brand); color:#fff; padding:14px 24px; display:flex; align-items:center; gap:18px; flex-wrap:wrap; }
header h1 { font-size:18px; margin:0; font-weight:600; }
nav { display:flex; gap:4px; flex-wrap:wrap; }
nav a { color:#dbeafe; text-decoration:none; padding:5px 11px; border-radius:6px; }
nav a.active, nav a:hover { background:rgba(255,255,255,.18); color:#fff; }
.clock-banner { background:#fef3c7; color:#92400e; padding:8px 24px; font-size:13px; border-bottom:1px solid #fcd34d; }
main { max-width:1180px; margin:22px auto; padding:0 20px 60px; }
.panel { background:var(--panel); border:1px solid var(--line); border-radius:10px; padding:18px 20px; margin-bottom:18px; }
.panel h2 { margin:0 0 12px; font-size:16px; }
.panel h3 { margin:16px 0 8px; font-size:14px; }
table { border-collapse:collapse; width:100%; font-size:13px; }
th,td { text-align:left; padding:7px 9px; border-bottom:1px solid var(--line); vertical-align:top; }
th { color:var(--muted); font-weight:600; white-space:nowrap; }
tr:last-child td { border-bottom:none; }
.badge { display:inline-block; padding:1px 9px; border-radius:999px; font-size:12px; font-weight:600; }
.b-PENDING { background:#fef9c3; color:#854d0e; }
.b-ACTIVE { background:#dcfce7; color:#166534; }
.b-EXPIRED { background:#e5e7eb; color:#374151; }
.b-REVOKED { background:#fee2e2; color:#991b1b; }
.b-SUPERSEDED { background:#ede9fe; color:#5b21b6; }
.b-ALLOW { background:#dcfce7; color:#166534; }
.b-DENY { background:#fee2e2; color:#991b1b; }
.b-high,.b-critical { background:#fee2e2; color:#991b1b; }
.b-medium { background:#fef3c7; color:#92400e; }
.b-normal,.b-low { background:#e0f2fe; color:#075985; }
label { display:block; font-size:13px; color:#334155; margin:10px 0 3px; font-weight:600; }
input[type=text],input[type=datetime-local],select,textarea {
  width:100%; padding:7px 9px; border:1px solid var(--line); border-radius:6px; font:inherit; background:#fff; }
textarea { min-height:60px; resize:vertical; }
.row { display:flex; gap:14px; flex-wrap:wrap; }
.row > div { flex:1; min-width:200px; }
button,.btn { font:inherit; cursor:pointer; border:none; background:var(--brand); color:#fff; padding:7px 16px; border-radius:6px; text-decoration:none; display:inline-block; }
button:hover,.btn:hover { background:var(--brand-dark); }
.btn-secondary { background:#475569; }
.btn-danger { background:var(--bad); }
.btn-small { padding:3px 10px; font-size:12px; }
.flash { padding:9px 14px; border-radius:8px; margin-bottom:14px; }
.flash-ok { background:#dcfce7; color:#166534; border:1px solid #86efac; }
.flash-err { background:#fee2e2; color:#991b1b; border:1px solid #fca5a5; }
.muted { color:var(--muted); }
code,.mono { font-family:ui-monospace,SFMono-Regular,Menlo,monospace; font-size:12px; }
.fingerprint { word-break:break-all; background:#f1f5f9; padding:6px 9px; border-radius:6px; }
.disposition { border:1px solid var(--line); border-radius:8px; padding:10px 13px; margin:8px 0; }
.disposition.waived { border-color:#86efac; background:#f0fdf4; }
.disposition.enforced { border-color:#fca5a5; background:#fef2f2; }
.disposition.idle { border-color:var(--line); background:#fafafa; }
.chips span { display:inline-block; background:var(--chip); border-radius:6px; padding:1px 8px; margin:2px 3px 2px 0; font-size:12px; }
.warning-box { background:#fffbeb; border:1px solid #fcd34d; color:#92400e; padding:9px 13px; border-radius:8px; margin:8px 0; }
.inline-form { display:inline; }
.grid2 { display:grid; grid-template-columns:1fr 1fr; gap:14px; }
@media (max-width:800px){ .grid2{grid-template-columns:1fr} }
</style></head><body>
<header><h1>策略例外登记站</h1>
<nav>
${navLink("总览", "/", activeNav)}
${navLink("策略", "/policies", activeNav)}
${navLink("例外", "/exceptions", activeNav)}
${navLink("对象目录", "/directory", activeNav)}
${navLink("事实评估", "/decide", activeNav)}
${navLink("版本/审计链", "/audit", activeNav)}
${navLink("时钟", "/clock", activeNav)}
${navLink("导入导出", "/bundle", activeNav)}
</nav></header>
$clockBanner
<main>$flashHtml
$body
</main></body></html>"""
    }

    private fun navLink(label: String, href: String, active: String): String =
        """<a href="$href" class="${if (href == active) "active" else ""}">$label</a>"""
}
