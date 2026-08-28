package com.github.wechat.ilink.bot.travelrag;

import java.util.List;

/** 由 {@link TravelRagIndexBuilder} 生成的可序列化本地向量索引。 */
public record TravelVectorIndex(
    String model,
    int dimension,
    String generatedAt,
    String dataNotice,
    List<TravelKnowledgeChunk> chunks) {

  public TravelVectorIndex {
    chunks = chunks == null ? List.of() : List.copyOf(chunks);
  }
}
