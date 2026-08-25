package com.github.wechat.ilink.bot;

import com.github.wechat.ilink.bot.config.AppConfig;
import com.github.wechat.ilink.bot.llm.QwenClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A manually-run credential check. It sends one short prompt and never logs the API key. */
public final class QwenSmokeTest {
  private static final Logger log = LoggerFactory.getLogger(QwenSmokeTest.class);

  private QwenSmokeTest() {}

  public static void main(String[] args) {
    try {
      AppConfig config = AppConfig.fromEnvironment();
      log.info("Starting isolated DashScope smoke test for model={}", config.getQwenModel());
      String reply = new QwenClient(config).smokeTest();
      log.info("DashScope smoke test succeeded; response={}", reply);
    } catch (Exception e) {
      log.error("DashScope smoke test failed: {}", e.getMessage());
      System.exit(1);
    }
  }
}
