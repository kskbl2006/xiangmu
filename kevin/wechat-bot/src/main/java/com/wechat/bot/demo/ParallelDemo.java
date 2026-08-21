package com.wechat.bot.demo;

import com.wechat.bot.config.AppConfig;
import com.wechat.bot.tool.DateTimeTool;
import com.wechat.bot.tool.ToolRegistry;
import com.wechat.bot.tool.WeatherTool;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 多工具并行执行验证 Demo（不依赖 LLM / 微信登录）。
 * 运行后观察：3 个工具任务并行总耗时 ≈ 单任务耗时（而非 3 倍）。
 */
public class ParallelDemo {

    public static void main(String[] args) {
        AppConfig config = AppConfig.load();
        ToolRegistry registry = new ToolRegistry()
                .register(new WeatherTool(config))
                .register(new DateTimeTool());
        ObjectMapper mapper = new ObjectMapper();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        try {
            // ---- 串行基线：逐个执行 3 个任务 ----
            long serialStart = System.currentTimeMillis();
            String r1 = registry.execute("get_weather",
                    mapper.createObjectNode().put("city", "北京").put("days", 1));
            String r2 = registry.execute("get_weather",
                    mapper.createObjectNode().put("city", "上海").put("days", 1));
            String r3 = registry.execute("get_datetime", mapper.createObjectNode());
            long serialCost = System.currentTimeMillis() - serialStart;

            // ---- 并行执行：同样 3 个任务 ----
            long parallelStart = System.currentTimeMillis();
            List<CompletableFuture<String>> futures = new ArrayList<>();
            futures.add(CompletableFuture.supplyAsync(() -> registry.execute("get_weather",
                    mapper.createObjectNode().put("city", "北京").put("days", 1)), executor));
            futures.add(CompletableFuture.supplyAsync(() -> registry.execute("get_weather",
                    mapper.createObjectNode().put("city", "上海").put("days", 1)), executor));
            futures.add(CompletableFuture.supplyAsync(() -> registry.execute("get_datetime",
                    mapper.createObjectNode()), executor));
            List<String> parallelResults = futures.stream().map(CompletableFuture::join).toList();
            long parallelCost = System.currentTimeMillis() - parallelStart;

            System.out.println("=== 串行执行（3 任务）耗时 " + serialCost + " ms ===");
            System.out.println(r1.split("\n")[0]);
            System.out.println(r2.split("\n")[0]);
            System.out.println(r3);
            System.out.println();
            System.out.println("=== 并行执行（3 任务，虚拟线程）耗时 " + parallelCost + " ms ===");
            parallelResults.forEach(r -> System.out.println(r.split("\n")[0]));
            System.out.println();
            System.out.printf("加速比：%.2fx%n", (double) serialCost / parallelCost);
        } finally {
            executor.close();
        }
    }
}
