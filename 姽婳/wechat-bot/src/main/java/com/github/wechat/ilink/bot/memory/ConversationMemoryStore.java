package com.github.wechat.ilink.bot.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Small per-user persistent memory containing only a bounded number of recent dialogue turns. */
public final class ConversationMemoryStore {
  public record Turn(String user, String assistant) {}

  private static final Logger log = LoggerFactory.getLogger(ConversationMemoryStore.class);
  private static final TypeReference<Map<String, List<Turn>>> MEMORY_TYPE =
      new TypeReference<Map<String, List<Turn>>>() {};

  private final Path path;
  private final int maxTurns;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Map<String, List<Turn>> turnsByUser = new LinkedHashMap<>();

  public ConversationMemoryStore(Path path, int maxTurns) {
    if (maxTurns < 1) {
      throw new IllegalArgumentException("maxTurns must be positive");
    }
    this.path = path;
    this.maxTurns = maxTurns;
    load();
  }

  public synchronized List<Turn> getRecentTurns(String userId) {
    return List.copyOf(turnsByUser.getOrDefault(userId, List.of()));
  }

  public synchronized void addTurn(String userId, String userText, String assistantText)
      throws IOException {
    if (userId == null || userId.isBlank() || userText == null || assistantText == null) {
      return;
    }
    List<Turn> turns = new ArrayList<>(turnsByUser.getOrDefault(userId, List.of()));
    turns.add(new Turn(userText, assistantText));
    if (turns.size() > maxTurns) {
      turns = new ArrayList<>(turns.subList(turns.size() - maxTurns, turns.size()));
    }
    turnsByUser.put(userId, turns);
    save();
  }

  public synchronized void clear(String userId) throws IOException {
    turnsByUser.remove(userId);
    save();
  }

  private void load() {
    if (!Files.isRegularFile(path)) {
      return;
    }
    try {
      Map<String, List<Turn>> loaded = objectMapper.readValue(path.toFile(), MEMORY_TYPE);
      for (Map.Entry<String, List<Turn>> entry : loaded.entrySet()) {
        List<Turn> turns = entry.getValue() == null ? List.of() : entry.getValue();
        int start = Math.max(0, turns.size() - maxTurns);
        turnsByUser.put(entry.getKey(), new ArrayList<>(turns.subList(start, turns.size())));
      }
      log.info("Loaded short-term conversation memory for {} user(s)", turnsByUser.size());
    } catch (Exception e) {
      log.warn("Ignoring unreadable conversation memory {}: {}", path, e.getMessage());
    }
  }

  private void save() throws IOException {
    Path absolute = path.toAbsolutePath();
    Files.createDirectories(absolute.getParent());
    Path temporary = Files.createTempFile(absolute.getParent(), "conversation-memory-", ".tmp");
    try {
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), turnsByUser);
      Files.move(
          temporary,
          absolute,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
