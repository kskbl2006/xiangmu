package com.github.wechat.ilink.bot.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Loads runtime settings from environment variables; credentials never belong in source control. */
public final class AppConfig {
  public static final String DEFAULT_DASHSCOPE_BASE_URL =
      "https://dashscope.aliyuncs.com/compatible-mode/v1";
  public static final String DEFAULT_DASHSCOPE_NATIVE_BASE_URL =
      "https://dashscope.aliyuncs.com/api/v1";

  private final String dashscopeApiKey;
  private final String qwenModel;
  private final String dashscopeBaseUrl;
  private AppConfig(
      String dashscopeApiKey, String qwenModel, String dashscopeBaseUrl) {
    this.dashscopeApiKey = dashscopeApiKey;
    this.qwenModel = qwenModel;
    this.dashscopeBaseUrl = dashscopeBaseUrl;
  }

  public static AppConfig fromEnvironment() {
    Properties localProperties = loadLocalProperties();
    return new AppConfig(
        value("DASHSCOPE_API_KEY", null, localProperties),
        value("QWEN_MODEL", "qwen3.8-max", localProperties),
        value("DASHSCOPE_BASE_URL", DEFAULT_DASHSCOPE_BASE_URL, localProperties));
  }

  public boolean hasDashscopeApiKey() {
    return dashscopeApiKey != null && !dashscopeApiKey.isEmpty();
  }

  public String requireDashscopeApiKey() {
    if (!hasDashscopeApiKey()) {
      throw new IllegalStateException("DASHSCOPE_API_KEY is required; configure it outside the project");
    }
    return dashscopeApiKey;
  }

  public String getQwenModel() {
    return qwenModel;
  }

  public String getDashscopeBaseUrl() {
    return dashscopeBaseUrl;
  }

  public String getDashscopeNativeBaseUrl() {
    return value(
        "DASHSCOPE_NATIVE_BASE_URL", DEFAULT_DASHSCOPE_NATIVE_BASE_URL, loadLocalProperties());
  }

  public String getQwenAsrModel() {
    return value("QWEN_ASR_MODEL", "qwen3-asr-flash", loadLocalProperties());
  }

  public String getQwenTtsModel() {
    return value("QWEN_TTS_MODEL", "qwen3-tts-flash", loadLocalProperties());
  }

  public String getQwenTtsVoice() {
    return value("QWEN_TTS_VOICE", "Cherry", loadLocalProperties());
  }

  public String getSeniverseApiBaseUrl() {
    return value(
        "SENIVERSE_API_BASE_URL", "https://api.seniverse.com/v3", loadLocalProperties());
  }

  public boolean hasSeniverseApiKey() {
    String key = getSeniverseApiKey();
    return key != null && !key.isBlank();
  }

  public String requireSeniverseApiKey() {
    String key = getSeniverseApiKey();
    if (key == null || key.isBlank()) {
      throw new IllegalStateException("SENIVERSE_API_KEY is required for Seniverse queries");
    }
    return key;
  }

  public boolean hasWeatherProvider() {
    return hasSeniverseApiKey();
  }

  private String getSeniverseApiKey() {
    return value("SENIVERSE_API_KEY", null, loadLocalProperties());
  }

  public boolean isWeChatAutoReplyEnabled() {
    return Boolean.parseBoolean(value("WECHAT_AUTO_REPLY_ENABLED", "true", loadLocalProperties()));
  }

  public String getWeChatAutoReplyText() {
    return value("WECHAT_AUTO_REPLY_TEXT", "已收到你的消息，微信收发链路正常。", loadLocalProperties());
  }

  public String getWeChatMissingApiReplyText() {
    return value(
        "WECHAT_MISSING_API_REPLY_TEXT",
        "机器人已连接微信，但未配置千问 API Key，当前无法使用智能回复。"
            + "请在项目根目录的 .env 文件或 IDEA 运行配置中添加 DASHSCOPE_API_KEY 后重启。",
        loadLocalProperties());
  }

  public boolean isWeChatLlmReplyEnabled() {
    return Boolean.parseBoolean(value("WECHAT_LLM_REPLY_ENABLED", "false", loadLocalProperties()));
  }

  public String getWeChatSystemPrompt() {
    return value(
        "WECHAT_SYSTEM_PROMPT",
        "你是一个微信助手。请使用简洁、自然的中文回答用户；若信息不足，请明确说明。",
        loadLocalProperties());
  }

  public boolean isRagEnabled() {
    return Boolean.parseBoolean(value("RAG_ENABLED", "true", loadLocalProperties()));
  }

  public int getRagTopK() {
    String configured = value("RAG_TOP_K", "2", loadLocalProperties());
    try {
      return Math.max(1, Math.min(5, Integer.parseInt(configured)));
    } catch (NumberFormatException e) {
      return 2;
    }
  }

  private static String value(String name, String defaultValue, Properties localProperties) {
    String value = System.getenv(name);
    if (value != null && !value.trim().isEmpty()) {
      return value.trim();
    }
    String localValue = localProperties.getProperty(name);
    if (localValue != null && !localValue.trim().isEmpty()) {
      return localValue.trim();
    }
    return defaultValue;
  }

  private static Properties loadLocalProperties() {
    Path envFile = Path.of(".env");
    if (!Files.isRegularFile(envFile)) {
      return new Properties();
    }
    try {
      return loadProperties(envFile);
    } catch (IOException e) {
      throw new IllegalStateException("Unable to read local .env file", e);
    }
  }

  static Properties loadProperties(Path propertiesFile) throws IOException {
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(propertiesFile, StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    return properties;
  }
}
