package com.github.wechat.ilink.bot.travelrag;

import java.util.List;

/** One independently retrievable travel fact with its precomputed dense vector. */
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
