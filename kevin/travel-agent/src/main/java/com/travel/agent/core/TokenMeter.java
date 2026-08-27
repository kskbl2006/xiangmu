package com.travel.agent.core;

/**
 * 全局 token 记账器（LLM 消耗 + 工具缓存节省估算）。
 *
 * 所有 LLM 调用与缓存命中均经此处记账，运行结束输出 token 报告，
 * 用于演示"减少 token 消耗"的优化效果。
 */
public final class TokenMeter {

    public static final TokenMeter INSTANCE = new TokenMeter();

    private int calls;
    private long promptTokens;
    private long completionTokens;
    private long cacheHits;
    private long savedTokens;

    private TokenMeter() {
    }

    /** 粗略估算 token：中文约 1 字/token，其他字符约 4 字符/token。 */
    public static int estTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cn = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fff) {
                cn++;
            }
        }
        return cn + (text.length() - cn) / 4;
    }

    public synchronized void record(String prompt, String completion) {
        calls++;
        promptTokens += estTokens(prompt);
        completionTokens += estTokens(completion);
    }

    /** 工具磁盘缓存命中一次：估算节省约 300 token（一次工具结果进入下游 prompt 的量）。 */
    public synchronized void cacheHit() {
        cacheHits++;
        savedTokens += 300;
    }

    public synchronized int getCalls() {
        return calls;
    }

    public synchronized long getTokens() {
        return promptTokens + completionTokens;
    }

    public synchronized long getCacheHits() {
        return cacheHits;
    }

    public synchronized String report() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[TOKEN] LLM 调用 %d 次，消耗约 %d tokens（prompt %d + completion %d）",
                calls, getTokens(), promptTokens, completionTokens));
        if (cacheHits > 0) {
            sb.append(String.format("%n[TOKEN] 工具缓存命中 %d 次，估算节省约 %d tokens 及对应网络等待",
                    cacheHits, savedTokens));
        }
        if (calls == 0 && cacheHits == 0) {
            sb.append(String.format("%n[TOKEN] 本次运行 0 次 LLM 调用（规则模板 + 知识库检索完全命中，token 消耗为 0）"));
        }
        return sb.toString();
    }
}
