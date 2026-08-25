package com.github.wechat.ilink.bot.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class IntentRecognizerTest {
  private final IntentRecognizer recognizer = new IntentRecognizer();

  @Test
  void recognizesWeatherAndExtractsLocation() {
    IntentRecognizer.Result result = recognizer.recognize("北京今天天气怎么样？");
    assertEquals(IntentRecognizer.Intent.WEATHER, result.intent());
    assertEquals("北京", result.location());
  }

  @Test
  void asksForLocationWhenOnlyWeatherIsProvided() {
    IntentRecognizer.Result result = recognizer.recognize("查一下天气");
    assertEquals(IntentRecognizer.Intent.WEATHER, result.intent());
    assertNull(result.location());
  }

  @Test
  void recognizesRainQuestion() {
    IntentRecognizer.Result result = recognizer.recognize("上海明天会下雨吗");
    assertEquals(IntentRecognizer.Intent.WEATHER, result.intent());
    assertEquals("上海", result.location());
  }

  @Test
  void leavesGeneralConversationForLlm() {
    assertEquals(IntentRecognizer.Intent.CHAT, recognizer.recognize("给我讲个笑话").intent());
  }

  @Test
  void acceptsGreetingBeforeWeatherQuestion() {
    IntentRecognizer.Result result = recognizer.recognize("你好，今天天气如何");
    assertEquals(IntentRecognizer.Intent.WEATHER, result.intent());
    assertNull(result.location());
  }

  @Test
  void acceptsConversationalWeatherQuestion() {
    IntentRecognizer.Result result = recognizer.recognize("那苏州市的天气呢");
    assertEquals(IntentRecognizer.Intent.WEATHER, result.intent());
    assertEquals("苏州市", result.location());
  }

  @Test
  void extractsLocationFromFollowUpReply() {
    assertEquals("常州市新北区", recognizer.extractLocationReply("我在常州市新北区"));
    assertEquals("苏州市", recognizer.extractLocationReply("那苏州市呢"));
  }
}
