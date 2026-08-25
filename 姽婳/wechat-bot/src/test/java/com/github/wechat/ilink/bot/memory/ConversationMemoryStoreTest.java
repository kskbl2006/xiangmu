package com.github.wechat.ilink.bot.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationMemoryStoreTest {
  @TempDir Path temporaryDirectory;

  @Test
  void keepsBoundedTurnsAndRestoresThem() throws Exception {
    Path file = temporaryDirectory.resolve("memory.json");
    ConversationMemoryStore store = new ConversationMemoryStore(file, 2);
    store.addTurn("user", "one", "a");
    store.addTurn("user", "two", "b");
    store.addTurn("user", "three", "c");

    ConversationMemoryStore restored = new ConversationMemoryStore(file, 2);

    assertEquals(2, restored.getRecentTurns("user").size());
    assertEquals("two", restored.getRecentTurns("user").getFirst().user());
    assertEquals("three", restored.getRecentTurns("user").getLast().user());
  }
}
