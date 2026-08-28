package com.github.wechat.ilink.bot.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wechat.ilink.bot.travelrag.InMemoryTravelVectorStore;
import com.github.wechat.ilink.bot.travelrag.TravelKnowledgeChunk;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CandidateCollectorTest {
  @Test
  void mergesProvenanceAndExcludesClosedCandidates() {
    TravelKnowledgeChunk open =
        new TravelKnowledgeChunk("a", "苏州", "attraction", "拙政园", "园林", "test", List.of());
    TravelKnowledgeChunk closed =
        new TravelKnowledgeChunk("b", "苏州", "attraction", "闭馆点", "展馆", "test", List.of());
    List<InMemoryTravelVectorStore.Hit> hits =
        List.of(
            new InMemoryTravelVectorStore.Hit(open, 0.9),
            new InMemoryTravelVectorStore.Hit(closed, 0.8));
    CandidateCollector collector = new CandidateCollector();
    List<PlaceCandidate> candidates = collector.collect(hits);
    TravelMapData map =
        new TravelMapData(
            true,
            "常州",
            "苏州",
            List.of(),
            List.of(),
            Map.of(
                "拙政园", poi("拙政园", "07:30-17:30"),
                "闭馆点", poi("闭馆点", "暂停营业")),
            List.of());

    List<PlaceCandidate> merged = collector.mergeMapData(candidates, map);
    List<InMemoryTravelVectorStore.Hit> eligible = collector.eligibleHits(hits, merged);

    assertEquals(1, eligible.size());
    assertEquals("a", eligible.getFirst().chunk().id());
    assertTrue(merged.getFirst().source().contains("baidu-map"));
    assertTrue(merged.getFirst().dataCompleteness() > candidates.getFirst().dataCompleteness());
  }

  private static TravelMapData.PoiSnapshot poi(String name, String hours) {
    return new TravelMapData.PoiSnapshot(
        name, "地址", 1, 1, 4.8, 50, hours, "", "", Instant.parse("2026-08-28T00:00:00Z"));
  }
}
