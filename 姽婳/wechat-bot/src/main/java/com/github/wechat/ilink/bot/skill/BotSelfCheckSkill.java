package com.github.wechat.ilink.bot.skill;

import com.github.wechat.ilink.bot.config.AppConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Checks the local Bot runtime without exposing credential values. */
public final class BotSelfCheckSkill implements BotSkill {
  private final AppConfig config;
  private final List<String> toolNames;
  private final Path sessionFile;
  private final BooleanSupplier ffmpegAvailable;

  public BotSelfCheckSkill(AppConfig config, List<String> toolNames, Path sessionFile) {
    this(config, toolNames, sessionFile, BotSelfCheckSkill::detectFfmpeg);
  }

  BotSelfCheckSkill(
      AppConfig config,
      List<String> toolNames,
      Path sessionFile,
      BooleanSupplier ffmpegAvailable) {
    this.config = config;
    this.toolNames = List.copyOf(toolNames);
    this.sessionFile = sessionFile;
    this.ffmpegAvailable = ffmpegAvailable;
  }

  @Override
  public String name() {
    return "bot_self_check";
  }

  @Override
  public String description() {
    return "检查 Bot 的 Java、模型、天气、FFmpeg、微信会话和工具注册状态。";
  }

  @Override
  public Set<String> triggerKeywords() {
    return Set.of("机器人自检", "bot自检", "系统自检", "检查运行环境", "检查配置");
  }

  @Override
  public String execute(String userMessage) {
    int javaFeature = Runtime.version().feature();
    boolean javaReady = javaFeature >= 21;
    boolean qwenReady = config.hasDashscopeApiKey();
    boolean weatherReady = config.hasWeatherProvider();
    boolean replyEnabled = config.isWeChatLlmReplyEnabled();
    boolean ffmpegReady = ffmpegAvailable.getAsBoolean();
    boolean sessionReady = Files.isRegularFile(sessionFile);

    StringBuilder result = new StringBuilder("Bot 自检结果：\n");
    append(result, javaReady, "Java " + javaFeature, "需要 Java 21 或更高版本");
    append(result, qwenReady, "千问 API 已配置", "未配置 DASHSCOPE_API_KEY");
    append(result, weatherReady, "心知天气已配置", "未配置 SENIVERSE_API_KEY");
    append(result, replyEnabled, "LLM 微信回复已开启", "WECHAT_LLM_REPLY_ENABLED 未开启");
    append(result, ffmpegReady, "FFmpeg 可用", "FFmpeg 不可用，语音转码可能失败");
    append(result, sessionReady, "微信会话文件存在", "微信会话文件不存在，首次启动需要扫码");
    result.append("✓ 已注册工具：").append(String.join("、", toolNames)).append('\n');
    long warnings =
        List.of(javaReady, qwenReady, weatherReady, replyEnabled, ffmpegReady, sessionReady)
            .stream()
            .filter(value -> !value)
            .count();
    result.append(warnings == 0 ? "结论：运行条件完整。" : "结论：发现 " + warnings + " 项需要处理。");
    return result.toString();
  }

  private static void append(
      StringBuilder target, boolean success, String successText, String failureText) {
    target.append(success ? "✓ " : "⚠ ").append(success ? successText : failureText).append('\n');
  }

  private static boolean detectFfmpeg() {
    try {
      Process process =
          new ProcessBuilder("ffmpeg", "-version")
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return false;
      }
      return process.exitValue() == 0;
    } catch (Exception ignored) {
      return false;
    }
  }
}
