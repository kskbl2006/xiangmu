package com.travel.agent.core;

import com.travel.agent.config.Config;
import com.travel.agent.tools.ToolRegistry;
import com.travel.agent.util.Json;
import com.travel.agent.util.Log;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 调度执行器（Agent 主循环）。
 *
 * 特性：
 * 1. 断点续跑：每个子任务完成后写 checkpoint，resume 时跳过已完成步骤；
 * 2. 并行加速：DAG 中无依赖的子任务（天气/景点检索）并行执行；
 * 3. 失败重试 + Mock 兜底：工具异常自动重试，仍失败则由各工具内部 Mock 数据兜底，流程不中断；
 * 4. token 可观测：全程 TokenMeter 记账，报告文档附带运行统计。
 */
public final class TravelAgent {

    private static final DateTimeFormatter RUN_ID_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public final String runId;
    public String goal;
    public Checkpoint ckpt;
    public final List<String> executed = Collections.synchronizedList(new ArrayList<>());
    public final Map<String, Double> taskSeconds = new ConcurrentHashMap<>();
    public String reportPath;
    private final Set<String> failedTasks = ConcurrentHashMap.newKeySet();

    public TravelAgent(String goal) {
        this(goal, null);
    }

    public TravelAgent(String goal, String runId) {
        this.goal = goal;
        this.runId = (runId == null || runId.isBlank()) ? "run_" + LocalDateTime.now().format(RUN_ID_FMT) : runId;
    }

    // ---------------- 入口 ----------------
    public String run() {
        return run(false, null, null, false);
    }

