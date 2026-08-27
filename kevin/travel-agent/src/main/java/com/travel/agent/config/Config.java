package com.travel.agent.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 全局配置：路径、缓存 TTL、LLM 接入、执行器参数。
 *
 * 目录约定：以进程工作目录（travel-agent/ 工程根）为基准，
 * knowledge/ 为知识库，workspace/runs/ 为运行产物，workspace/cache/ 为磁盘缓存。
 */
public final class Config {

    public static final Path BASE_DIR = Paths.get(System.getProperty("user.dir"));
    public static final Path WORKSPACE = BASE_DIR.resolve("workspace");
    public static final Path RUNS_DIR = WORKSPACE.resolve("runs");
    public static final Path CACHE_DIR = WORKSPACE.resolve("cache");
    public static final Path KNOWLEDGE_DIR = BASE_DIR.resolve("knowledge");

    // ---- LLM 接入（OpenAI 兼容接口，如 GLM / DeepSeek / 通义）----
    // 设置环境变量后自动启用真实 LLM；未设置时使用内置 MockLLM，保证离线跑通完整闭环。
    public static final String LLM_BASE_URL = env("LLM_BASE_URL", "");
    public static final String LLM_API_KEY = env("LLM_API_KEY", "");
    public static final String LLM_MODEL = env("LLM_MODEL", "glm-4-flash");
    public static final int LLM_TIMEOUT = Integer.parseInt(env("LLM_TIMEOUT", "20"));

    // ---- 缓存 TTL（秒）：命中缓存 = 0 token、0 网络等待 ----
    public static final int WEATHER_CACHE_TTL = 3 * 3600;      // 天气 3 小时
    public static final int POI_CACHE_TTL = 7 * 24 * 3600;    // 景点/美食/酒店 7 天

    // ---- 执行器 ----
    public static final int TOOL_RETRY = 2;      // 工具失败重试次数
    public static final int TOOL_TIMEOUT = 8;    // 网络类工具超时（秒）
    public static final int MAX_WORKERS = 4;     // 并行子任务线程数

    // ---- Token 压缩 ----
    public static final int MAX_POI_IN_ITINERARY_STEP = 12;   // 进入行程编排环节的候选景点上限（截断省 token）

    static {
        try {
            Files.createDirectories(WORKSPACE);
            Files.createDirectories(RUNS_DIR);
            Files.createDirectories(CACHE_DIR);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建 workspace 目录: " + e.getMessage(), e);
        }
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private Config() {
    }
}
