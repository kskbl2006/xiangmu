package com.github.wechat.ilink.bot.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Deterministic keyword retrieval baseline; no embedding service or vector database is required. */
public final class KeywordRagRetriever {
  public record Hit(KnowledgeDocument document, int score, List<String> matchedKeywords) {}

  private final List<KnowledgeDocument> documents;

  public KeywordRagRetriever(List<KnowledgeDocument> documents) {
    if (documents == null || documents.isEmpty()) {
      throw new IllegalArgumentException("RAG knowledge documents must not be empty");
    }
    Set<String> ids = new HashSet<>();
    for (KnowledgeDocument document : documents) {
      if (document == null
          || document.id() == null
          || document.id().isBlank()
          || document.content() == null
          || document.content().isBlank()
          || document.keywords() == null
          || document.keywords().isEmpty()) {
        throw new IllegalArgumentException("RAG document fields must not be blank");
      }
      if (!ids.add(document.id())) {
        throw new IllegalArgumentException("duplicate RAG document id: " + document.id());
      }
    }
    this.documents = List.copyOf(documents);
  }

  public static KeywordRagRetriever fromResource(
      ObjectMapper objectMapper, String resourcePath) {
    try (InputStream input = KeywordRagRetriever.class.getResourceAsStream(resourcePath)) {
      if (input == null) throw new IllegalStateException("RAG resource not found: " + resourcePath);
      List<KnowledgeDocument> documents =
          objectMapper.readValue(input, new TypeReference<List<KnowledgeDocument>>() {});
      return new KeywordRagRetriever(documents);
    } catch (IOException e) {
      throw new IllegalStateException("Unable to load RAG resource: " + resourcePath, e);
    }
  }

  public List<Hit> search(String query, int limit) {
    if (query == null || query.isBlank() || limit <= 0) return List.of();
    String normalizedQuery = normalize(query);
    List<Hit> hits = new ArrayList<>();
    for (KnowledgeDocument document : documents) {
      List<String> matched = new ArrayList<>();
      int score = 0;
      for (String keyword : document.keywords()) {
        String normalizedKeyword = normalize(keyword);
        if (!normalizedKeyword.isBlank() && normalizedQuery.contains(normalizedKeyword)) {
          matched.add(keyword);
          score += 10 + normalizedKeyword.length();
        }
      }
      if (score > 0) hits.add(new Hit(document, score, List.copyOf(matched)));
    }
    return hits.stream()
        .sorted(
            Comparator.comparingInt(Hit::score)
                .reversed()
                .thenComparing(hit -> hit.document().id()))
        .limit(limit)
        .toList();
  }

  public int size() {
    return documents.size();
  }

  private static String normalize(String value) {
    return value
        .toLowerCase(Locale.ROOT)
        .replaceAll("[\\p{P}\\p{S}\\s]+", "")
        .trim();
  }
}
