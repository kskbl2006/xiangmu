package com.github.wechat.ilink.bot.agent;

import com.github.wechat.ilink.bot.travelrag.InMemoryTravelVectorStore;
import com.github.wechat.ilink.bot.travelrag.TravelKnowledgeChunk;
import java.time.Instant;

/** 包含明确来源和数据质量信息的规划候选。 */
public record PlaceCandidate(
    String sourceId,
    String title,
    boolean outdoor,
    String evidence,
    String source,
    Instant lastUpdated,
    double confidence,
    double dataCompleteness,
    TravelKnowledgeChunk knowledge,
    TravelMapData.PoiSnapshot poi) {
  public PlaceCandidate {
    sourceId = text(sourceId);
    title = text(title);
    evidence = text(evidence);
    source = source == null || source.isBlank() ? "unknown" : source.trim();
    lastUpdated = lastUpdated == null ? Instant.EPOCH : lastUpdated;
    confidence = clamp(confidence);
    dataCompleteness = clamp(dataCompleteness);
  }

  public static PlaceCandidate fromHit(InMemoryTravelVectorStore.Hit hit, boolean outdoor) {
    TravelKnowledgeChunk chunk = hit.chunk();
    return new PlaceCandidate(
        chunk.id(),
        chunk.title(),
        outdoor,
        chunk.text(),
        "local-rag",
        Instant.EPOCH,
        hit.score(),
        0.35,
        chunk,
        null);
  }

  public PlaceCandidate withPoi(TravelMapData.PoiSnapshot snapshot) {
    if (snapshot == null) return this;
    int complete = 2; // 名称和坐标足以匹配地点。
    if (!snapshot.address().isBlank()) complete++;
    if (snapshot.rating() > 0) complete++;
    if (snapshot.referencePriceYuan() > 0) complete++;
    if (!snapshot.openingHours().isBlank()) complete++;
    return new PlaceCandidate(
        sourceId,
        title,
        outdoor,
        evidence,
        source + "+baidu-map",
        snapshot.queriedAt(),
        Math.min(1.0, Math.max(confidence, 0.75)),
        complete / 6.0,
        knowledge,
        snapshot);
  }

  public boolean closed() {
    if (poi == null) return false;
    String hours = poi.openingHours();
    return hours.contains("暂停营业")
        || hours.contains("永久关闭")
        || hours.contains("停止营业")
        || hours.contains("歇业");
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static double clamp(double value) {
    return Math.max(0, Math.min(1, value));
  }
}
