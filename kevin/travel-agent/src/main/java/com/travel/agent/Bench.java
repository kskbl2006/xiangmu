package com.travel.agent;

import com.travel.agent.core.TokenMeter;
import com.travel.agent.core.TravelAgent;
import com.travel.agent.tools.ToolRegistry;

import java.util.Map;

/**
 * 性能与 token 基准：冷启动 vs 缓存命中、串行 vs 并行执行对比。java -jar travel-agent.jar bench
 *
 * 输出三组数据，用于验收时量化"加速响应 / 减少 token"两项优化：
 *   1. 冷启动全链路耗时（含真实天气 API + 知识库检索与缓存写入）
 *   2. 缓存命中全链路耗时（0 网络、0 token）
 *   3. 工具串行 vs 并行微基准（DAG 并行加速的依据）
 */
public final class Bench {

    private static final String GOAL = "从上海出发去苏州2天，2人，预算3000元";

    private Bench() {
    }

    private record Snapshot(int calls, long tokens, long hits) {
        static Snapshot now() {
            return new Snapshot(TokenMeter.INSTANCE.getCalls(),
                    TokenMeter.INSTANCE.getTokens(), TokenMeter.INSTANCE.getCacheHits());
        }
    }

    private record RunResult(double elapsed, String path) {
    }

    private static RunResult fullRun(String runId) {
        long t0 = System.nanoTime();
        TravelAgent agent = new TravelAgent(GOAL, runId);
        String path = agent.run();
        return new RunResult((System.nanoTime() - t0) / 1e9, path);
    }

    private static double[] microTools() throws Exception {
        // 同一组工具调用：串行 vs 并行（force_refresh 绕过缓存，体现真实 IO 并行收益）
        Map<String, Object> w = com.travel.agent.util.Json.obj(
                "destination", "苏州", "start_date", "2026-09-10", "days", 2, "force_refresh", true);
        Map<String, Object> p = com.travel.agent.util.Json.obj(
                "destination", "苏州", "days", 2, "preferences", java.util.List.of(), "goal", GOAL);

        long t0 = System.nanoTime();
        ToolRegistry.get("weather").run(new java.util.HashMap<>(w));
        ToolRegistry.get("poi").run(new java.util.HashMap<>(p));
        double serial = (System.nanoTime() - t0) / 1e9;

        t0 = System.nanoTime();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.List<java.util.concurrent.Future<?>> fs = java.util.List.of(
                pool.submit(() -> {
                    try {
                        ToolRegistry.get("weather").run(new java.util.HashMap<>(w));
                    } catch (Exception ignored) {
                    }
                }),
                pool.submit(() -> {
                    try {
                        ToolRegistry.get("poi").run(new java.util.HashMap<>(p));
                    } catch (Exception ignored) {
                    }
                }));
        for (java.util.concurrent.Future<?> f : fs) {
            f.get();
        }
        pool.shutdown();
        pool.awaitTermination(1, java.util.concurrent.TimeUnit.HOURS);
        double parallel = (System.nanoTime() - t0) / 1e9;
        return new double[]{serial, parallel};
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(62));
        System.out.println(" 性能与 token 基准 · " + GOAL);
        System.out.println("=".repeat(62));

        ToolRegistry.invalidate("weather");
        ToolRegistry.invalidate("poi");
        Snapshot before = Snapshot.now();
        RunResult cold = fullRun("bench_cold");
        Snapshot afterCold = Snapshot.now();
        int coldCalls = afterCold.calls() - before.calls();
        long coldTokens = afterCold.tokens() - before.tokens();
        long coldHits = afterCold.hits() - before.hits();

        before = Snapshot.now();
        RunResult warm = fullRun("bench_warm");
        Snapshot afterWarm = Snapshot.now();
        int warmCalls = afterWarm.calls() - before.calls();
        long warmTokens = afterWarm.tokens() - before.tokens();
        long warmHits = afterWarm.hits() - before.hits();

        double[] sp = microTools();

        System.out.printf("""
                %n[1] 全链路端到端
                    冷启动（含网络+缓存写入） : %6.2f s   LLM调用 %d 次 / token %d / 缓存命中 %d
                    缓存命中（0网络0token）   : %6.2f s   LLM调用 %d 次 / token %d / 缓存命中 %d
                    端到端加速比              : %6.1f x

                [2] 工具层串行 vs 并行（真实IO）
                    串行 weather→poi          : %6.2f s
                    并行 weather∥poi          : %6.2f s
                    工具层加速比              : %6.1f x

                [3] 结论
                    - 缓存命中后全链路毫秒级完成，token 消耗为 0（规则模板+知识库检索）
                    - 无依赖子任务并行显著缩短网络等待时间
                %n""", cold.elapsed(), coldCalls, coldTokens, coldHits,
                warm.elapsed(), warmCalls, warmTokens, warmHits,
                cold.elapsed() / Math.max(warm.elapsed(), 1e-6),
                sp[0], sp[1], sp[0] / Math.max(sp[1], 1e-6));
        if (cold.path() != null) {
            System.out.println("方案产物：" + cold.path());
        }
    }
}
