package com.travel.agent.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.travel.agent.config.Config;
import com.travel.agent.core.Checkpoint;
import com.travel.agent.util.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Web 控制台（纯 JDK HttpServer）：运行历史总览、逐步完成进度、方案在线预览与文档下载。
 *
 * 用法：java -jar travel-agent.jar web [port]    # 默认 http://127.0.0.1:8765
 */
public final class WebConsole {

    private static final List<String> PIPELINE =
            List.of("nlu", "weather", "poi", "budget", "itinerary", "validate", "report");
    private static final Map<String, String> DESC = Map.of(
            "nlu", "需求解析", "weather", "天气查询", "poi", "景点检索(RAG)", "budget", "预算测算",
            "itinerary", "行程编排", "validate", "冲突校验", "report", "文档生成");

    private static final String CSS = """
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
            hr{border:none;border-top:1px solid #ddd}""";

    private static final String PAGE_INDEX = """
            <!doctype html><html><head><meta charset="utf-8"><title>旅行规划助手 · 控制台</title>
            <style>__CSS__</style></head><body>
            <h1>🧭 智能旅行规划助手 · Agent 控制台</h1>
            <p class="muted">每 2 秒自动刷新 · 运行任务：<code>java -jar target/travel-agent.jar "一句话需求"</code> · 断点续跑 <code>--resume</code></p>
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
            </script></body></html>""".replace("__CSS__", CSS);

    private static final Pattern H_RULE = Pattern.compile("^(#{1,3})\\s+(.*)$");
    private static final Pattern HR = Pattern.compile("-{3,}");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern LINK = Pattern.compile("\\[([^]]+)]\\(([^)]+)\\)");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|[\\s:|-]+\\|\\s*$");

    private WebConsole() {
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8765;
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", new Handler());
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("Web 控制台已启动：http://127.0.0.1:" + port + " （Ctrl+C 退出）");
    }

    // ---------------- Markdown → HTML（轻量渲染，够方案预览用） ----------------
    private static String inline(String text) {
        String t = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        t = BOLD.matcher(t).replaceAll("<strong>$1</strong>");
        t = LINK.matcher(t).replaceAll("<a href=\"$2\" target=\"_blank\">$1</a>");
        return t;
    }

