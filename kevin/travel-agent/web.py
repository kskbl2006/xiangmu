# -*- coding: utf-8 -*-
"""Web 控制台（纯标准库）：运行历史总览、逐步完成进度、方案在线预览与文档下载。

用法：
  python web.py            # 默认 http://127.0.0.1:8765
  python web.py 9000       # 自定义端口

配合演示：另开一个终端运行 python main.py "..."，控制台页面每 2 秒自动刷新进度，
可以实时看到 Agent 从 1/7 走到 7/7 的过程（断点续跑时同样可见）。
"""
from __future__ import annotations

import json
import re
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import quote

from config import RUNS_DIR
from core.checkpoint import Checkpoint

PIPELINE = ["nlu", "weather", "poi", "budget", "itinerary", "validate", "report"]
DESC = {"nlu": "需求解析", "weather": "天气查询", "poi": "景点检索(RAG)", "budget": "预算测算",
        "itinerary": "行程编排", "validate": "冲突校验", "report": "文档生成"}


# ---------------- Markdown → HTML（轻量渲染，够方案预览用） ----------------
def _inline(text):
    text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    text = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r'<a href="\2" target="_blank">\1</a>', text)
    return text


def md_to_html(md: str) -> str:
    out, lines, i = [], md.splitlines(), 0
    while i < len(lines):
        line = lines[i].rstrip()
        if not line.strip():
            i += 1
            continue
        m = re.match(r"^(#{1,3})\s+(.*)", line)
        if m:
            out.append("<h%d>%s</h%d>" % (len(m.group(1)) + 1, _inline(m.group(2)), len(m.group(1)) + 1))
        elif re.fullmatch(r"-{3,}", line.strip()):
            out.append("<hr>")
        elif line.startswith("> "):
            out.append("<blockquote>%s</blockquote>" % _inline(line[2:]))
        elif line.startswith("- "):
            out.append("<ul>")
            while i < len(lines) and lines[i].startswith("- "):
                out.append("<li>%s</li>" % _inline(lines[i][2:]))
                i += 1
            out.append("</ul>")
            continue
        elif line.strip().startswith("|") and i + 1 < len(lines) and re.match(r"^\s*\|[\s:|-]+\|\s*$", lines[i + 1]):
            rows = [[c.strip() for c in line.strip().strip("|").split("|")]]
            i += 2
            while i < len(lines) and lines[i].strip().startswith("|"):
                rows.append([c.strip() for c in lines[i].strip().strip("|").split("|")])
                i += 1
            out.append("<table><thead><tr>" + "".join("<th>%s</th>" % _inline(c) for c in rows[0])
                       + "</tr></thead><tbody>")
            for r in rows[1:]:
                out.append("<tr>" + "".join("<td>%s</td>" % _inline(c) for c in r) + "</tr>")
            out.append("</tbody></table>")
            continue
        else:
            out.append("<p>%s</p>" % _inline(line))
        i += 1
    return "\n".join(out)


# ---------------- 数据 ----------------
def load_runs():
    runs = []
    if RUNS_DIR.exists():
        for d in sorted(RUNS_DIR.iterdir(), reverse=True):
            if not d.is_dir():
                continue
            try:
                ck = Checkpoint.load(d.name)
            except Exception:
                continue
            report = ck.result("report")
            runs.append({
                "run_id": d.name,
                "goal": ck.state["goal"],
                "created": ck.state["created_at"],
                "steps": [n for n in PIPELINE if n in ck.completed()],
                "done": len(ck.completed()),
                "report": bool(report),
                "docx": bool(report and report.get("docx_path")),
            })
    return runs


def _render(tpl: str, mapping: dict) -> str:
    """占位符逐个替换（避免 % 格式化被 CSS/正文中的 % 干扰）。"""
    for k, v in mapping.items():
        tpl = tpl.replace("%(" + k + ")s", str(v))
    return tpl


