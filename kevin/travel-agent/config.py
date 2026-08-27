# -*- coding: utf-8 -*-
"""全局配置：路径、缓存 TTL、LLM 接入、执行器参数。"""
from __future__ import annotations

import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
WORKSPACE = BASE_DIR / "workspace"
RUNS_DIR = WORKSPACE / "runs"
CACHE_DIR = WORKSPACE / "cache"
KNOWLEDGE_DIR = BASE_DIR / "knowledge"

for _d in (WORKSPACE, RUNS_DIR, CACHE_DIR):
    _d.mkdir(parents=True, exist_ok=True)

# ---- LLM 接入（OpenAI 兼容接口，如 GLM / DeepSeek / 通义）----
# 设置环境变量后自动启用真实 LLM；未设置时使用内置 MockLLM，保证离线跑通完整闭环。
LLM_BASE_URL = os.getenv("LLM_BASE_URL", "")   # 例: https://open.bigmodel.cn/api/paas/v4
LLM_API_KEY = os.getenv("LLM_API_KEY", "")
LLM_MODEL = os.getenv("LLM_MODEL", "glm-4-flash")
LLM_TIMEOUT = int(os.getenv("LLM_TIMEOUT", "20"))

# ---- 缓存 TTL（秒）：命中缓存 = 0 token、0 网络等待 ----
WEATHER_CACHE_TTL = 3 * 3600       # 天气 3 小时
POI_CACHE_TTL = 7 * 24 * 3600      # 景点/美食/酒店 7 天

# ---- 执行器 ----
TOOL_RETRY = 2        # 工具失败重试次数
TOOL_TIMEOUT = 8      # 网络类工具超时（秒）
MAX_WORKERS = 4       # 并行子任务线程数

# ---- Token 压缩 ----
MAX_POI_IN_ITINERARY_STEP = 12   # 进入行程编排环节的候选景点上限（截断省 token）
