package com.github.wechat.ilink.bot.travelrag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TravelRagServiceTest {
  @Test
  void recognizesTravelRequestsAndExtractsSupportedCity() {
    assertTrue(TravelRagService.isTravelRequest("帮我做一个苏州三日游行程"));
    assertTrue(TravelRagService.isTravelRequest("推荐几个适合亲子的景点"));
    assertTrue(TravelRagService.isTravelRequest("从苏州去上海当天往返多久"));
    assertFalse(TravelRagService.isTravelRequest("给我讲个笑话"));
    assertFalse(TravelRagService.isTravelRequest("上海的 GDP 是多少"));
    assertFalse(TravelRagService.isTravelRequest("杭州亚运会是哪一年"));
    assertEquals("苏州", TravelRagService.detectCity("苏州园林怎么玩"));
    assertTrue(TravelRagService.mentionsUnsupportedCity("北京三日游"));
    assertFalse(TravelRagService.mentionsUnsupportedCity("上海三日游"));
  }

  @Test
  void promptDisclosesDemoDataLimitations() {
    TravelKnowledgeChunk chunk =
        new TravelKnowledgeChunk(
            "a", "上海", "attraction", "外滩", "外滩适合观赏夜景", "demo_unverified", List.of());
    String prompt =
        TravelRagService.enhancePrompt(
            "上海夜景去哪", List.of(new InMemoryTravelVectorStore.Hit(chunk, 0.8)));
    assertTrue(prompt.contains("未经实时核验"));
    assertTrue(prompt.contains("外滩适合观赏夜景"));
    assertTrue(prompt.contains("上海夜景去哪"));
  }

  @Test
  void constraintRerankingPrefersIndoorKnowledgeForRainyTrips() {
    TravelKnowledgeChunk museum =
        new TravelKnowledgeChunk(
            "museum", "上海", "attraction", "上海博物馆", "室内博物馆展览", "test", List.of());
    TravelKnowledgeChunk park =
        new TravelKnowledgeChunk(
            "park", "上海", "attraction", "野生动物园", "大型野生动物园", "test", List.of());

    assertTrue(
        TravelRagService.constraintBonus("上海雨天带孩子去哪", museum)
            > TravelRagService.constraintBonus("上海雨天带孩子去哪", park));
  }
}
