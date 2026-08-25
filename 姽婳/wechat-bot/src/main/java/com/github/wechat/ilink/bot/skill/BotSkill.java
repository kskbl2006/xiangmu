package com.github.wechat.ilink.bot.skill;

import java.util.Set;

/** A deterministic, reusable workflow that is triggered before RAG and open-ended chat. */
public interface BotSkill {
  String name();

  String description();

  Set<String> triggerKeywords();

  String execute(String userMessage) throws Exception;

  default boolean matches(String userMessage) {
    if (userMessage == null || userMessage.isBlank()) return false;
    String normalized = userMessage.trim().toLowerCase(java.util.Locale.ROOT);
    return triggerKeywords().stream()
        .map(keyword -> keyword.toLowerCase(java.util.Locale.ROOT))
        .anyMatch(normalized::contains);
  }
}
