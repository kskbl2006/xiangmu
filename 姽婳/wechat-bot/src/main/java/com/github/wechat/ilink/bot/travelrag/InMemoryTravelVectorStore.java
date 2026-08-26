package com.github.wechat.ilink.bot.travelrag;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;

/** Exact cosine search over the small bundled travel index. */
public final class InMemoryTravelVectorStore {
  public record Hit(TravelKnowledgeChunk chunk, double score) {}

  private final TravelVectorIndex index;

  public InMemoryTravelVectorStore(TravelVectorIndex index) {
    if (index == null || index.chunks().isEmpty()) {
      throw new IllegalArgumentException("travel vector index must contain chunks");
    }
    for (TravelKnowledgeChunk chunk : index.chunks()) {
      if (chunk.embedding().size() != index.dimension()) {
        throw new IllegalArgumentException("invalid vector dimension for chunk " + chunk.id());
      }
    }
    this.index = index;
  }

  public static InMemoryTravelVectorStore fromResource(
      ObjectMapper objectMapper, String resourcePath) {
    try (InputStream input = InMemoryTravelVectorStore.class.getResourceAsStream(resourcePath)) {
      if (input == null) {
        throw new IllegalStateException("travel vector resource is missing: " + resourcePath);
      }
      return new InMemoryTravelVectorStore(objectMapper.readValue(input, TravelVectorIndex.class));
    } catch (IOException e) {
      throw new IllegalStateException("unable to load travel vector index", e);
    }
  }

  public List<Hit> search(
      List<Double> queryVector, String cityFilter, int topK, double minScore) {
    if (queryVector == null || queryVector.size() != index.dimension()) {
      throw new IllegalArgumentException("query vector dimension does not match index");
    }
    return index.chunks().stream()
        .filter(chunk -> cityFilter == null || cityFilter.equals(chunk.city()))
        .map(chunk -> new Hit(chunk, cosine(queryVector, chunk.embedding())))
        .filter(hit -> hit.score() >= minScore)
        .sorted(Comparator.comparingDouble(Hit::score).reversed())
        .limit(Math.max(1, topK))
        .toList();
  }

  public int size() {
    return index.chunks().size();
  }

  public String model() {
    return index.model();
  }

  public int dimension() {
    return index.dimension();
  }

  private static double cosine(List<Double> left, List<Double> right) {
    double dot = 0;
    double leftNorm = 0;
    double rightNorm = 0;
    for (int index = 0; index < left.size(); index++) {
      double a = left.get(index);
      double b = right.get(index);
      dot += a * b;
      leftNorm += a * a;
      rightNorm += b * b;
    }
    if (leftNorm == 0 || rightNorm == 0) return -1;
    return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
  }
}
