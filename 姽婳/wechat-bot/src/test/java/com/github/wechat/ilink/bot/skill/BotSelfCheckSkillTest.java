package com.github.wechat.ilink.bot.skill;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wechat.ilink.bot.config.AppConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BotSelfCheckSkillTest {
  @TempDir Path tempDir;

  @Test
  void matchesExplicitCommandsAndReportsChecksWithoutCredentialValues() throws Exception {
    Path session = tempDir.resolve("wechat-session.json");
    Files.writeString(session, "{}");
    BotSelfCheckSkill skill =
        new BotSelfCheckSkill(
            AppConfig.fromEnvironment(),
            List.of("weather_query", "calculator", "datetime_query"),
            session,
            () -> true);

    assertTrue(skill.matches("请执行机器人自检"));
    assertTrue(skill.matches("检查运行环境"));
    assertFalse(skill.matches("给我讲个笑话"));

    String result = skill.execute("机器人自检");
    assertTrue(result.contains("Bot 自检结果"));
    assertTrue(result.contains("FFmpeg 可用"));
    assertTrue(result.contains("微信会话文件存在"));
    assertTrue(result.contains("weather_query、calculator、datetime_query"));
    assertFalse(result.contains("sk-"));
  }
}
