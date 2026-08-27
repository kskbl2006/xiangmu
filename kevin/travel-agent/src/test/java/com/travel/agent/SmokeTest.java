package com.travel.agent;

import com.travel.agent.core.TravelAgent;
import com.travel.agent.core.Nlu;
import com.travel.agent.rag.Kb;
import com.travel.agent.tools.BudgetTool;
import com.travel.agent.tools.DocxExport;
import com.travel.agent.tools.PoiTool;
import com.travel.agent.util.Json;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 冒烟测试：NLU 解析 / RAG 检索 / 预算测算 / docx 导出 / 端到端闭环 / 断点续跑。
 * 运行：mvn test
 */
class SmokeTest {

    private static final String GOAL = "北京出发去三亚5天，预算5000元，2大1小";

    @Test
    void testParse() {
        Map<String, Object> req = Nlu.parseRequest(GOAL).request();
        assertEquals("三亚", Json.getStr(req, "destination"));
        assertEquals("北京", Json.getStr(req, "depart_city"));
        assertEquals(5, Json.getInt(req, "days"));
        assertEquals(5000, Json.getInt(req, "budget"));
        assertEquals(2, Json.getInt(req, "adults"));
        assertEquals(1, Json.getInt(req, "children"));
        assertEquals(3, Json.getInt(req, "people"));
    }

    @Test
    void testIntent() {
        assertTrue(Nlu.isTravelIntent("帮我规划一个上海三日游"));
        assertFalse(Nlu.isTravelIntent("今天股票行情怎么样"));
    }

    @Test
    void testKbLoad() {
        Map<String, Object> kb = PoiTool.loadKb("三亚");
        assertNotNull(kb);
        assertTrue(Json.getMaps(kb, "attractions").size() >= 8);
        assertEquals(3, Json.getMaps(kb, "hotels").size());
    }

    @Test
    void testKbNewCities() {
        for (String city : List.of("西安", "重庆", "广州", "南京", "苏州")) {
            Map<String, Object> kb = PoiTool.loadKb(city);
            assertNotNull(kb, city + " 知识库缺失");
            assertTrue(Json.getMaps(kb, "attractions").size() >= 8, city + " 景点不足 8 个");
        }
    }

    @Test
    void testRelevance() {
        assertTrue(Kb.relevance("海滩 游泳 潜水", "亚龙湾 海滩 沙质 水质清澈 游泳")
                > Kb.relevance("海滩 游泳 潜水", "博物馆 历史文化 展览"));
    }

    @Test
    void testBudget() throws Exception {
        Map<String, Object> out = new BudgetTool().run(Json.obj(
                "request", Json.obj("people", 3, "days", 5, "budget", 5000,
                        "depart_city", "北京", "destination", "三亚"),
                "hotels", Json.arr(
                        Json.obj("name", "A", "tier", "经济", "rating", 4.3, "price", 220, "area", "x", "desc", ""),
                        Json.obj("name", "B", "tier", "舒适", "rating", 4.5, "price", 560, "area", "x", "desc", ""),
                        Json.obj("name", "C", "tier", "豪华", "rating", 4.7, "price", 980, "area", "x", "desc", "")),
                "foods", Json.arr(Json.obj("name", "f", "type", "t", "rating", 4.5, "avg_cost", 100, "area", "x")),
                "attractions", Json.arr(Json.obj("name", "a", "ticket", "100元"))));
        assertEquals(4, Json.getInt(out, "nights"));
        assertEquals(560, Json.getInt(Json.getMap(out, "hotel"), "per_night"));   // 中档
        assertEquals(200, Json.getInt(Json.getMap(out, "food"), "per_person_day"));
        assertTrue(Json.getInt(out, "planned_total") > 5000);     // 北京-三亚 5天3人必然超预算
        assertFalse(Json.getList(out, "warnings").isEmpty());
    }

    @Test
    void testMdToDocx() throws Exception {
        String md = "# 测试标题\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n- 列表项\n\n> 引用文字\n\n普通段落 **加粗** 内容\n";
        Path out = Path.of(System.getProperty("java.io.tmpdir"), "travel_agent_test.docx");
        DocxExport.mdToDocx(md, out.toString());
        try (ZipFile z = new ZipFile(out.toFile())) {
            assertNotNull(z.getEntry("word/document.xml"));
            assertNotNull(z.getEntry("word/styles.xml"));
            byte[] xmlBytes = z.getInputStream(z.getEntry("word/document.xml")).readAllBytes();
            String xml = new String(xmlBytes, java.nio.charset.StandardCharsets.UTF_8);
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(xmlBytes));   // XML 结构合法（不抛异常即通过）
            assertTrue(xml.contains("测试标题"));
            assertTrue(xml.contains("列表项"));
            assertTrue(xml.contains("引用文字"));
            assertTrue(xml.contains("加粗"));
            assertFalse(xml.contains("**"));             // 加粗标记已剥离
            assertTrue(xml.contains("<w:tbl>"));           // 表格已生成
        } finally {
            Files.deleteIfExists(out);
        }
    }

    @Test
    void testFullRun() throws Exception {
        TravelAgent agent = new TravelAgent(GOAL, "test_full");
        String path = agent.run();
        assertNotNull(path);
        assertTrue(Files.exists(Path.of(path)));
        String content = Files.readString(Path.of(path), java.nio.charset.StandardCharsets.UTF_8);
        for (String section : List.of("需求概览", "出行期间天气", "每日行程", "预算明细",
                "自检与自动修复", "质量自评", "注意事项", "运行统计")) {
            assertTrue(content.contains(section), "缺少章节：" + section);
        }
        assertTrue(content.contains("导航](https://"));           // 景点带地图导航链接
        assertTrue(content.contains("三亚"));
        for (String task : List.of("nlu", "weather", "poi", "budget", "itinerary", "validate", "report")) {
            assertTrue(agent.executed.contains(task), "未执行：" + task);
        }
        // 预算紧张场景应触发自动修复或风险提示
        Map<String, Object> vd = agent.ckpt.result("validate");
        assertFalse(Json.getList(vd, "fixes").isEmpty() && Json.getList(vd, "issues").isEmpty(),
                "预算紧张场景应触发修复或风险提示");
        Map<String, Object> report = agent.ckpt.result("report");
        String docxPath = Json.getStr(report, "docx_path");
        assertFalse(docxPath.isEmpty(), "应同步导出 docx");
        assertTrue(Files.exists(Path.of(docxPath)), "docx 文件应存在");
    }

    @Test
    void testResume() throws Exception {
        TravelAgent a1 = new TravelAgent(GOAL, "test_resume");
        String p1 = a1.run(false, 3, null, false);
        assertNull(p1);                              // 3 步后暂停，未产出方案
        assertEquals(List.of("nlu", "poi", "weather"), sorted(a1.executed));

        TravelAgent a2 = new TravelAgent("", "test_resume");
        String p2 = a2.run(true, null, null, false);
        assertNotNull(p2);
        assertTrue(Files.exists(Path.of(p2)));        // 续跑补完剩余步骤
        for (String task : List.of("nlu", "weather", "poi")) {   // 已完成步骤未重复执行
            assertFalse(a2.executed.contains(task), "续跑不应重跑已完成步骤：" + task);
        }
        for (String task : List.of("budget", "itinerary", "validate", "report")) {
            assertTrue(a2.executed.contains(task), "续跑应补完：" + task);
        }
    }

    private static List<String> sorted(List<String> in) {
        List<String> out = new java.util.ArrayList<>(in);
        java.util.Collections.sort(out);
        return out;
    }
}
