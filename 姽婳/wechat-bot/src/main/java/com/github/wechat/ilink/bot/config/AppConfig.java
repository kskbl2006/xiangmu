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

  public String getQwenEmbeddingModel() {
    return value("QWEN_EMBEDDING_MODEL", "qwen3.7-text-embedding", loadLocalProperties());
  }

  public int getQwenEmbeddingDimension() {
    return intValue("QWEN_EMBEDDING_DIMENSION", 256, 256, 2_560);
  }

  public boolean isTravelRagEnabled() {
    return Boolean.parseBoolean(value("TRAVEL_RAG_ENABLED", "true", loadLocalProperties()));
  }

  public int getTravelRagTopK() {
    return intValue("TRAVEL_RAG_TOP_K", 4, 1, 8);
  }

  public double getTravelRagMinScore() {
    String configured = value("TRAVEL_RAG_MIN_SCORE", "0.35", loadLocalProperties());
    try {
      return Math.max(-1.0, Math.min(1.0, Double.parseDouble(configured)));
    } catch (NumberFormatException e) {
      return 0.35;
    }
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
    return true;
  }

  public String getOpenMeteoGeocodingUrl() {
    return value(
        "OPEN_METEO_GEOCODING_URL",
        "https://geocoding-api.open-meteo.com/v1/search",
        loadLocalProperties());
  }

  public String getOpenMeteoForecastUrl() {
    return value(
        "OPEN_METEO_FORECAST_URL",
        "https://api.open-meteo.com/v1/forecast",
        loadLocalProperties());
  }

  public boolean hasBaiduMapApiKey() {
    String key = getBaiduMapApiKey();
    return key != null && !key.isBlank();
  }

  public String requireBaiduMapApiKey() {
    String key = getBaiduMapApiKey();
    if (key == null || key.isBlank()) {
      throw new IllegalStateException("BAIDU_MAP_AK is required for Baidu Map queries");
    }
    return key;
  }

  public String getBaiduMapBaseUrl() {
    return value("BAIDU_MAP_BASE_URL", "https://api.map.baidu.com", loadLocalProperties());
  }

  public int getBaiduMapMinimumIntervalMillis() {
    return intValue("BAIDU_MAP_MIN_INTERVAL_MS", 600, 0, 5_000);
  }

  public int getBaiduMapMaxPoiQueries() {
    return intValue("BAIDU_MAP_MAX_POI_QUERIES", 8, 0, 21);
  }

  public String getTravelPdfFontPath() {
    return value("TRAVEL_PDF_FONT_PATH", "", loadLocalProperties());
  }

  private String getSeniverseApiKey() {
    return value("SENIVERSE_API_KEY", null, loadLocalProperties());
  }

  private String getBaiduMapApiKey() {
    return value("BAIDU_MAP_AK", null, loadLocalProperties());
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

  private static int intValue(String name, int defaultValue, int min, int max) {
    String configured = value(name, Integer.toString(defaultValue), loadLocalProperties());
    try {
      return Math.max(min, Math.min(max, Integer.parseInt(configured)));
    } catch (NumberFormatException e) {
      return defaultValue;
    }
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
