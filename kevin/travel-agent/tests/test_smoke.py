# -*- coding: utf-8 -*-
"""冒烟测试：NLU 解析 / RAG 检索 / 预算测算 / 端到端闭环 / 断点续跑。

运行：python tests/test_smoke.py
"""
from __future__ import annotations

import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from config import RUNS_DIR  # noqa: E402
from core.agent import TravelAgent  # noqa: E402
from core.nlu import is_travel_intent, parse_request  # noqa: E402
from rag.kb import relevance  # noqa: E402
from tools.budget import BudgetTool  # noqa: E402
from tools.poi import load_kb  # noqa: E402

GOAL = "北京出发去三亚5天，预算5000元，2大1小"


class TestNLU(unittest.TestCase):
    def test_parse(self):
        req, _ = parse_request(GOAL)
        self.assertEqual(req["destination"], "三亚")
        self.assertEqual(req["depart_city"], "北京")
        self.assertEqual(req["days"], 5)
        self.assertEqual(req["budget"], 5000)
        self.assertEqual(req["adults"], 2)
        self.assertEqual(req["children"], 1)
        self.assertEqual(req["people"], 3)

    def test_intent(self):
        self.assertTrue(is_travel_intent("帮我规划一个上海三日游"))
        self.assertFalse(is_travel_intent("今天股票行情怎么样"))


class TestRAG(unittest.TestCase):
    def test_kb_load(self):
        kb = load_kb("三亚")
        self.assertIsNotNone(kb)
        self.assertGreaterEqual(len(kb["attractions"]), 8)
        self.assertEqual(len(kb["hotels"]), 3)

    def test_kb_new_cities(self):
        for city in ("西安", "重庆", "广州", "南京", "苏州"):
            kb = load_kb(city)
            self.assertIsNotNone(kb, city)
            self.assertGreaterEqual(len(kb["attractions"]), 8, city)

    def test_relevance(self):
        self.assertGreater(relevance("海滩 游泳 潜水", "亚龙湾 海滩 沙质 水质清澈 游泳"),
                           relevance("海滩 游泳 潜水", "博物馆 历史文化 展览"))


class TestBudget(unittest.TestCase):
    def test_budget(self):
        out = BudgetTool().run({
            "request": {"people": 3, "days": 5, "budget": 5000,
                        "depart_city": "北京", "destination": "三亚"},
            "hotels": [{"name": "A", "tier": "经济", "rating": 4.3, "price": 220, "area": "x", "desc": ""},
                       {"name": "B", "tier": "舒适", "rating": 4.5, "price": 560, "area": "x", "desc": ""},
                       {"name": "C", "tier": "豪华", "rating": 4.7, "price": 980, "area": "x", "desc": ""}],
            "foods": [{"name": "f", "type": "t", "rating": 4.5, "avg_cost": 100, "area": "x"}],
            "attractions": [{"name": "a", "ticket": "100元"}],
        })
        self.assertEqual(out["nights"], 4)
        self.assertEqual(out["hotel"]["per_night"], 560)   # 中档
        self.assertEqual(out["food"]["per_person_day"], 200)
        self.assertGreater(out["planned_total"], 5000)     # 北京-三亚 5天3人必然超预算
        self.assertTrue(out["warnings"])


class TestDocx(unittest.TestCase):
    def test_md_to_docx(self):
        import tempfile
        import zipfile
        from xml.dom import minidom
        from tools.docx_export import md_to_docx

        md = "# 测试标题\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n- 列表项\n\n> 引用文字\n\n普通段落 **加粗** 内容\n"
        out = os.path.join(tempfile.gettempdir(), "travel_agent_test.docx")
        md_to_docx(md, out)
        try:
            with zipfile.ZipFile(out) as z:
                names = z.namelist()
                self.assertIn("word/document.xml", names)
                self.assertIn("word/styles.xml", names)
                xml = z.read("word/document.xml").decode("utf-8")
            minidom.parseString(xml)               # XML 结构合法
            self.assertIn("测试标题", xml)
            self.assertIn("列表项", xml)
            self.assertIn("引用文字", xml)
            self.assertIn("加粗", xml)
            self.assertNotIn("**", xml)             # 加粗标记已剥离
            self.assertIn("<w:tbl>", xml)           # 表格已生成
        finally:
            if os.path.exists(out):
                os.remove(out)


class TestEndToEnd(unittest.TestCase):
    def test_full_run(self):
        agent = TravelAgent(GOAL, run_id="test_full")
        path = agent.run()
        self.assertTrue(path and os.path.exists(path))
        report = agent.ckpt.result("report")
        self.assertTrue(report.get("docx_path") and os.path.exists(report["docx_path"]))
        with open(path, encoding="utf-8") as f:
            content = f.read()
        for section in ("需求概览", "出行期间天气", "每日行程", "预算明细", "自检与自动修复",
                        "质量自评", "注意事项", "运行统计"):
            self.assertIn(section, content)
        self.assertIn("导航](https://", content)           # 景点带地图导航链接
        self.assertIn("三亚", content)
        for task in ("nlu", "weather", "poi", "budget", "itinerary", "validate", "report"):
            self.assertIn(task, agent.executed)
        # 预算紧张场景应触发自动修复或风险提示
        vd = agent.ckpt.result("validate")
        self.assertTrue(vd["fixes"] or vd["issues"])

    def test_resume(self):
        a1 = TravelAgent(GOAL, run_id="test_resume")
        p1 = a1.run(max_steps=3)
        self.assertIsNone(p1)                              # 3 步后暂停，未产出方案
        self.assertEqual(sorted(a1.executed), ["nlu", "poi", "weather"])

        a2 = TravelAgent("", run_id="test_resume")
        p2 = a2.run(resume=True)
        self.assertTrue(p2 and os.path.exists(p2))         # 续跑补完剩余步骤
        for task in ("nlu", "weather", "poi"):             # 已完成步骤未重复执行
            self.assertNotIn(task, a2.executed)
        for task in ("budget", "itinerary", "validate", "report"):
            self.assertIn(task, a2.executed)


if __name__ == "__main__":
    unittest.main(verbosity=2)