def run_page(run_id: str) -> str:
    ck = Checkpoint.load(run_id)
    done = set(ck.completed())
    report = ck.result("report")
    vd = ck.result("validate")

    steps = "".join('<li class="%s"><span class="dot"></span>%s · %s</li>'
                    % ("done" if n in done else "todo", n, DESC[n]) for n in PIPELINE)
    checks = ""
    if vd:
        for f in vd.get("fixes", []):
            checks += '<div class="fix">✅ %s</div>' % _inline(f)
        for s in vd.get("issues", []):
            checks += '<div class="warn">⚠ %s</div>' % _inline(s)
        if not vd.get("fixes") and not vd.get("issues"):
            checks = '<div class="fix">✅ 全部检查通过</div>'

    quality = ""
    q = (vd or {}).get("quality")
    if q:
        quality = ("<h2>方案质量自评：%d / 100</h2><table><tr><th>维度</th><th>得分率</th><th>明细</th></tr>"
                   "<tr><td>日程覆盖(%d)</td><td>%d%%</td><td>%s</td></tr>"
                   "<tr><td>雨天安全(%d)</td><td>%d%%</td><td>%s</td></tr>"
                   "<tr><td>预算达成(%d)</td><td>%d%%</td><td>%s</td></tr></table>"
                   % (q["score"], q["coverage"]["weight"], q["coverage"]["value"], q["coverage"]["detail"],
                      q["rain_safety"]["weight"], q["rain_safety"]["value"], q["rain_safety"]["detail"],
                      q["budget_fit"]["weight"], q["budget_fit"]["value"], q["budget_fit"]["detail"]))

    md_html, downloads = "", ""
    if report:
        try:
            md_html = md_to_html(open(report["path"], encoding="utf-8").read())
        except Exception as e:
            md_html = "<p>方案文件读取失败: %s</p>" % e
        downloads = '<p><a class="btn" href="/file/%s/%s">下载 Markdown</a>' % (
            run_id, quote(report["filename"]))
        if report.get("docx_path"):
            import os
            dname = quote(os.path.basename(report["docx_path"]))
            if os.path.exists(report["docx_path"]):
                downloads += ' <a class="btn" href="/file/%s/%s">下载 Word(docx)</a>' % (run_id, dname)
        downloads += "</p>"

    return _render(PAGE_RUN, {
        "run_id": run_id, "goal": _inline(ck.state["goal"]), "created": ck.state["created_at"],
        "done": len(done), "steps": steps, "checks": checks or "<p>（尚未执行到校验步骤）</p>",
        "quality": quality, "downloads": downloads,
        "md": md_html or "<p>（方案尚未生成，可运行 main.py --resume %s 续跑）</p>" % run_id,
    })


# ---------------- 页面模板 ----------------
CSS = """
body{font-family:"Microsoft YaHei",system-ui,sans-serif;max-width:960px;margin:24px auto;padding:0 16px;color:#222;background:#fafafa}
h1{font-size:22px} h2{font-size:17px;margin-top:24px;border-left:4px solid #2b6cb0;padding-left:8px}
table{border-collapse:collapse;width:100%;margin:10px 0;background:#fff}
th,td{border:1px solid #ddd;padding:6px 10px;font-size:14px;text-align:left}
th{background:#edf2f7}
code{background:#eee;padding:1px 5px;border-radius:3px}
ol{list-style:none;padding:0}
ol li{padding:6px 10px;border-left:3px solid #cbd5e0;margin:4px 0;background:#fff;color:#999}
ol li.done{border-color:#38a169;color:#222}
ol li.done .dot:before{content:"✔ "}
ol li.todo .dot:before{content:"… "}
.fix,.warn{padding:6px 10px;margin:4px 0;background:#fff;border-radius:4px}
.fix{border-left:3px solid #38a169} .warn{border-left:3px solid #d69e2e}
.btn{display:inline-block;padding:6px 14px;background:#2b6cb0;color:#fff;border-radius:4px;text-decoration:none;margin-right:8px}
.bar{height:8px;background:#e2e8f0;border-radius:4px;overflow:hidden;width:120px;display:inline-block;vertical-align:middle}
.bar>i{display:block;height:100%;background:#38a169}
.badge{font-size:12px;padding:2px 8px;border-radius:10px;margin-left:6px}
.b-ok{background:#c6f6d5;color:#22543d} .b-no{background:#fed7d7;color:#742a2a}
.muted{color:#888;font-size:13px}
blockquote{border-left:4px solid #a0aec0;margin:8px 0;padding:4px 12px;color:#555;background:#fff}
hr{border:none;border-top:1px solid #ddd}
"""

