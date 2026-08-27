package com.travel.agent.core;

import com.travel.agent.config.Config;
import com.travel.agent.tools.ToolRegistry;
import com.travel.agent.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * 定时任务调度器：按固定间隔 / 每日固定时间自动重跑规划（刷新天气、重出方案）。
 *
 * 用法：
 *   --schedule "30m" "..."             每 30 分钟（cron 形式 "0-59/30 * * * *" 同样支持）
 *   --schedule "08:00" "..."            每天 08:00
 *   --schedule "10m" "..." --max-runs 3 每 10 分钟，最多 3 次（演示）
 *
 * 每次触发会：清空天气缓存（拿最新预报）→ 新建一轮 run → 景点知识库缓存继续命中，
 * 日志追加到 workspace/schedule.log。
 */
public final class Scheduler {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Scheduler() {
    }

    /** 返回调度模式：(minutes) 或 (HH, MM)。 */
    private static int[] parse(String pattern) {
        String p = pattern.strip();
        if (p.endsWith("m") && p.substring(0, p.length() - 1).matches("\\d+")) {   // "10m"
            return new int[]{Math.max(1, Integer.parseInt(p.substring(0, p.length() - 1)))};
        }
        if (p.contains(" ") && p.startsWith("*/")) {                              // "*/30 * * * *"
            String first = p.split("\\s+")[0];
            return new int[]{Math.max(1, Integer.parseInt(first.substring(2)))};
        }
        String[] hm = p.split(":");                                                 // "08:00"
        if (hm.length == 2) {
            return new int[]{Integer.parseInt(hm[0]), Integer.parseInt(hm[1])};
        }
        throw new IllegalArgumentException("无法解析定时规则：" + pattern
                + "（支持 '*/N * * * *'、'Nm'、'HH:MM'）");
    }

    static LocalDateTime nextFire(String pattern, LocalDateTime now) {
        int[] k = parse(pattern);
        if (k.length == 1) {
            return now.plusMinutes(k[0]);
        }
        LocalDateTime fire = now.withHour(k[0]).withMinute(k[1]).withSecond(0).withNano(0);
        if (!fire.isAfter(now)) {
            fire = fire.plusDays(1);
        }
        return fire;
    }

    private static void appendLog(String line) {
        Path path = Config.WORKSPACE.resolve("schedule.log");
        try {
            Files.writeString(path, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 日志写失败不阻断调度
        }
    }

    public static void runScheduled(String pattern, String goal, Integer maxRuns) {
        Log.info("CRON", String.format("定时任务启动：规则=%s，目标=%s（Ctrl+C 退出）", pattern, goal));
        int runs = 0;
        while (maxRuns == null || runs < maxRuns) {
            LocalDateTime fire = nextFire(pattern, LocalDateTime.now());
            Log.info("CRON", String.format("第 %d 轮将在 %s 触发", runs + 1, fire.format(FMT)));
            while (LocalDateTime.now().isBefore(fire)) {
                long secs = Math.max(1, Math.min(1, Duration.between(LocalDateTime.now(), fire).getSeconds()));
                try {
                    TimeUnit.SECONDS.sleep(secs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            ToolRegistry.invalidate("weather");   // 定时刷新：天气缓存过期，景点缓存保留
            Log.info("CRON", String.format("触发第 %d 轮规划（已清空天气缓存，重取最新预报）", runs + 1));
            TravelAgent agent = new TravelAgent(goal);
            String path = agent.run(true, null, null, true);
            appendLog(String.format("[%s] run=%s report=%s",
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), agent.runId,
                    path == null ? "未生成" : path));
            runs++;
        }
        if (maxRuns != null) {
            Log.info("CRON", "已达 max-runs=" + maxRuns + "，调度结束");
        }
    }
}
