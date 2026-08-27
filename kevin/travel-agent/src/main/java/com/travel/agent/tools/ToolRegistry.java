package com.travel.agent.tools;

import com.travel.agent.config.Config;
import com.travel.agent.core.TokenMeter;
import com.travel.agent.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具基座：统一注册（Tool Registry）+ 磁盘缓存（TTL）+ 缓存失效。
 *
 * 缓存命中 = 0 token、0 网络等待，是"加速响应 + 减少 token 消耗"的主要手段之一。
 */
public final class ToolRegistry {

    private static final Map<String, Tool> REGISTRY = new LinkedHashMap<>();

    static {
        register(new WeatherTool());
        register(new PoiTool());
        register(new BudgetTool());
        register(new ItineraryTool());
        register(new ValidateTool());
        register(new ReportTool());
    }

    private static void register(Tool tool) {
        REGISTRY.put(tool.name(), tool);
    }

    public static Tool get(String name) {
        Tool t = REGISTRY.get(name);
        if (t == null) {
            throw new IllegalArgumentException("工具未注册: " + name);
        }
        return t;
    }

    // ---------------- 磁盘缓存 ----------------

    private static Path cachePath(String key) {
        String digest = md5(key).substring(0, 12);
        String safe = key.split(":")[0].replaceAll("[^0-9A-Za-z_-]+", "_");
        return Config.CACHE_DIR.resolve(safe + "_" + digest + ".json");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> cacheGet(String key, int ttlSec) {
        Path p = cachePath(key);
        if (!Files.exists(p)) {
            return null;
        }
        try {
            Map<String, Object> data = Json.MAPPER.readValue(p.toFile(),
                    Json.MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
            long ts = Json.intVal(data.get("ts"));
            if (System.currentTimeMillis() / 1000 - ts <= ttlSec) {
                TokenMeter.INSTANCE.cacheHit();
                return data.get("data") instanceof Map ? (Map<String, Object>) data.get("data") : null;
            }
        } catch (Exception ignored) {
            // 缓存损坏按未命中处理
        }
        return null;
    }

    public static void cacheSet(String key, Map<String, Object> data) {
        Path p = cachePath(key);
        try {
            Path tmp = Config.CACHE_DIR.resolve(p.getFileName() + ".tmp");
            Files.writeString(tmp, Json.MAPPER.writeValueAsString(Json.obj("ts",
                    System.currentTimeMillis() / 1000, "data", data)), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // 缓存写失败不阻断主流程
        }
    }

    /** 按前缀清除缓存（定时任务刷新天气时使用）。 */
    public static void invalidate(String prefix) {
        List<Path> matched = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Config.CACHE_DIR, prefix + "_*.json")) {
            ds.forEach(matched::add);
        } catch (IOException ignored) {
            return;
        }
        for (Path p : matched) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // 删除失败忽略
            }
        }
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private ToolRegistry() {
    }
}
