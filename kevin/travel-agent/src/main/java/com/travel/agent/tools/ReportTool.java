package com.travel.agent.tools;

import com.travel.agent.config.Config;
import com.travel.agent.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 输出层·文档生成 Skill：把全链路结果渲染为结构化《旅行方案》Markdown。 */
public final class ReportTool implements Tool {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Override
    public String name() {
        return "report";
    }

    @Override
    public Map<String, Object> run(Map<String, Object> params) {
        Map<String, Object> req = Json.getMap(params, "request");
        Map<String, Object> r = Json.getMap(params, "results");
        Map<String, Object> weather = Json.getMap(r, "weather");
        Map<String, Object> poi = Json.getMap(r, "poi");
        Map<String, Object> budget = Json.getMap(r, "budget");
        Map<String, Object> vd = Json.getMap(r, "validate");
        Map<String, Object> itin = Json.getMap(vd, "itinerary");
        Map<String, Object> stats = Json.getMap(params, "stats");

        List<String> L = new ArrayList<>();
        L.add(String.format("# %s%d日旅行方案", Json.getStr(req, "destination"), Json.getInt(req, "days")));
        L.add("");
        L.add(String.format("> 由智能旅行规划助手 Agent 生成 · run_id: `%s` · %s · 数据源：天气=%s / 景点=%s",
                Json.getStr(stats, "run_id"), LocalDateTime.now().format(FMT),
                Json.getStr(weather, "source"), Json.getStr(poi, "source")));
        L.add("");

        // 一、需求概览
        L.add("## 一、需求概览");
        L.add("");
        List<String> prefStr = new ArrayList<>();
        for (Object p : Json.getList(req, "preferences")) {
            prefStr.add(Json.str(p));
        }
        List<Object[]> rows = new ArrayList<>(List.of(
                new Object[]{"目的地", Json.getStr(req, "destination")},
                new Object[]{"出发地", Json.getStr(req, "depart_city")},
                new Object[]{"出行日期", String.format("%s 起共 %d 天", Json.getStr(req, "start_date"), Json.getInt(req, "days"))},
                new Object[]{"人数", String.format("%d大%d小（共%d人）", Json.getInt(req, "adults"), Json.getInt(req, "children"), Json.getInt(req, "people"))},
                new Object[]{"总预算", Json.getInt(req, "budget") + " 元"},
                new Object[]{"偏好", prefStr.isEmpty() ? "未指定" : String.join("、", prefStr)}));
        List<String> notes = new ArrayList<>();
        for (Object n : Json.getList(req, "notes")) {
            notes.add(Json.str(n));
        }
        if (!notes.isEmpty()) {
            rows.add(new Object[]{"参数补全", String.join("；", notes)});
        }
        L.add(table(List.of("项目", "内容"), rows));
        L.add("");

        // 二、天气
        L.add("## 二、出行期间天气");
        L.add("");
        List<Object[]> wRows = new ArrayList<>();
        for (Object d : Json.getList(weather, "days")) {
            Map<String, Object> dd = (Map<String, Object>) d;
            wRows.add(new Object[]{Json.getStr(dd, "date"), Json.getStr(dd, "weekday"), Json.getStr(dd, "cond"),
                    String.format("%d~%d℃", Json.getInt(dd, "temp_lo"), Json.getInt(dd, "temp_hi")),
                    Json.getInt(dd, "rain_prob") + "%", Json.getStr(dd, "tip")});
        }
        L.add(table(List.of("日期", "星期", "天气", "气温", "降雨概率", "提示"), wRows));
        L.add("");

        // 三、每日行程
        Map<String, Object> vHotel = Json.getMap(vd, "hotel");
        L.add(String.format("## 三、每日行程（住宿：%s，%d元/晚 × %d晚）",
                Json.getStr(vHotel, "name"), Json.getInt(vHotel, "per_night"), Json.getInt(budget, "nights")));
        L.add("");
        for (Object dObj : Json.getList(itin, "days")) {
            Map<String, Object> d = (Map<String, Object>) dObj;
            L.add(String.format("### D%d %s（%s）%s %s", Json.getInt(d, "index"), Json.getStr(d, "date"),
                    Json.getStr(d, "weekday"), Json.getStr(d, "cond"), Json.getStr(d, "temp")));
            L.add("");
            List<Object[]> iRows = new ArrayList<>();
            for (Object itObj : Json.getList(d, "items")) {
                Map<String, Object> it = (Map<String, Object>) itObj;
                int cost = Json.getInt(it, "cost");
                String note = Json.getStr(it, "note");
                if ("景点".equals(Json.getStr(it, "kind"))) {
                    note += " [导航](" + mapLink(Json.getStr(req, "destination"), Json.getStr(it, "name")) + ")";
                }
                iRows.add(new Object[]{Json.getStr(it, "time"), Json.getStr(it, "name"), Json.getStr(it, "type"),
                        cost != 0 ? cost + "元" : "免费", note});
            }
            L.add(table(List.of("时间", "安排", "类型", "费用(全队)", "说明"), iRows));
            L.add("");
            if (!Json.getStr(d, "tip").isEmpty()) {
                L.add("> 当日提示：" + Json.getStr(d, "tip"));
                L.add("");
            }
        }

        // 四、预算
        L.add(String.format("## 四、预算明细（%d人%d天）", Json.getInt(req, "people"), Json.getInt(req, "days")));
        L.add("");
        Map<String, Object> transport = Json.getMap(budget, "transport");
        Map<String, Object> food = Json.getMap(budget, "food");
        List<Object[]> bRows = new ArrayList<>(List.of(
                new Object[]{"交通", String.format("%s⇄%s 约%dkm，%d元/人往返", Json.getStr(req, "depart_city"),
                        Json.getStr(req, "destination"), Json.getInt(transport, "dist_km"), Json.getInt(transport, "per_person")),
                        Json.getInt(transport, "total")},
                new Object[]{"住宿", String.format("%s（%s）%d元/晚×%d晚", Json.getStr(vHotel, "name"),
                        Json.getStr(vHotel, "tier"), Json.getInt(vHotel, "per_night"), Json.getInt(budget, "nights")),
                        Json.getInt(vHotel, "total")},
                new Object[]{"餐饮", String.format("约%d元/人/天（2正餐）", Json.getInt(food, "per_person_day")), Json.getInt(food, "total")},
                new Object[]{"门票", "按实际编排核算", Json.getInt(itin, "tickets_total")}));
        int actual = Json.getInt(vd, "actual_total");
        bRows.add(new Object[]{"合计", "交通+住宿+餐饮+门票", actual});
        L.add(table(List.of("项目", "明细", "金额(元)"), bRows));
        L.add("");
        if (actual <= Json.getInt(req, "budget")) {
            L.add(String.format("**预算结论**：合计 %d 元 ≤ 预算 %d 元，余量 %d 元（建议保留 10%% 机动金）。",
                    actual, Json.getInt(req, "budget"), Json.getInt(req, "budget") - actual));
        } else {
            L.add(String.format("**预算结论**：⚠ 合计 %d 元超出预算 %d 元，建议追加预算或缩减行程。",
                    actual, Json.getInt(req, "budget")));
        }
        L.add("");

        // 五、校验与修复
        L.add("## 五、Agent 自检与自动修复记录");
        L.add("");
        for (Object f : Json.getList(vd, "fixes")) {
            L.add("- ✅ " + Json.str(f));
        }
        for (Object i : Json.getList(vd, "issues")) {
            L.add("- ⚠ " + Json.str(i));
        }
        if (Json.getList(vd, "fixes").isEmpty() && Json.getList(vd, "issues").isEmpty()) {
            L.add("- ✅ 全部检查通过：日程完整、无雨天户外冲突、预算可控");
        }
        L.add("");

        // 六、质量自评
        Map<String, Object> q = Json.getMap(vd, "quality");
        if (q != null && !q.isEmpty()) {
            L.add("## 六、方案质量自评（Agent 自评）");
            L.add("");
            L.add(String.format("**总分：%d / 100**", Json.getInt(q, "score")));
            L.add("");
            Map<String, Object> cov = Json.getMap(q, "coverage");
            Map<String, Object> rain = Json.getMap(q, "rain_safety");
            Map<String, Object> bf = Json.getMap(q, "budget_fit");
            L.add(table(List.of("维度", "得分率", "权重", "明细"), List.of(
                    new Object[]{"日程覆盖", Json.getInt(cov, "value") + "%", String.valueOf(Json.getInt(cov, "weight")), Json.getStr(cov, "detail")},
                    new Object[]{"雨天安全", Json.getInt(rain, "value") + "%", String.valueOf(Json.getInt(rain, "weight")), Json.getStr(rain, "detail")},
                    new Object[]{"预算达成", Json.getInt(bf, "value") + "%", String.valueOf(Json.getInt(bf, "weight")), Json.getStr(bf, "detail")})));
            L.add("");
        }

        // 七、注意事项
        L.add("## 七、注意事项");
        L.add("");
        for (Object t : Json.getList(poi, "tips")) {
            L.add("- " + Json.str(t));
        }
        L.add("- 出行前请再次核对天气与票务信息；儿童门票、景区预约政策以官方为准。");
        L.add("");

        // 八、运行统计
        Map<String, Double> sec = new TreeMap<>();
        Map<String, Object> secRaw = Json.getMap(stats, "task_seconds");
        for (Map.Entry<String, Object> e : secRaw.entrySet()) {
            sec.put(e.getKey(), Json.dblVal(e.getValue()));
        }
        List<Object[]> sRows = new ArrayList<>();
        if (sec.isEmpty()) {
            sRows.add(new Object[]{"-", "-"});
        } else {
            for (Map.Entry<String, Double> e : sec.entrySet()) {
                sRows.add(new Object[]{e.getKey(), String.format("%.2f", e.getValue())});
            }
        }
        L.add("## 八、运行统计（Agent 过程可观测）");
        L.add("");
        L.add(table(List.of("子任务", "耗时(s)"), sRows));
        L.add("");
        List<String> executed = new ArrayList<>();
        for (Object x : Json.getList(stats, "executed")) {
            executed.add(Json.str(x));
        }
        executed.add("report");
        L.add(String.format("执行记录：%s；%s", String.join(" → ", executed),
                Json.getStr(stats, "token_report").replace("\n", " ")));
        L.add("");

        String fname = String.format("旅行方案_%s%d日_%s.md",
                Json.getStr(req, "destination"), Json.getInt(req, "days"), Json.getStr(stats, "run_id"));
        Path path = Config.RUNS_DIR.resolve(Json.getStr(stats, "run_id")).resolve(fname);
        try {
            Files.writeString(path, String.join("\n", L), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("方案文档写入失败: " + e.getMessage(), e);
        }
        // 同步导出 docx（纯 JDK 实现，本地渲染 0 token；失败不影响 md 交付）
        String docxPath = "";
        try {
            docxPath = path.resolveSibling(fname.replace(".md", ".docx")).toString();
            DocxExport.mdToDocx(String.join("\n", L), docxPath);
        } catch (Exception e) {
            docxPath = "";
            L.add("<!-- docx 导出失败: " + e.getMessage() + " -->");
            try {
                Files.writeString(path, String.join("\n", L), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // md 已落盘，忽略重写失败
            }
        }
        return Json.obj("path", path.toString(), "filename", fname, "docx_path", docxPath);
    }

    private static String mapLink(String dest, String name) {
        return "https://uri.amap.com/search?keyword=" + urlEncode(dest + " " + name);
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String table(List<String> headers, List<Object[]> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
        sb.append("|").append("---|".repeat(headers.size())).append("\n");
        for (Object[] row : rows) {
            List<String> cells = new ArrayList<>();
            for (Object c : row) {
                cells.add(Json.str(c));
            }
            sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
        }
        return sb.toString().strip();
    }
}
