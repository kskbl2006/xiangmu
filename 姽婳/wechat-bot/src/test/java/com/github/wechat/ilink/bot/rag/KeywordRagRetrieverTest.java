package com.github.wechat.ilink.bot.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeywordRagRetrieverTest {
  private final KeywordRagRetriever retriever =
      KeywordRagRetriever.fromResource(new ObjectMapper(), "/rag/bot-knowledge.json");

  @Test
  void retrievesCuratedSessionKnowledgeAndBuildsGroundedPrompt() {
    List<KeywordRagRetriever.Hit> hits =
        retriever.search("为什么机器人重启后不用重新扫码？", 2);

    assertFalse(hits.isEmpty());
    assertEquals("wechat-session", hits.get(0).document().id());
    String enhanced = RagPromptBuilder.enhance("为什么重启不用扫码？", hits);
    assertTrue(enhanced.contains("runtime/wechat-session.json"));
    assertTrue(enhanced.contains("用户原始问题"));
  }

  @Test
  void doesNotRetrieveForOpenEndedChat() {
    assertTrue(retriever.search("给我讲一个关于小猫的笑话", 2).isEmpty());
  }

  @Test
  void limitsAndOrdersHitsByKeywordScore() {
    List<KeywordRagRetriever.Hit> hits =
        retriever.search("请解释串行调用、并行调用和多工具协作", 1);
    assertEquals(1, hits.size());
    assertEquals("tool-cooperation", hits.get(0).document().id());
    assertTrue(hits.get(0).matchedKeywords().size() >= 2);
  }
}
