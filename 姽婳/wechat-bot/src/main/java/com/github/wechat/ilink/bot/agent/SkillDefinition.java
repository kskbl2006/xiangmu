package com.github.wechat.ilink.bot.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads the packaged Agent Skill metadata so runtime code and SKILL.md cannot drift silently. */
public record SkillDefinition(String name, String description, String body) {
  private static final Pattern NAME = Pattern.compile("(?m)^name:\\s*(.+?)\\s*$");
  private static final Pattern DESCRIPTION = Pattern.compile("(?m)^description:\\s*(.+?)\\s*$");

  public static SkillDefinition load(String skillName) {
    String resource = "/skills/" + skillName + "/SKILL.md";
    try (InputStream input = SkillDefinition.class.getResourceAsStream(resource)) {
      if (input == null) throw new IllegalStateException("Missing packaged skill: " + resource);
      String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      String name = required(NAME, content, "name", resource);
      String description = required(DESCRIPTION, content, "description", resource);
      if (!skillName.equals(name)) {
        throw new IllegalStateException("Skill folder/name mismatch for " + resource);
      }
      return new SkillDefinition(name, description, content);
    } catch (IOException e) {
      throw new IllegalStateException("Unable to load packaged skill " + resource, e);
    }
  }

  private static String required(Pattern pattern, String content, String field, String resource) {
    Matcher matcher = pattern.matcher(content);
    if (!matcher.find() || matcher.group(1).isBlank()) {
      throw new IllegalStateException("Skill " + resource + " is missing " + field);
    }
    return matcher.group(1).trim();
  }
}