PAGE_INDEX = """<!doctype html><html><head><meta charset="utf-8"><title>旅行规划助手 · 控制台</title>
<style>__CSS__</style></head><body>
<h1>🧭 智能旅行规划助手 · Agent 控制台</h1>
<p class="muted">每 2 秒自动刷新 · 运行任务：<code>python main.py "一句话需求"</code> · 断点续跑 <code>--resume</code></p>
<table id="runs"><thead><tr><th>运行</th><th>进度</th><th>目标</th><th>产物</th></tr></thead><tbody></tbody></table>
<script>
function esc(s){return s.replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]))}
async function refresh(){
  const rs = await (await fetch('/api/runs')).json();
  document.querySelector('#runs tbody').innerHTML = rs.map(r =>
    `<tr><td><a href="/run/${r.run_id}">${r.run_id}</a><br><span class="muted">${r.created}</span></td>
     <td><span class="bar"><i style="width:${r.done/7*100}%"></i></span> ${r.done}/7</td>
     <td>${esc(r.goal)}</td>
     <td>${r.report?'<span class="badge b-ok">md</span>':''}${r.docx?'<span class="badge b-ok">docx</span>':''}</td></tr>`
  ).join('') || '<tr><td colspan="4" class="muted">暂无运行记录</td></tr>';
}
refresh(); setInterval(refresh, 2000);
</script></body></html>""".replace("__CSS__", CSS)

PAGE_RUN = """<!doctype html><html><head><meta charset="utf-8"><title>%(run_id)s · 运行详情</title>
<style>__CSS__</style></head><body>
<p><a href="/">← 返回控制台</a></p>
<h1>%(goal)s</h1>
<p class="muted">run_id: <code>%(run_id)s</code> · 创建于 %(created)s · 进度 %(done)s/7</p>
<h2>子任务执行链路</h2><ol>%(steps)s</ol>
<h2>自检与自动修复</h2>%(checks)s
%(quality)s
%(downloads)s
%(md)s
</body></html>""".replace("__CSS__", CSS)


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="text/html; charset=utf-8"):
        data = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = self.path.split("?")[0]
        try:
            if path in ("/", "/index.html"):
                self._send(200, PAGE_INDEX)
            elif path == "/api/runs":
                self._send(200, json.dumps(load_runs(), ensure_ascii=False),
                           "application/json; charset=utf-8")
            elif path.startswith("/run/"):
                self._send(200, run_page(path[len("/run/"):]))
            elif path.startswith("/file/"):
                self._file(path[len("/file/"):])
            else:
                self._send(404, "not found")
        except FileNotFoundError:
            self._send(404, "not found")
        except Exception as e:  # noqa: BLE001
            self._send(500, "server error: %s" % e)

    def _file(self, rest):
        run_id, fname = rest.split("/", 1)
        base = (RUNS_DIR / run_id).resolve()
        target = (base / fname).resolve()
        if not str(target).startswith(str(base)) or not target.exists():
            self._send(404, "not found")
            return
        ctype = {".md": "text/markdown; charset=utf-8",
                 ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                 }.get(target.suffix.lower(), "application/octet-stream")
        data = target.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Disposition",
                         "attachment; filename*=UTF-8''" + quote(target.name))
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):  # 静默访问日志
        pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    if hasattr(sys.stdout, "reconfigure"):
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
    print("Web 控制台已启动：http://127.0.0.1:%d （Ctrl+C 退出）" % port)
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