    public String run(boolean resume, Integer maxSteps, String failAt, boolean forceRefresh) {
        if (resume) {
            try {
                ckpt = Checkpoint.load(runId);
            } catch (Exception e) {
                throw new IllegalStateException("载入断点失败: " + e.getMessage(), e);
            }
            goal = ckpt.goal();
            List<String> done = ckpt.completed();
            Log.info("RESUME", String.format("载入断点 %s，已完成 %d 步：%s", runId, done.size(),
                    String.join("→", done)));
        } else {
            ckpt = new Checkpoint(runId, goal);
        }

        long t0 = System.nanoTime();

        // ---- 子任务0：需求解析（NLU）----
        if (!ckpt.completed().contains("nlu")) {
            runNlu();
        } else {
            Log.info("RESUME", "跳过已完成步骤：nlu");
        }
        Map<String, Object> request = ckpt.result("nlu");

        // ---- 检查意图（非旅行输入直接拒绝，避免无意义长任务）----
        if (!Nlu.isTravelIntent(goal)) {
            Log.info("NLU", "输入不像旅行规划需求，Agent 拒绝执行：" + goal);
            return null;
        }

        // ---- DAG 执行循环 ----
        List<TaskSpec> dag = Planner.buildDag(request);
        Log.info("PLAN", String.format("拆解出 %d 个子任务：%s", dag.size(),
                String.join(" → ", dag.stream().map(TaskSpec::name).toList())));

        while (true) {
            List<String> completed = new ArrayList<>(ckpt.completed());
            List<TaskSpec> pending = dag.stream().filter(t -> !completed.contains(t.name())).toList();
            if (pending.isEmpty()) {
                break;
            }
            List<TaskSpec> ready = pending.stream()
                    .filter(t -> completed.containsAll(t.deps()) && !failedTasks.contains(t.name()))
                    .toList();
            if (ready.isEmpty()) {
                Log.info("PLAN", "无待执行任务（存在失败步骤），本轮终止；可 --resume 重试");
                break;
            }
            if (maxSteps != null && completed.size() >= maxSteps) {
                Log.info("STEP", String.format("已达到单次步数上限 %d，主动暂停并保存断点：%s", maxSteps, runId));
                return null;
            }

            if (ready.size() > 1) {
                Log.info("PLAN", "并行执行无依赖子任务：" + String.join(",", ready.stream().map(TaskSpec::name).toList())
                        + "（线程池加速）");
                ExecutorService pool = Executors.newFixedThreadPool(Config.MAX_WORKERS);
                List<Future<?>> futures = new ArrayList<>();
                for (TaskSpec t : ready) {
                    futures.add(pool.submit(() -> execTask(t.name(), t.desc(), failAt, forceRefresh)));
                }
                pool.shutdown();
                try {
                    for (Future<?> f : futures) {
                        f.get();
                    }
                    pool.awaitTermination(1, TimeUnit.HOURS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    Log.info("TASK", "并行子任务异常：" + e.getMessage());
                }
            } else {
                TaskSpec t = ready.get(0);
                execTask(t.name(), t.desc(), failAt, forceRefresh);
            }
        }

        // ---- 汇总 ----
        double elapsed = (System.nanoTime() - t0) / 1e9;
        Map<String, Object> report = ckpt.result("report");
        if (report != null) {
            reportPath = Json.getStr(report, "path");
            Log.info("DONE", String.format("全链路完成，耗时 %.1fs，方案文件：%s", elapsed, reportPath));
        } else {
            Log.info("DONE", String.format("本轮结束（有步骤未完成），耗时 %.1fs；可用 --resume %s 续跑", elapsed, runId));
        }
        return reportPath;
    }

    // ---------------- 步骤实现 ----------------
    private void runNlu() {
        long t0 = System.nanoTime();
        Log.info("TASK", "▶ nlu 开始：意图识别 + 实体抽取 + 参数补全");
        NluResult pr = Nlu.parseRequest(goal);
        ckpt.mark("nlu", pr.request());
        executed.add("nlu");
        taskSeconds.put("nlu", (System.nanoTime() - t0) / 1e9);
        Map<String, Object> request = pr.request();
        List<String> prefs = new ArrayList<>();
        for (Object p : Json.getList(request, "preferences")) {
            prefs.add(Json.str(p));
        }
        String questions = pr.questions().isEmpty() ? "" : "；待确认：" + String.join("；", pr.questions());
        Log.info("TASK", String.format("✔ nlu 完成：%s %d日 %d人 预算%d元 出发地%s 偏好%s%s",
                Json.getStr(request, "destination"), Json.getInt(request, "days"),
                Json.getInt(request, "people"), Json.getInt(request, "budget"),
                Json.getStr(request, "depart_city"),
                prefs.isEmpty() ? "无" : String.join("、", prefs), questions));
    }

    private void execTask(String name, String desc, String failAt, boolean forceRefresh) {
        long t0 = System.nanoTime();
        Log.info("TASK", "▶ " + name + " 开始：" + desc);
        Map<String, Object> result = callWithRetry(name, failAt, forceRefresh);
        if (result == null) {
            failedTasks.add(name);
            Log.info("TASK", "✖ " + name + " 重试后仍失败，标记跳过（Mock 兜底也未生效）");
            return;
        }
        ckpt.mark(name, result);
        executed.add(name);
        double sec = (System.nanoTime() - t0) / 1e9;
        taskSeconds.put(name, sec);
        Log.info("TASK", String.format("✔ %s 完成，耗时 %.2fs", name, sec));
    }

    private Map<String, Object> callWithRetry(String name, String failAt, boolean forceRefresh) {
        Exception lastErr = null;
        for (int attempt = 1; attempt <= Config.TOOL_RETRY; attempt++) {
            try {
                if (failAt != null && failAt.equals(name) && attempt == 1) {
                    throw new RuntimeException("演示注入故障：模拟第 1 步网络异常");
                }
                Map<String, Object> params = params(name, forceRefresh);
                return ToolRegistry.get(name).run(params);
            } catch (Exception e) {
                lastErr = e;
                Log.info("TASK", String.format("! %s 第 %d 次尝试失败：%s", name, attempt, e.getMessage()));
            }
        }
        Log.info("TASK", String.format("! %s 已重试 %d 次，最后错误：%s", name, Config.TOOL_RETRY,
                lastErr == null ? "" : lastErr.getMessage()));
        return null;
    }

    // ---------------- 各子任务的参数组装（依赖上游结果）----------------
    private Map<String, Object> params(String name, boolean forceRefresh) {
        Map<String, Object> req = ckpt.result("nlu");
        switch (name) {
            case "weather" -> {
                return Json.obj("destination", Json.getStr(req, "destination"),
                        "start_date", Json.getStr(req, "start_date"), "days", Json.getInt(req, "days"),
                        "force_refresh", forceRefresh);
            }
            case "poi" -> {
                List<Object> prefs = Json.getList(req, "preferences");
                return Json.obj("destination", Json.getStr(req, "destination"),
                        "days", Json.getInt(req, "days"), "preferences", prefs,
                        "goal", Json.getStr(req, "goal"));
            }
            case "budget" -> {
                Map<String, Object> p = ckpt.result("poi");
                return Json.obj("request", req,
                        "hotels", Json.getMaps(p, "hotels"), "foods", Json.getMaps(p, "foods"),
                        "attractions", Json.getMaps(p, "attractions"));
            }
            case "itinerary" -> {
                return Json.obj("request", req, "weather", ckpt.result("weather"),
                        "poi", ckpt.result("poi"), "budget", ckpt.result("budget"));
            }
            case "validate" -> {
                return Json.obj("request", req, "weather", ckpt.result("weather"),
                        "poi", ckpt.result("poi"), "budget", ckpt.result("budget"),
                        "itinerary", ckpt.result("itinerary"));
            }
            case "report" -> {
                Map<String, Object> results = Json.obj(
                        "weather", ckpt.result("weather"), "poi", ckpt.result("poi"),
                        "budget", ckpt.result("budget"), "validate", ckpt.result("validate"));
                Map<String, Double> ts = new LinkedHashMap<>(taskSeconds);
                return Json.obj("request", req, "results", results,
                        "stats", Json.obj("run_id", runId, "task_seconds", ts,
                                "executed", new ArrayList<>(executed),
                                "token_report", TokenMeter.INSTANCE.report()));
            }
            default -> throw new IllegalArgumentException("未知子任务: " + name);
        }
    }
}
