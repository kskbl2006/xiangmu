package com.travel.agent.core;

import com.travel.agent.config.Config;
import com.travel.agent.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 断点续跑（Checkpoint）：每个子任务完成后状态原子落盘，支持 --resume 续跑。
 *
 * 落盘内容：workspace/runs/&lt;run_id&gt;/checkpoint.json
 *   { run_id, goal, created_at, tasks: { task_name: {status, result, finished_at} } }
 * 同时把每个子任务的原始结果存为 step_&lt;name&gt;.json，便于调试与演示中间过程。
 */
public final class Checkpoint {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public final String runId;
    public final Path dir;
    public final Path path;
    public Map<String, Object> state;
    private final Object lock = new Object();   // 并行子任务同时 mark 时的写互斥

    public Checkpoint(String runId, String goal) {
        this.runId = runId;
        this.dir = Config.RUNS_DIR.resolve(runId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.path = dir.resolve("checkpoint.json");
        this.state = Json.obj(
                "run_id", runId,
                "goal", goal,
                "created_at", LocalDateTime.now().format(TS),
                "tasks", new LinkedHashMap<String, Object>());
    }

    /** 写入任务结果并原子落盘。 */
    public void mark(String task, Map<String, Object> result) {
        synchronized (lock) {
            Json.map(state.get("tasks")).put(task, Json.obj(
                    "status", "done",
                    "result", result,
                    "finished_at", LocalDateTime.now().format(TS)));
            flush(task);
        }
    }

    /** 原子写：先写临时文件再替换，避免中途崩溃损坏断点。调用方需持有 lock。 */
    private void flush(String task) {
        try {
            Path tmp = dir.resolve("checkpoint.tmp");
            Files.writeString(tmp, Json.MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(state), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            // 中间产物同步落盘，便于调试与演示
            Object taskState = Json.map(state.get("tasks")).get(task);
            Files.writeString(dir.resolve("step_" + task + ".json"),
                    Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(taskState),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("checkpoint 落盘失败: " + e.getMessage(), e);
        }
    }

    /** 已完成任务名列表（保持完成顺序）。 */
    public List<String> completed() {
        List<String> done = new ArrayList<>();
        for (Map.Entry<String, Object> e : Json.map(state.get("tasks")).entrySet()) {
            Map<String, Object> t = Json.map(e.getValue());
            if ("done".equals(t.get("status"))) {
                done.add(e.getKey());
            }
        }
        return done;
    }

    /** 任务结果（未完成为 null）。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> result(String task) {
        Object t = Json.map(state.get("tasks")).get(task);
        if (!(t instanceof Map)) {
            return null;
        }
        Object r = ((Map<String, Object>) t).get("result");
        return r instanceof Map ? (Map<String, Object>) r : null;
    }

    public String goal() {
        return Json.getStr(state, "goal");
    }

    public String createdAt() {
        return Json.getStr(state, "created_at");
    }

    /** 从磁盘载入运行记录。 */
    public static Checkpoint load(String runId) throws IOException {
        Path p = Config.RUNS_DIR.resolve(runId).resolve("checkpoint.json");
        if (!Files.exists(p)) {
            throw new IOException("未找到运行记录 " + runId + "（查看 workspace/runs/ 目录）");
        }
        Checkpoint ck = new Checkpoint(runId, "");
        ck.state = Json.MAPPER.readValue(p.toFile(), Json.MAPPER.getTypeFactory()
                .constructMapType(LinkedHashMap.class, String.class, Object.class));
        return ck;
    }
}
