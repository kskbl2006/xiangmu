package com.github.wechat.ilink.bot.agent;

import com.github.wechat.ilink.bot.travelrag.InMemoryTravelVectorStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 将检索结果转为可追踪候选，并合并实时 POI 证据。 */
public final class CandidateCollector {
  private static final Set<String> OUTDOOR_WORDS =
      Set.of("外滩", "湖", "湿地", "古街", "园林", "动物园", "山", "步行", "夜景", "公园");

  public List<PlaceCandidate> collect(List<InMemoryTravelVectorStore.Hit> hits) {
    if (hits == null || hits.isEmpty()) return List.of();
    List<PlaceCandidate> candidates = new ArrayList<>();
    Set<String> used = new HashSet<>();
    for (InMemoryTravelVectorStore.Hit hit : hits) {
      if (!"attraction".equals(hit.chunk().type()) || !used.add(hit.chunk().id())) continue;
      candidates.add(PlaceCandidate.fromHit(hit, isOutdoor(hit.chunk().title() + " " + hit.chunk().text())));
    }
    return List.copyOf(candidates);
  }

  public List<PlaceCandidate> mergeMapData(
      List<PlaceCandidate> candidates, TravelMapData mapData) {
    if (candidates == null || candidates.isEmpty()) return List.of();
    return candidates.stream()
        .map(candidate -> candidate.withPoi(mapData.poi(candidate.title())))
        .toList();
  }

  public List<InMemoryTravelVectorStore.Hit> eligibleHits(
      List<InMemoryTravelVectorStore.Hit> hits, List<PlaceCandidate> candidates) {
    Set<String> eligible = new HashSet<>();
    candidates.stream().filter(candidate -> !candidate.closed()).forEach(value -> eligible.add(value.sourceId()));
    return hits.stream().filter(hit -> eligible.contains(hit.chunk().id())).toList();
  }

  private static boolean isOutdoor(String content) {
    return OUTDOOR_WORDS.stream().anyMatch(content::contains)
        && !content.contains("博物馆")
        && !content.contains("纪念馆");
  }
}
