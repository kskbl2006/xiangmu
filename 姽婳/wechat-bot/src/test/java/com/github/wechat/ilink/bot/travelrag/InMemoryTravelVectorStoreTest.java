package com.github.wechat.ilink.bot.travelrag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryTravelVectorStoreTest {
  @Test
  void appliesCityFilterAndRanksByCosineSimilarity() {
    TravelVectorIndex index =
        new TravelVectorIndex(
            "test",
            2,
            "2026-08-25T00:00:00+08:00",
            "test",
            List.of(
                new TravelKnowledgeChunk(
                    "shanghai", "上海", "attraction", "上海", "上海", "test", List.of(1.0, 0.0)),
                new TravelKnowledgeChunk(
                    "hangzhou", "杭州", "attraction", "杭州", "杭州", "test", List.of(0.9, 0.1)),
                new TravelKnowledgeChunk(
                    "suzhou", "苏州", "attraction", "苏州", "苏州", "test", List.of(0.0, 1.0))));
    InMemoryTravelVectorStore store = new InMemoryTravelVectorStore(index);

    var all = store.search(List.of(1.0, 0.0), null, 2, -1);
    assertEquals(List.of("shanghai", "hangzhou"), all.stream().map(hit -> hit.chunk().id()).toList());

    var filtered = store.search(List.of(1.0, 0.0), "苏州", 2, -1);
    assertEquals(List.of("suzhou"), filtered.stream().map(hit -> hit.chunk().id()).toList());
  }
}
