package com.github.wechat.ilink.bot.message;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MessageDeduplicatorTest {
  @Test
  void rejectsRepeatedMessageIdButAllowsMissingIds() {
    MessageDeduplicator deduplicator = new MessageDeduplicator(Duration.ofMinutes(5), 10);
    assertFalse(deduplicator.isDuplicate(123L));
    assertTrue(deduplicator.isDuplicate(123L));
    assertFalse(deduplicator.isDuplicate(null));
    assertFalse(deduplicator.isDuplicate(null));
  }
}
