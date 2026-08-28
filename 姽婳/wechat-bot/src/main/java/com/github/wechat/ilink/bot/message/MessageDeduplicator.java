package com.github.wechat.ilink.bot.message;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** iLink 消息 ID 的限量 TTL 缓存，用于过滤轮询重试产生的重复消息。 */
final class MessageDeduplicator {
  private final long ttlMillis;
  private final int maxEntries;
  private final LinkedHashMap<Long, Long> seenAt = new LinkedHashMap<>();

  MessageDeduplicator(Duration ttl, int maxEntries) {
    this.ttlMillis = ttl.toMillis();
    this.maxEntries = maxEntries;
  }

  synchronized boolean isDuplicate(Long messageId) {
    if (messageId == null) {
      return false;
    }
    long now = System.currentTimeMillis();
    Iterator<Map.Entry<Long, Long>> iterator = seenAt.entrySet().iterator();
    while (iterator.hasNext()) {
      if (now - iterator.next().getValue() > ttlMillis) {
        iterator.remove();
      } else {
        break;
      }
    }
    if (seenAt.containsKey(messageId)) {
      return true;
    }
    seenAt.put(messageId, now);
    while (seenAt.size() > maxEntries) {
      Iterator<Long> keys = seenAt.keySet().iterator();
      keys.next();
      keys.remove();
    }
    return false;
  }
}
