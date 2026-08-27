# -*- coding: utf-8 -*-
"""LLM 引擎：真实 API（OpenAI 兼容）+ MockLLM 兜底 + Token 记账。

- 未配置 LLM_API_KEY / LLM_BASE_URL 时自动降级 MockLLM，任何环境都能跑通闭环；
- 所有调用经 TokenMeter 记账，运行结束输出 token 报告，用于演示"减少 token 消耗"的优化效果。
"""
from __future__ import annotations

import json
import re
import urllib.request
from typing import Optional

from config import LLM_API_KEY, LLM_BASE_URL, LLM_MODEL, LLM_TIMEOUT


def est_tokens(text: str) -> int:
    """粗略估算 token：中文约 1 字/token，其他字符约 4 字符/token。"""
    if not text:
        return 0
    cn = len(re.findall(r"[\u4e00-\u9fff]", text))
    return cn + (len(text) - cn) // 4


class TokenMeter(object):
    """全局 token 记账器（LLM 消耗 + 工具缓存节省估算）。"""

    def __init__(self):
        self.calls = 0
        self.prompt_tokens = 0
        self.completion_tokens = 0
        self.cache_hits = 0
        self.saved_tokens = 0

    def record(self, prompt: str, completion: str):
        self.calls += 1
        self.prompt_tokens += est_tokens(prompt)
        self.completion_tokens += est_tokens(completion)

    def report(self) -> str:
        total = self.prompt_tokens + self.completion_tokens
        lines = ["[TOKEN] LLM 调用 %d 次，消耗约 %d tokens（prompt %d + completion %d）"
                 % (self.calls, total, self.prompt_tokens, self.completion_tokens)]
        if self.cache_hits:
            lines.append("[TOKEN] 工具缓存命中 %d 次，估算节省约 %d tokens 及对应网络等待"
                         % (self.cache_hits, self.saved_tokens))
        if self.calls == 0 and self.cache_hits == 0:
            lines.append("[TOKEN] 本次运行 0 次 LLM 调用（规则模板 + 知识库检索完全命中，token 消耗为 0）")
        return "\n".join(lines)


TOKEN_METER = TokenMeter()


class MockLLM(object):
    """离线兜底：规则式最小应答，接口与真实 LLM 一致。

    主链路（解析/预算/行程/校验/报告）均为规则模板 + 知识库检索，
    只有意图识别等轻量判断走 LLM，Mock 模式下按关键词规则应答。
    """

    def complete(self, prompt: str, temperature: float = 0.3) -> str:
        TOKEN_METER.record(prompt, "是")
        # 只对"用户输入："之后的原文做关键词判断，避免把提示词模板本身误判为旅行意图
        text = prompt.split("用户输入：")[-1] if "用户输入：" in prompt else prompt
        if any(kw in text for kw in ("旅行", "旅游", "行程", "攻略", "游", "出发", "度假", "玩")):
            return "是"
        return "否"


class RealLLM(object):
    def __init__(self):
        self.url = LLM_BASE_URL.rstrip("/") + "/chat/completions"

    def complete(self, prompt: str, temperature: float = 0.3) -> str:
        body = json.dumps({
            "model": LLM_MODEL,
            "messages": [{"role": "user", "content": prompt}],
            "temperature": temperature,
        }).encode("utf-8")
        req = urllib.request.Request(
            self.url, data=body,
            headers={"Content-Type": "application/json",
                     "Authorization": "Bearer %s" % LLM_API_KEY})
        with urllib.request.urlopen(req, timeout=LLM_TIMEOUT) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        text = data["choices"][0]["message"]["content"]
        TOKEN_METER.record(prompt, text)
        return text


def get_llm() -> object:
    """优先真实 LLM，配置缺失时降级 Mock。"""
    if LLM_API_KEY and LLM_BASE_URL:
        return RealLLM()
    return MockLLM()
