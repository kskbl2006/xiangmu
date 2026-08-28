package com.github.wechat.ilink.bot.travelrag;

import java.util.List;

/** 可独立检索的旅行知识片段，包含预计算稠密向量。 */
public record TravelKnowledgeChunk(
    String id,
    String city,
    String type,
    String title,
    String text,
    String sourceStatus,
    List<Double> embedding) {

  public TravelKnowledgeChunk {
    embedding = embedding == null ? List.of() : List.copyOf(embedding);
  }
}