    static String mdToHtml(String md) {
        List<String> out = new ArrayList<>();
        String[] lines = md.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].replaceAll("\\s+$", "");
            if (line.isBlank()) {
                i++;
                continue;
            }
            Matcher m = H_RULE.matcher(line);
            if (m.matches()) {
                int lvl = m.group(1).length() + 1;
                out.add("<h" + lvl + ">" + inline(m.group(2)) + "</h" + lvl + ">");
            } else if (HR.matcher(line.strip()).matches()) {
                out.add("<hr>");
            } else if (line.startsWith("> ")) {
                out.add("<blockquote>" + inline(line.substring(2)) + "</blockquote>");
            } else if (line.startsWith("- ")) {
                out.add("<ul>");
                while (i < lines.length && lines[i].startsWith("- ")) {
                    out.add("<li>" + inline(lines[i].substring(2)) + "</li>");
                    i++;
                }
                out.add("</ul>");
                continue;
            } else if (line.strip().startsWith("|") && i + 1 < lines.length
                    && TABLE_SEP.matcher(lines[i + 1]).matches()) {
                List<String[]> rows = new ArrayList<>();
                rows.add(splitRow(line));
                i += 2;
                while (i < lines.length && lines[i].strip().startsWith("|")) {
                    rows.add(splitRow(lines[i]));
                    i++;
                }
                StringBuilder tb = new StringBuilder("<table><thead><tr>");
                for (String c : rows.get(0)) {
                    tb.append("<th>").append(inline(c)).append("</th>");
                }
                tb.append("</tr></thead><tbody>");
                for (int r = 1; r < rows.size(); r++) {
                    tb.append("<tr>");
                    for (String c : rows.get(r)) {
                        tb.append("<td>").append(inline(c)).append("</td>");
                    }
                    tb.append("</tr>");
                }
                tb.append("</tbody></table>");
                out.add(tb.toString());
                continue;
            } else {
                out.add("<p>" + inline(line) + "</p>");
            }
            i++;
        }
        return String.join("\n", out);
    }

    private static String[] splitRow(String line) {
        String t = line.strip().replaceAll("^\\|", "").replaceAll("\\|$", "");
        String[] parts = t.split("\\|", -1);
        String[] out = new String[parts.length];
        for (int k = 0; k < parts.length; k++) {
            out[k] = parts[k].strip();
        }
        return out;
    }

    // ---------------- 数据 ----------------
    private static List<Map<String, Object>> loadRuns() {
        List<Map<String, Object>> runs = new ArrayList<>();
        if (!Files.isDirectory(Config.RUNS_DIR)) {
            return runs;
        }
        List<Path> dirs = new ArrayList<>();
        try (var ds = Files.newDirectoryStream(Config.RUNS_DIR)) {
            ds.forEach(dirs::add);
        } catch (IOException ignored) {
            return runs;
        }
        dirs.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));
        for (Path d : dirs) {
            if (!Files.isDirectory(d)) {
                continue;
            }
            try {
                Checkpoint ck = Checkpoint.load(d.getFileName().toString());
                List<String> done = ck.completed();
                Map<String, Object> report = ck.result("report");
                Map<String, Object> run = new LinkedHashMap<>();
                run.put("run_id", d.getFileName().toString());
                run.put("goal", ck.goal());
                run.put("created", ck.createdAt());
                List<String> steps = new ArrayList<>();
                for (String n : PIPELINE) {
                    if (done.contains(n)) {
                        steps.add(n);
                    }
                }
                run.put("steps", steps);
                run.put("done", done.size());
                run.put("report", report != null);
                run.put("docx", report != null && !Json.getStr(report, "docx_path").isEmpty());
                runs.add(run);
            } catch (Exception ignored) {
                // 损坏的运行记录跳过
            }
        }
        return runs;
    }

    private static String runPage(String runId) {
        Checkpoint ck;
        try {
            ck = Checkpoint.load(runId);
        } catch (Exception e) {
            return "运行记录不存在：" + runId;
        }
        List<String> done = ck.completed();
        Map<String, Object> report = ck.result("report");
        Map<String, Object> vd = ck.result("validate");

        StringBuilder steps = new StringBuilder();
        for (String n : PIPELINE) {
            steps.append("<li class=\"").append(done.contains(n) ? "done" : "todo").append("\">")
                    .append("<span class=\"dot\"></span>").append(n).append(" · ").append(DESC.get(n))
                    .append("</li>");
        }

        String checks;
        if (vd != null) {
            StringBuilder sb = new StringBuilder();
            for (Object f : Json.getList(vd, "fixes")) {
                sb.append("<div class=\"fix\">✅ ").append(inline(Json.str(f))).append("</div>");
            }
            for (Object s : Json.getList(vd, "issues")) {
                sb.append("<div class=\"warn\">⚠ ").append(inline(Json.str(s))).append("</div>");
            }
            checks = sb.isEmpty() ? "<div class=\"fix\">✅ 全部检查通过</div>" : sb.toString();
        } else {
            checks = "<p>（尚未执行到校验步骤）</p>";
        }

        String quality = "";
        Map<String, Object> q = vd == null ? null : Json.getMap(vd, "quality");
        if (q != null && !q.isEmpty()) {
            Map<String, Object> cov = Json.getMap(q, "coverage");
            Map<String, Object> rain = Json.getMap(q, "rain_safety");
            Map<String, Object> bf = Json.getMap(q, "budget_fit");
            quality = String.format("""
                    <h2>方案质量自评：%d / 100</h2><table><tr><th>维度</th><th>得分率</th><th>明细</th></tr>
                    <tr><td>日程覆盖(%d)</td><td>%d%%</td><td>%s</td></tr>
                    <tr><td>雨天安全(%d)</td><td>%d%%</td><td>%s</td></tr>
                    <tr><td>预算达成(%d)</td><td>%d%%</td><td>%s</td></tr></table>""",
                    Json.getInt(q, "score"),
                    Json.getInt(cov, "weight"), Json.getInt(cov, "value"), Json.getStr(cov, "detail"),
                    Json.getInt(rain, "weight"), Json.getInt(rain, "value"), Json.getStr(rain, "detail"),
                    Json.getInt(bf, "weight"), Json.getInt(bf, "value"), Json.getStr(bf, "detail"));
        }

        String mdHtml = "";
        String downloads = "";
        if (report != null) {
            try {
                mdHtml = mdToHtml(Files.readString(Path.of(Json.getStr(report, "path")), StandardCharsets.UTF_8));
            } catch (Exception e) {
                mdHtml = "<p>方案文件读取失败: " + e.getMessage() + "</p>";
            }
            downloads = "<p><a class=\"btn\" href=\"/file/" + runId + "/"
                    + enc(Json.getStr(report, "filename")) + "\">下载 Markdown</a>";
            String docxPath = Json.getStr(report, "docx_path");
            if (!docxPath.isEmpty() && Files.exists(Path.of(docxPath))) {
                String dname = enc(Path.of(docxPath).getFileName().toString());
                downloads += " <a class=\"btn\" href=\"/file/" + runId + "/" + dname + "\">下载 Word(docx)</a>";
            }
            downloads += "</p>";
        }

        return PAGE_RUN
                .replace("{{run_id}}", esc(runId))
                .replace("{{goal}}", inline(ck.goal()))
                .replace("{{created}}", esc(ck.createdAt()))
                .replace("{{done}}", String.valueOf(done.size()))
                .replace("{{steps}}", steps.toString())
                .replace("{{checks}}", checks)
                .replace("{{quality}}", quality)
                .replace("{{downloads}}", downloads)
                .replace("{{md}}", mdHtml.isEmpty()
                        ? "<p>（方案尚未生成，可运行 java -jar travel-agent.jar --resume " + runId + " 续跑）</p>"
                        : mdHtml);
    }

    private static final String PAGE_RUN = """
            <!doctype html><html><head><meta charset="utf-8"><title>{{run_id}} · 运行详情</title>
            <style>__CSS__</style></head><body>
            <p><a href="/">← 返回控制台</a></p>
            <h1>{{goal}}</h1>
            <p class="muted">run_id: <code>{{run_id}}</code> · 创建于 {{created}} · 进度 {{done}}/7</p>
            <h2>子任务执行链路</h2><ol>{{steps}}</ol>
            <h2>自检与自动修复</h2>{{checks}}
            {{quality}}
            {{downloads}}
            {{md}}
            </body></html>""".replace("__CSS__", CSS);

    // ---------------- HTTP 处理 ----------------
    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static final class Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            try {
                if (path.equals("/") || path.equals("/index.html")) {
                    send(ex, 200, PAGE_INDEX.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
                } else if (path.equals("/api/runs")) {
                    send(ex, 200, Json.MAPPER.writeValueAsString(loadRuns()).getBytes(StandardCharsets.UTF_8),
                            "application/json; charset=utf-8");
                } else if (path.startsWith("/run/")) {
                    String runId = URLDecoder.decode(path.substring("/run/".length()), StandardCharsets.UTF_8);
                    send(ex, 200, runPage(runId).getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
                } else if (path.startsWith("/file/")) {
                    file(ex, path.substring("/file/".length()));
                } else {
                    send(ex, 404, "not found".getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
                }
            } catch (Exception e) {
                send(ex, 500, ("server error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8),
                        "text/plain; charset=utf-8");
            }
        }

        private void file(HttpExchange ex, String rest) throws IOException {
            int idx = rest.indexOf('/');
            if (idx < 0) {
                send(ex, 404, "not found".getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
                return;
            }
            String runId = URLDecoder.decode(rest.substring(0, idx), StandardCharsets.UTF_8);
            String fname = URLDecoder.decode(rest.substring(idx + 1), StandardCharsets.UTF_8);
            Path base = Config.RUNS_DIR.resolve(runId).normalize();
            Path target = base.resolve(fname).normalize();
            if (!target.startsWith(base) || !Files.exists(target)) {
                send(ex, 404, "not found".getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
                return;
            }
            String ctype = switch (getExtension(target)) {
                case ".md" -> "text/markdown; charset=utf-8";
                case ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                default -> "application/octet-stream";
            };
            byte[] data = Files.readAllBytes(target);
            ex.getResponseHeaders().set("Content-Type", ctype);
            ex.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename*=UTF-8''" + enc(target.getFileName().toString()));
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
        }

        private static void send(HttpExchange ex, int code, byte[] body, String ctype) throws IOException {
            ex.getResponseHeaders().set("Content-Type", ctype);
            ex.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
            ex.sendResponseHeaders(code, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }

        private static String getExtension(Path p) {
            String name = p.getFileName().toString();
            int dot = name.lastIndexOf('.');
            return dot >= 0 ? name.substring(dot).toLowerCase() : "";
        }
    }
}
