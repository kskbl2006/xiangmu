package com.travel.agent.core;

import com.travel.agent.config.Config;

/**
 * LLM 引擎：真实 API（OpenAI 兼容）+ MockLLM 兜底 + Token 记账。
 *
 * 未配置 LLM_API_KEY / LLM_BASE_URL 时自动降级 MockLLM，任何环境都能跑通闭环；
 * 所有调用经 TokenMeter 记账，运行结束输出 token 报告。
 */
public interface Llm {

    String complete(String prompt, double temperature);

    /** 优先真实 LLM，配置缺失时降级 Mock。 */
    static Llm get() {
        if (!Config.LLM_API_KEY.isEmpty() && !Config.LLM_BASE_URL.isEmpty()) {
            return new RealLlm();
        }
        return new MockLlm();
    }

    default String complete(String prompt) {
        return complete(prompt, 0.3);
    }
}
