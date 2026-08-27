package com.travel.agent.core;

import java.util.List;

/**
 * 离线兜底：规则式最小应答，接口与真实 LLM 一致。
 *
 * 主链路（解析/预算/行程/校验/报告）均为规则模板 + 知识库检索，
 * 只有意图识别等轻量判断走 LLM，Mock 模式下按关键词规则应答。
 */
public final class MockLlm implements Llm {

    private static final List<String> KEYWORDS =
            List.of("旅行", "旅游", "行程", "攻略", "游", "出发", "度假", "玩");

    @Override
    public String complete(String prompt, double temperature) {
        TokenMeter.INSTANCE.record(prompt, "是");
        // 只对"用户输入："之后的原文做关键词判断，避免把提示词模板本身误判为旅行意图
        String text = prompt;
        int idx = prompt.lastIndexOf("用户输入：");
        if (idx >= 0) {
            text = prompt.substring(idx + "用户输入：".length());
        }
        for (String kw : KEYWORDS) {
            if (text.contains(kw)) {
                return "是";
            }
        }
        return "否";
    }
}
