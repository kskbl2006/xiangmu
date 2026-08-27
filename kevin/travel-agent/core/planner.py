# -*- coding: utf-8 -*-
"""任务规划层：把结构化需求固化为子任务 DAG（与架构图一致）。

链路：nlu（先行完成）→ [weather ∥ poi]（并行）→ budget → itinerary → validate → report

依赖关系：
  weather / poi   ：仅依赖 nlu 结果，二者无相互依赖 → 并行执行（加速响应）
  budget          ：依赖 weather + poi（按酒店档次和餐饮均价测算）
  itinerary       ：依赖 budget（按预算控制门票支出）+ weather（雨天改室内）
  validate        ：依赖 itinerary（冲突检查与自动修复）
  report          ：依赖 validate（渲染最终方案文档）
"""
from __future__ import annotations

from typing import List


def build_dag(request: dict) -> List[dict]:
    return [
        {"name": "weather", "deps": [], "desc": "查询目的地逐日天气（真实API+Mock兜底，磁盘缓存3h）"},
        {"name": "poi", "deps": [], "desc": "RAG 检索景点/美食/酒店（本地知识库，缓存7天）"},
        {"name": "budget", "deps": ["weather", "poi"], "desc": "预算分配与测算（交通/住宿/餐饮/门票）"},
        {"name": "itinerary", "deps": ["budget"], "desc": "逐日行程编排（天气感知：雨天优先室内）"},
        {"name": "validate", "deps": ["itinerary"], "desc": "冲突校验与自动修复（预算超支/雨天户外）"},
        {"name": "report", "deps": ["validate"], "desc": "渲染结构化方案文档（Markdown）"},
    ]
