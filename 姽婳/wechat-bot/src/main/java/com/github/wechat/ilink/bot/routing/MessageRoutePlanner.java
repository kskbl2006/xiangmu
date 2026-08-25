package com.github.wechat.ilink.bot.routing;

import com.github.wechat.ilink.bot.rag.KeywordRagRetriever;
import com.github.wechat.ilink.bot.skill.BotSkill;
import com.github.wechat.ilink.bot.skill.SkillRegistry;
import java.util.List;

/** Applies the required Skill -> RAG -> LLM routing order. */
public final class MessageRoutePlanner {
  public enum RouteType {
    SKILL,
    RAG,
    LLM
  }

  public record Decision(
      RouteType type, BotSkill skill, List<KeywordRagRetriever.Hit> ragHits) {}

  private final SkillRegistry skillRegistry;
  private final KeywordRagRetriever ragRetriever;
  private final boolean ragEnabled;
  private final int ragTopK;

  public MessageRoutePlanner(
      SkillRegistry skillRegistry,
      KeywordRagRetriever ragRetriever,
      boolean ragEnabled,
      int ragTopK) {
    this.skillRegistry = skillRegistry;
    this.ragRetriever = ragRetriever;
    this.ragEnabled = ragEnabled;
    this.ragTopK = ragTopK;
  }

  public Decision plan(String userMessage) {
    var skill = skillRegistry.match(userMessage);
    if (skill.isPresent()) {
      return new Decision(RouteType.SKILL, skill.get(), List.of());
    }
    if (ragEnabled) {
      List<KeywordRagRetriever.Hit> hits = ragRetriever.search(userMessage, ragTopK);
      if (!hits.isEmpty()) return new Decision(RouteType.RAG, null, hits);
    }
    return new Decision(RouteType.LLM, null, List.of());
  }
}
