package com.github.wechat.ilink.bot.skill;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stores application-level skills and selects the first deterministic keyword match. */
public final class SkillRegistry {
  private final Map<String, BotSkill> skills = new LinkedHashMap<>();

  public SkillRegistry(Collection<? extends BotSkill> registeredSkills) {
    for (BotSkill skill : registeredSkills) {
      if (skill == null || skill.name() == null || skill.name().isBlank()) {
        throw new IllegalArgumentException("skill name must not be blank");
      }
      if (skill.triggerKeywords() == null || skill.triggerKeywords().isEmpty()) {
        throw new IllegalArgumentException("skill trigger keywords must not be empty: " + skill.name());
      }
      if (skills.putIfAbsent(skill.name(), skill) != null) {
        throw new IllegalArgumentException("duplicate skill name: " + skill.name());
      }
    }
  }

  public Optional<BotSkill> match(String userMessage) {
    return skills.values().stream().filter(skill -> skill.matches(userMessage)).findFirst();
  }

  public List<String> names() {
    return List.copyOf(skills.keySet());
  }
}
