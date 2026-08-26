package com.github.wechat.ilink.bot.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class SkillDefinitionTest {
  @Test
  void loadsBothPackagedSkills() {
    SkillDefinition planner = SkillDefinition.load("cn-trip-planner");
    SkillDefinition reviewer = SkillDefinition.load("travel-plan-review");

    assertEquals("cn-trip-planner", planner.name());
    assertEquals("travel-plan-review", reviewer.name());
    assertFalse(planner.description().isBlank());
    assertFalse(reviewer.description().isBlank());
  }
}
