package com.github.wechat.ilink.bot.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.bot.rag.KeywordRagRetriever;
import com.github.wechat.ilink.bot.skill.BotSkill;
import com.github.wechat.ilink.bot.skill.SkillRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MessageRoutePlannerTest {
  private final KeywordRagRetriever retriever =
      KeywordRagRetriever.fromResource(new ObjectMapper(), "/rag/bot-knowledge.json");
  private final SkillRegistry skills = new SkillRegistry(List.of(selfCheckSkill()));

  @Test
  void followsSkillThenRagThenLlmPriority() {
    MessageRoutePlanner planner = new MessageRoutePlanner(skills, retriever, true, 2);

    assertEquals(
        MessageRoutePlanner.RouteType.SKILL,
        planner.plan("机器人自检，并告诉我怎么重新扫码").type());
    assertEquals(
        MessageRoutePlanner.RouteType.RAG,
        planner.plan("为什么机器人重启后不用重新扫码？").type());
    assertEquals(
        MessageRoutePlanner.RouteType.LLM,
        planner.plan("给我讲一个笑话").type());
  }

  @Test
  void ablationDisablesOnlyRagBranch() {
    MessageRoutePlanner ragOn = new MessageRoutePlanner(skills, retriever, true, 2);
    MessageRoutePlanner ragOff = new MessageRoutePlanner(skills, retriever, false, 2);
    String projectQuestion = "为什么机器人重启后不用重新扫码？";

    assertEquals(MessageRoutePlanner.RouteType.RAG, ragOn.plan(projectQuestion).type());
    assertEquals(MessageRoutePlanner.RouteType.LLM, ragOff.plan(projectQuestion).type());
    assertEquals(
        MessageRoutePlanner.RouteType.SKILL,
        ragOff.plan("机器人自检").type());
  }

  private static BotSkill selfCheckSkill() {
    return new BotSkill() {
      @Override public String name() { return "bot_self_check"; }
      @Override public String description() { return "test skill"; }
      @Override public Set<String> triggerKeywords() { return Set.of("机器人自检"); }
      @Override public String execute(String userMessage) { return "ok"; }
    };
  }
}
