package com.github.wechat.ilink.bot.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class WeChatMessageRouterFallbackTest {
  @Test
  void reportsMissingApiKeyBeforeCheckingLlmSwitch() {
    assertEquals(
        "missing-key",
        WeChatMessageRouter.unavailableLlmReply(false, false, "missing-key", "disabled"));
  }

  @Test
  void usesFixedReplyWhenLlmIsDisabled() {
    assertEquals(
        "disabled",
        WeChatMessageRouter.unavailableLlmReply(true, false, "missing-key", "disabled"));
  }

  @Test
  void allowsLlmWhenConfigurationIsReady() {
    assertNull(WeChatMessageRouter.unavailableLlmReply(true, true, "missing-key", "disabled"));
  }
}
