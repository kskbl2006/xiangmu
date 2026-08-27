package com.travel.agent;

import com.travel.agent.config.Config;
import com.travel.agent.core.Checkpoint;
import com.travel.agent.core.TokenMeter;
import com.travel.agent.core.TravelAgent;
import com.travel.agent.util.Json;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 智能旅行规划助手 Agent —— 命令行入口。
 *
 * 快速上手：
 *   java -jar target/travel-agent.jar "北京出发去三亚5天，预算5000元，2大1小"   # 一句话 → 完整方案
 *   java -jar target/travel-agent.jar --list-runs                                 # 查看历史运行
 *   java -jar target/travel-agent.jar --step 3 "..."                              # 只跑前3步后暂停
 *   java -jar target/travel-agent.jar --resume run_20260826_103000                # 断点续跑
 *   java -jar target/travel-agent.jar --fail-at weather "..."                      # 故障注入+自动重试
 *   java -jar target/travel-agent.jar --schedule "30m" "..."                      # 定时刷新方案（支持 Nm / HH:MM / cron）
 *   java -jar target/travel-agent.jar web                                          # Web 控制台
 *   java -jar target/travel-agent.jar bench                                       # 性能与 token 基准
 *   java -jar target/travel-agent.jar demo [--auto]                               # 验收演示
 */
public final class Main {

    private static final List<String> PIPELINE_ORDER =
            List.of("nlu", "weather", "poi", "budget", "itinerary", "validate", "report");

