package com.github.wechat.ilink.bot.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.wechat.ilink.bot.travelrag.InMemoryTravelVectorStore;
import com.github.wechat.ilink.bot.travelrag.TravelKnowledgeChunk;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 保存可在重试间复用的天气、RAG 和旅行证据。 */
public final class TravelCheckpointStore {
  private static final Logger log = LoggerFactory.getLogger(TravelCheckpointStore.class);
  private static final int MAX_ENTRIES = 20;
  private static final TypeReference<Map<String, Checkpoint>> TYPE = new TypeReference<>() {};

  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
  private final Path path;
  private final long ttlMillis;
  private final Clock clock;
  private final Map<String, Checkpoint> entries = new LinkedHashMap<>();

  public TravelCheckpointStore(Path path, Duration ttl) {
    this(path, ttl, Clock.systemUTC());
  }

  TravelCheckpointStore(Path path, Duration ttl, Clock clock) {
    this.path = path == null ? null : path.toAbsolutePath().normalize();
    this.ttlMillis = Math.max(1, ttl.toMillis());
    this.clock = clock;
    load();
  }

  public static TravelCheckpointStore disabled() {
    return new TravelCheckpointStore(null, Duration.ofHours(1));
  }

  public synchronized Optional<Evidence> find(String goal) {
    if (path == null) return Optional.empty();
    purgeExpired();
    Checkpoint checkpoint = entries.get(key(goal));
    if (checkpoint == null) return Optional.empty();
    List<InMemoryTravelVectorStore.Hit> hits =
        checkpoint.hits().stream().map(HitSnapshot::toHit).toList();
    return Optional.of(new Evidence(checkpoint.forecast(), hits, checkpoint.mapData()));
  }

  public synchronized void save(
      String goal,
      TravelForecast forecast,
      List<InMemoryTravelVectorStore.Hit> hits,
      TravelMapData mapData) {
    if (path == null) return;
    purgeExpired();
    if (entries.size() >= MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
    entries.put(
        key(goal),
        new Checkpoint(
            forecast,
            hits == null ? List.of() : hits.stream().map(HitSnapshot::fromHit).toList(),
            mapData,
            clock.millis()));
    persistQuietly();
  }

  private void purgeExpired() {
    long oldest = clock.millis() - ttlMillis;
    if (entries.entrySet().removeIf(entry -> entry.getValue().savedAtEpochMillis() < oldest)) {
      persistQuietly();
    }
  }

  private void load() {
    if (path == null || !Files.isRegularFile(path)) return;
    try {
      entries.putAll(mapper.readValue(path.toFile(), TYPE));
      purgeExpired();
    } catch (Exception e) {
      log.warn("Unable to load travel evidence checkpoints: {}", e.getMessage());
    }
  }

  private void persistQuietly() {
    if (path == null) return;
    try {
      Path parent = path.getParent();
      if (parent != null) Files.createDirectories(parent);
      Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
      mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), entries);
      try {
        Files.move(
            temporary,
            path,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException atomicFailure) {
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      log.warn("Unable to persist travel evidence checkpoints: {}", e.getMessage());
    }
  }

  private static String key(String goal) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((goal == null ? "" : goal.trim()).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  public record Evidence(
      TravelForecast forecast,
      List<InMemoryTravelVectorStore.Hit> hits,
      TravelMapData mapData) {
    public Evidence {
      hits = hits == null ? List.of() : List.copyOf(hits);
    }
  }

  private record Checkpoint(
      TravelForecast forecast,
      List<HitSnapshot> hits,
      TravelMapData mapData,
      long savedAtEpochMillis) {
    private Checkpoint {
      hits = hits == null ? List.of() : List.copyOf(hits);
    }
  }

  private record HitSnapshot(
      String id,
      String city,
      String type,
      String title,
      String text,
      String sourceStatus,
      double score) {
    private static HitSnapshot fromHit(InMemoryTravelVectorStore.Hit hit) {
      TravelKnowledgeChunk chunk = hit.chunk();
      return new HitSnapshot(
          chunk.id(),
          chunk.city(),
          chunk.type(),
          chunk.title(),
          chunk.text(),
          chunk.sourceStatus(),
          hit.score());
    }

    private InMemoryTravelVectorStore.Hit toHit() {
      return new InMemoryTravelVectorStore.Hit(
          new TravelKnowledgeChunk(id, city, type, title, text, sourceStatus, List.of()), score);
    }
  }
}
