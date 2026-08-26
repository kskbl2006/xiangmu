package com.github.wechat.ilink.bot.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TravelTaskStoreTest {
  @TempDir Path temporaryDirectory;

  @Test
  void persistsGoalAndResolvesRevisionAndContinuation() {
    Path file = temporaryDirectory.resolve("travel-tasks.json");
    TravelTaskStore store = new TravelTaskStore(file);
    TravelBrief brief =
        new TravelBrief(
            "常州", "上海", 3, 2, 5000, "relaxed", List.of("历史", "美食"), List.of(),
            LocalDate.of(2026, 8, 28), true);
    store.complete("user-1", brief);

    TravelTaskStore restored = new TravelTaskStore(file);
    String revised = restored.resolveGoal("user-1", "预算改成3000元").orElseThrow();
    assertTrue(revised.startsWith("预算改成3000元"));
    assertTrue(revised.contains("上海"));
    assertEquals(
        TravelTaskStore.canonicalGoal(brief),
        restored.resolveGoal("user-1", "继续上次任务").orElseThrow());
    assertEquals("已完成", restored.get("user-1").orElseThrow().stage());
    assertTrue(
        restored
            .resolveGoal("user-1", "从常州去玩4天，2人预算5000元，喜欢历史和美食")
            .isEmpty());
    assertTrue(
        restored
            .resolveGoal("user-1", "去玩4天，2人预算5000元，喜欢历史和美食")
            .isEmpty());
  }

  @Test
  void acceptsShortLocationAnswersForPendingTravelQuestions() {
    TravelTaskStore store =
        new TravelTaskStore(temporaryDirectory.resolve("pending-travel-tasks.json"));

    store.progress("origin-user", "2026年10月20日去西安玩4天", "等待补充出发地");
    assertEquals(
        "从常州出发；2026年10月20日去西安玩4天",
        store.resolveGoal("origin-user", "我从常州出发").orElseThrow());

    store.progress("destination-user", "帮我规划一次4天旅行", "等待补充目的地");
    assertEquals(
        "目的地是西安；帮我规划一次4天旅行",
        store.resolveGoal("destination-user", "西安").orElseThrow());
  }
}
