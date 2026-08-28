package com.github.wechat.ilink.bot.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wechat.ilink.bot.travelrag.InMemoryTravelVectorStore;
import com.github.wechat.ilink.bot.travelrag.TravelKnowledgeChunk;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TravelCheckpointStoreTest {
  @TempDir Path temporaryDirectory;

  @Test
  void persistsEvidenceWithoutEmbeddingPayload() {
    Path file = temporaryDirectory.resolve("checkpoints.json");
    TravelCheckpointStore store = new TravelCheckpointStore(file, Duration.ofHours(6));
    TravelForecast forecast =
        new TravelForecast(
            "上海",
            LocalDate.of(2026, 8, 30),
            List.of(new TravelForecast.Daily(LocalDate.of(2026, 8, 30), true, "晴", 20, 30, 0, 10)));
    InMemoryTravelVectorStore.Hit hit = hit();
    TravelMapData map =
        new TravelMapData(true, "常州", "上海", List.of(), List.of(), Map.of(), List.of());

    store.save("从常州去上海", forecast, List.of(hit), map);
    TravelCheckpointStore.Evidence restored =
        new TravelCheckpointStore(file, Duration.ofHours(6)).find("从常州去上海").orElseThrow();

    assertEquals("晴", restored.forecast().days().getFirst().condition());
    assertEquals("景点", restored.hits().getFirst().chunk().title());
    assertTrue(restored.hits().getFirst().chunk().embedding().isEmpty());
    assertEquals("常州", restored.mapData().origin());
  }

  @Test
  void agentReusesCheckpointInsteadOfRepeatingExternalEvidenceCalls() throws Exception {
    AtomicInteger weatherCalls = new AtomicInteger();
    AtomicInteger evidenceCalls = new AtomicInteger();
    TravelCheckpointStore store =
        new TravelCheckpointStore(
            temporaryDirectory.resolve("agent-checkpoints.json"), Duration.ofHours(6));
    TravelAgentService agent =
        new TravelAgentService(
            new TravelBriefSkill(),
            new CnTripPlannerSkill(),
            new TravelPlanReviewSkill(),
            (query, limit) -> List.of(hit()),
            (city, startDate, days) -> {
              weatherCalls.incrementAndGet();
              return TravelForecast.unavailable(city, startDate, days);
            },
            (brief, candidates) -> {
              evidenceCalls.incrementAndGet();
              return TravelMapData.disabled(brief.origin(), brief.destination());
            },
            store);
    String goal = "从常州去上海玩2天，2人预算3000元，喜欢历史";

    agent.execute(goal);
    List<String> stages = new java.util.ArrayList<>();
    agent.execute(goal, stages::add);

    assertEquals(1, weatherCalls.get());
    assertEquals(1, evidenceCalls.get());
    assertTrue(stages.stream().anyMatch(stage -> stage.contains("检查点恢复")));
  }

  @Test
  void providerVariantPreventsReusingEvidenceFromAnotherConfiguration() throws Exception {
    AtomicInteger evidenceCalls = new AtomicInteger();
    java.util.concurrent.atomic.AtomicReference<String> variant =
        new java.util.concurrent.atomic.AtomicReference<>("rail-disabled-v1");
    TravelEvidenceProvider provider =
        new TravelEvidenceProvider() {
          @Override
          public TravelMapData collect(TravelBrief brief, List<PlaceCandidate> candidates) {
            evidenceCalls.incrementAndGet();
            return TravelMapData.disabled(brief.origin(), brief.destination());
          }

          @Override
          public String checkpointVariant() {
            return variant.get();
          }
        };
    TravelAgentService agent =
        new TravelAgentService(
            new TravelBriefSkill(),
            new CnTripPlannerSkill(),
            new TravelPlanReviewSkill(),
            (query, limit) -> List.of(hit()),
            (city, startDate, days) -> TravelForecast.unavailable(city, startDate, days),
            provider,
            new TravelCheckpointStore(
                temporaryDirectory.resolve("provider-variant.json"), Duration.ofHours(6)));
    String goal = "从常州去上海玩2天，2人预算3000元，喜欢历史";

    agent.execute(goal);
    agent.execute(goal);
    variant.set("rail-enabled-v1");
    agent.execute(goal);

    assertEquals(2, evidenceCalls.get());
  }

  private static InMemoryTravelVectorStore.Hit hit() {
    return new InMemoryTravelVectorStore.Hit(
        new TravelKnowledgeChunk(
            "attraction:test", "上海", "attraction", "景点", "景点位于上海", "demo", List.of(0.1, 0.2)),
        0.9);
  }
}
