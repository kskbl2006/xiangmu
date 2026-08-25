package com.github.wechat.ilink.bot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class AppConfigTest {
  @Test
  void readsUtf8ChineseFromLocalEnvFile() throws Exception {
    Path envFile = Files.createTempFile("ilink-bot", ".env");
    try {
      Files.writeString(envFile, "WECHAT_AUTO_REPLY_TEXT=已收到你的消息", StandardCharsets.UTF_8);

      Properties properties = AppConfig.loadProperties(envFile);

      assertEquals("已收到你的消息", properties.getProperty("WECHAT_AUTO_REPLY_TEXT"));
    } finally {
      Files.deleteIfExists(envFile);
    }
  }
}
