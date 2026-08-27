package com.travel.agent;

import com.travel.agent.config.Config;
import com.travel.agent.core.Checkpoint;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 验收演示脚本：按回车逐步演示 5 个核心特性（约 3 分钟）。
 *
 *   java -jar travel-agent.jar demo            # 交互模式（推荐验收时用）
 *   java -jar travel-agent.jar demo --auto     # 自动模式（无停顿连跑，录屏/自测用）
 */
public final class Demo {

    private static final boolean AUTO = List.of(System.getProperty("demo.args", "").split("\\s+")).contains("--auto");
    private static final String GOAL = "北京出发去三亚5天，预算5000元，2大1小";

    private Demo() {
    }

    public static void main(String[] args) throws Exception {
        boolean auto = Stream.concat(Stream.of(args), Stream.of(System.getProperty("demo.args", "")))
                .anyMatch("--auto"::equals);
        boolean finalAuto = auto || AUTO;

        System.out.println("智能旅行规划助手 Agent · 验收演示" + (finalAuto ? "（自动模式）" : ""));
        System.out.println("示例目标：" + GOAL);

        step("1/5 完整闭环", "一句话输入 → 7 步子任务自动拆解 → 并行工具调用 → 产出《旅行方案》md+docx", finalAuto);
        runCmd(List.of(GOAL));

        step("2/5 完成情况检查", "--status 查看所有运行总览与逐步详情（方案是否生成、自检修复记录）", finalAuto);
        runCmd(List.of("--status"));
        runCmd(List.of("--status", "test_full"));

        step("3/5 断点续跑", "先 --step 3 跑 3 步暂停，再 --resume 从断点续跑，已完成步骤不重复执行", finalAuto);
        runCmd(List.of("--step", "3", "帮我规划西安3日游，2人，预算7000元"));
        runCmd(List.of("--status"));
        String latest = latestPartialRun();
        if (latest != null) {
            runCmd(List.of("--resume", latest));
        }

        step("4/5 故障自愈", "--fail-at weather 注入网络故障 → 自动重试成功，流程不中断", finalAuto);
        runCmd(List.of("--fail-at", "weather", "帮我规划苏州2日游，2人，预算4000元"));

        step("5/5 缓存加速与 token 优化", "同一目标再跑一次：工具缓存命中，秒级完成、0 次 LLM 调用", finalAuto);
        runCmd(List.of(GOAL));

        System.out.println("\n演示完毕。历史运行与方案文档见 workspace/runs/，"
                + "完成情况：java -jar travel-agent.jar --status");
    }

    private static void step(String title, String desc, boolean auto) {
        System.out.println("\n" + "=".repeat(62));
        System.out.println(" ▶ " + title);
        System.out.println("   " + desc);
        System.out.println("=".repeat(62));
        if (auto) {
            return;
        }
        System.out.print("   按回车开始本节演示（Ctrl+C 退出）...");
        try {
            String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            if (line == null) {
                System.out.println("\n演示中断");
                System.exit(0);
            }
        } catch (Exception e) {
            System.out.println("\n演示中断");
            System.exit(0);
        }
    }

    private static String quote(String a) {
        return a.contains(" ") ? "\"" + a + "\"" : a;
    }

    private static void runCmd(List<String> args) throws Exception {
        System.out.println("\n$ java -jar travel-agent.jar " + String.join(" ", args.stream().map(Demo::quote).toList()));
        String jar = jarPath();
        if (jar != null && jar.endsWith(".jar")) {
            List<String> cmd = new ArrayList<>();
            cmd.add(System.getProperty("java.home") + fileSep() + "bin" + fileSep()
                    + (System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java"));
            cmd.add("-Dstdout.encoding=UTF-8");
            cmd.add("-Dstderr.encoding=UTF-8");
            cmd.add("-jar");
            cmd.add(jar);
            cmd.addAll(args);
            new ProcessBuilder(cmd).inheritIO().directory(new java.io.File(System.getProperty("user.dir"))).start().waitFor();
        } else {
            // 非 jar 运行（如 IDE）退化为进程内调用
            Main.main(args.toArray(new String[0]));
        }
    }

    private static String jarPath() {
        try {
            return new java.io.File(Demo.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private static String fileSep() {
        return System.getProperty("file.separator");
    }

    private static String latestPartialRun() {
        List<Path> rows = new ArrayList<>();
        try (Stream<Path> s = Files.list(Config.RUNS_DIR)) {
            s.forEach(rows::add);
        } catch (Exception e) {
            return null;
        }
        rows.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));
        for (Path r : rows) {
            try {
                Checkpoint ck = Checkpoint.load(r.getFileName().toString());
                if (ck.completed().size() < 7 && !"test_full".equals(r.getFileName().toString())) {
                    return r.getFileName().toString();
                }
            } catch (Exception ignored) {
                // 损坏记录跳过
            }
        }
        return null;
    }
}
