package com.github.wechat.ilink.bot;

import com.github.wechat.ilink.bot.config.AppConfig;
import com.github.wechat.ilink.bot.llm.QwenClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 手动密钥检查，仅发送一条短提示且不记录 API Key。 */
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