    public static void main(String[] args) throws Exception {
        // 强制控制台 UTF-8 输出（Windows GBK 控制台中文不乱码）
        try {
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // 降级使用默认输出
        }

        // ---- 子命令 ----
        if (args.length > 0) {
            switch (args[0]) {
                case "web" -> {
                    String[] rest = new String[args.length - 1];
                    System.arraycopy(args, 1, rest, 0, rest.length);
                    com.travel.agent.web.WebConsole.main(rest);
                    return;
                }
                case "bench" -> {
                    String[] rest = new String[args.length - 1];
                    System.arraycopy(args, 1, rest, 0, rest.length);
                    Bench.main(rest);
                    return;
                }
                case "demo" -> {
                    String[] rest = new String[args.length - 1];
                    System.arraycopy(args, 1, rest, 0, rest.length);
                    Demo.main(rest);
                    return;
                }
                default -> {
                    // 继续按旅行需求解析
                }
            }
        }

        // ---- 参数解析 ----
        String goal = null;
        String resume = null;
        Integer step = null;
        String failAt = null;
        String schedule = null;
        Integer maxRuns = null;
        String status = null;
        boolean listRuns = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--resume" -> resume = args[++i];
                case "--step" -> step = Integer.parseInt(args[++i]);
                case "--fail-at" -> failAt = args[++i];
                case "--schedule" -> schedule = args[++i];
                case "--max-runs" -> maxRuns = Integer.parseInt(args[++i]);
                case "--status" -> status = (i + 1 < args.length && !args[i + 1].startsWith("--"))
                        ? args[++i] : "__all__";
                case "--list-runs" -> listRuns = true;
                default -> {
                    if (goal == null) {
                        goal = a;
                    }
                }
            }
        }

        String llmMode = (!Config.LLM_API_KEY.isEmpty() && !Config.LLM_BASE_URL.isEmpty())
                ? "真实LLM(" + Config.LLM_MODEL + ")" : "MockLLM(离线兜底)";
        System.out.println("=".repeat(62));
        System.out.println(" 智能旅行规划助手 Agent · 自主规划型 · LLM引擎：" + llmMode);
        System.out.println("=".repeat(62));

        if (status != null) {
            showStatus("__all__".equals(status) ? null : status);
            return;
        }
        if (schedule != null) {
            if (goal == null) {
                System.err.println("--schedule 需要同时提供一句话目标");
                System.exit(2);
            }
            com.travel.agent.core.Scheduler.runScheduled(schedule, goal, maxRuns);
            return;
        }

        if (listRuns) {
            listRuns();
            return;
        }

        if (resume != null || goal != null) {
            long t0 = System.nanoTime();
            TravelAgent agent;
            String path;
            if (resume != null) {
                agent = new TravelAgent(goal == null ? "" : goal, resume);
                path = agent.run(true, step, failAt, false);
            } else {
                agent = new TravelAgent(goal);
                path = agent.run(false, step, failAt, false);
            }
            double elapsed = (System.nanoTime() - t0) / 1e9;
            System.out.println("-".repeat(62));
            System.out.printf("总耗时 %.1fs | 实际执行步骤：%s%n", elapsed,
                    agent.executed.isEmpty() ? "无" : String.join(" → ", agent.executed));
            System.out.println(TokenMeter.INSTANCE.report());
            if (path != null) {
                System.out.println("方案文档：" + path);
            } else if (step != null) {
                System.out.printf("已按 --step %d 暂停，续跑：java -jar travel-agent.jar --resume %s%n",
                        step, agent.runId);
            }
            System.out.println("=".repeat(62));
            return;
        }

        printHelp();
        listRuns();
    }

    private static void listRuns() {
        List<Path> rows = new ArrayList<>();
        if (Files.isDirectory(Config.RUNS_DIR)) {
            try (Stream<Path> s = Files.list(Config.RUNS_DIR)) {
                s.forEach(rows::add);
            } catch (Exception ignored) {
                // 列举失败按空处理
            }
            rows.sort(Path::compareTo);
        }
        if (rows.isEmpty()) {
            System.out.println("暂无运行记录（workspace/runs/ 为空）");
            return;
        }
        System.out.println("历史运行：");
        int start = Math.max(0, rows.size() - 10);
        for (int i = start; i < rows.size(); i++) {
            System.out.println("  " + rows.get(i).getFileName());
        }
        System.out.println("查看某次运行完成情况：java -jar travel-agent.jar --status <run_id>");
    }

    private static void showStatus(String runId) {
        List<Path> rows = new ArrayList<>();
        if (Files.isDirectory(Config.RUNS_DIR)) {
            try (Stream<Path> s = Files.list(Config.RUNS_DIR)) {
                s.forEach(rows::add);
            } catch (Exception ignored) {
                // 忽略
            }
            rows.sort(Path::compareTo);
        }
        if (rows.isEmpty()) {
            System.out.println("暂无运行记录");
            return;
        }
        if (runId == null) {   // 总览
            System.out.printf("%-24s %-8s %-10s %s%n", "RUN_ID", "步骤", "方案", "目标");
            int start = Math.max(0, rows.size() - 15);
            for (int i = start; i < rows.size(); i++) {
                Checkpoint ck;
                try {
                    ck = Checkpoint.load(rows.get(i).getFileName().toString());
                } catch (Exception e) {
                    continue;
                }
                int done = ck.completed().size();
                Map<String, Object> report = ck.result("report");
                String ok = (report != null && Files.exists(Path.of(Json.getStr(report, "path"))))
                        ? "已生成" : "未生成";
                String goalStr = Json.cut(ck.goal(), 24);
                if (ck.goal().length() > 24) {
                    goalStr += "…";
                }
                System.out.printf("%-24s %d/7      %-10s %s%n", rows.get(i).getFileName(), done, ok, goalStr);
            }
            System.out.println("详情：java -jar travel-agent.jar --status <run_id>");
            return;
        }
        try {
            Checkpoint ck = Checkpoint.load(runId);
            java.util.Set<String> done = new java.util.LinkedHashSet<>(ck.completed());
            System.out.println("运行：" + runId);
            System.out.println("目标：" + ck.goal());
            System.out.println("创建时间：" + ck.createdAt());
            System.out.printf("步骤完成情况（%d/7）：%n", done.size());
            for (String name : PIPELINE_ORDER) {
                System.out.printf("  [%s] %s%n", done.contains(name) ? "✔" : "…", name);
            }
            Map<String, Object> report = ck.result("report");
            if (report != null) {
                String p = Json.getStr(report, "path");
                boolean exists = Files.exists(Path.of(p));
                System.out.println("方案文档：" + p + "（" + (exists ? "已生成" : "⚠ 文件缺失") + "）");
            } else {
                System.out.println("方案文档：未生成 → 续跑：java -jar travel-agent.jar --resume " + runId);
            }
            Map<String, Object> vd = ck.result("validate");
            if (vd != null) {
                System.out.printf("自检结果：修复 %d 项，风险 %d 项%n",
                        Json.getList(vd, "fixes").size(), Json.getList(vd, "issues").size());
                for (Object f : Json.getList(vd, "fixes")) {
                    System.out.println("  ✅ " + Json.str(f));
                }
                for (Object i : Json.getList(vd, "issues")) {
                    System.out.println("  ⚠ " + Json.str(i));
                }
            }
        } catch (Exception e) {
            System.out.println("运行记录加载失败：" + e.getMessage());
        }
    }

    private static void printHelp() {
        System.out.println("用法：java -jar target/travel-agent.jar [选项] \"一句话旅行需求\"");
        System.out.println();
        System.out.println("常用：");
        System.out.println("  \"北京出发去三亚5天，预算5000元，2大1小\"    一句话 → 完整方案");
        System.out.println("  --status [RUN_ID]                            完成情况总览/详情");
        System.out.println("  --list-runs                                  历史运行列表");
        System.out.println("  --step N \"...\"                              只跑前 N 步后暂停");
        System.out.println("  --resume RUN_ID                              断点续跑");
        System.out.println("  --fail-at TASK \"...\"                        故障注入+自动重试");
        System.out.println("  --schedule \"*/30 * * * *\" \"...\" [--max-runs N]  定时刷新方案");
        System.out.println("  web [port]                                   Web 控制台");
        System.out.println("  bench                                        性能与 token 基准");
        System.out.println("  demo [--auto]                                验收演示");
    }
}
