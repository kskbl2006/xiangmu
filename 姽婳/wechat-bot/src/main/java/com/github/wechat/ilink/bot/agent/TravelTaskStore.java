package com.github.wechat.ilink.bot.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Persists the last canonical travel goal and execution stage for continuation after failures. */
public final class TravelTaskStore {
  private static final Logger log = LoggerFactory.getLogger(TravelTaskStore.class);
  private static final TypeReference<Map<String, Entry>> TYPE = new TypeReference<>() {};
  private final ObjectMapper mapper = new ObjectMapper();
  private final Path path;
  private final Map<String, Entry> entries = new LinkedHashMap<>();

  public TravelTaskStore(Path path) {
    this.path = path.toAbsolutePath().normalize();
    load();
  }

  public synchronized Optional<String> resolveGoal(String userId, String message) {
    Entry previous = entries.get(userId);
    if (previous == null || previous.goal().isBlank()) return Optional.empty();
    String text = message == null ? "" : message.trim();
    String location = extractLocationAnswer(text);
    if (location != null && "等待补充出发地".equals(previous.stage())) {
      return Optional.of("从" + location + "出发；" + previous.goal());
    }
    if (location != null && "等待补充目的地".equals(previous.stage())) {
      return Optional.of("目的地是" + location + "；" + previous.goal());
    }
    if (text.matches(".*(?:继续|接着)(?:上次|刚才)?(?:任务|规划|行程)?.*")) {
      return Optional.of(previous.goal());
    }
    if ("已完成".equals(previous.stage()) && looksLikeStandaloneTravelRequest(text)) {
      return Optional.empty();
    }
    if (looksLikeRevision(text)) {
      return Optional.of(text + "；基于上一版旅行目标：" + previous.goal());
    }
    return Optional.empty();
  }

  public synchronized void progress(String userId, String goal, String stage) {
    entries.put(userId, new Entry(goal, stage, System.currentTimeMillis()));
    persistQuietly();
  }

  public synchronized void complete(String userId, TravelBrief brief) {
    entries.put(userId, new Entry(canonicalGoal(brief), "已完成", System.currentTimeMillis()));
    persistQuietly();
  }

  public synchronized Optional<Entry> get(String userId) {
    return Optional.ofNullable(entries.get(userId));
  }

  static boolean looksLikeRevision(String text) {
    return text != null
        && text.matches(
            ".*(?:改成|调整|重新规划|换成|增加|减少|删掉|取消|不要|预算|改为|天数|人数|节奏|出发日期).*" );
  }

  private static boolean looksLikeStandaloneTravelRequest(String text) {
    return text != null
        && text.matches(".*(?:去|到|前往|旅游|旅行|度假|游玩|去玩).*")
        && text.matches(".*(?:天|日游|预算|人|节奏|喜欢).*");
  }

  private static String extractLocationAnswer(String text) {
    if (text == null || text.isBlank()) return null;
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile(
                "^(?:我)?(?:从|在|去|到|目的地是)?\\s*([\\p{IsHan}·]{2,12}?)(?:市)?(?:出发|旅游|旅行|玩)?[，。！？!?\\s]*$")
            .matcher(text);
    return matcher.matches() ? matcher.group(1) : null;
  }

  static String canonicalGoal(TravelBrief brief) {
    StringBuilder goal = new StringBuilder();
    if (!brief.origin().isBlank()) goal.append("从").append(brief.origin()).append("出发，");
    goal.append(brief.startDate())
        .append("去")
        .append(brief.destination())
        .append("旅行")
        .append(brief.days())
        .append("天，")
        .append(brief.travelers())
        .append("人，预算")
        .append(brief.budgetYuan())
        .append("元，节奏")
        .append(switch (brief.pace()) {
          case "relaxed" -> "轻松";
          case "packed" -> "紧凑";
          default -> "平衡";
        });
    if (!brief.interests().isEmpty()) goal.append("，喜欢").append(String.join("、", brief.interests()));
    return goal.toString();
  }

  private void load() {
    if (!Files.isRegularFile(path)) return;
    try {
      Map<String, Entry> loaded = mapper.readValue(path.toFile(), TYPE);
      entries.putAll(loaded);
    } catch (Exception e) {
      log.warn("Unable to load travel task state: {}", e.getMessage());
    }
  }

  private void persistQuietly() {
    try {
      Files.createDirectories(path.getParent());
      Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
      mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), entries);
      try {
        Files.move(
            temporary,
            path,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException atomicFailure) {
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      log.warn("Unable to persist travel task state: {}", e.getMessage());
    }
  }

  public record Entry(String goal, String stage, long updatedAtEpochMillis) {
    public Entry {
      goal = goal == null ? "" : goal;
      stage = stage == null ? "" : stage;
    }
  }
}
