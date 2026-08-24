package com.wechat.bot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 应用配置加载器：从 classpath 的 application.properties 读取配置。
 * 支持
 */
public final class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    private final Properties props;

    private AppConfig(Properties props) {
        this.props = props;
    }

    public static AppConfig load() {
        Properties props = new Properties();
        try (InputStream in = AppConfig.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in == null) {
                throw new IllegalStateException("classpath 下未找到 application.properties");
            }
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("读取 application.properties 失败", e);
        }
        log.info("应用配置加载完成，共 {} 项", props.size());
        return new AppConfig(props);
    }

    public String get(String key, String defaultValue) {
        String v = props.getProperty(key, defaultValue);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        return v.trim();
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            log.warn("配置项 {} 不是合法整数，使用默认值 {}", key, defaultValue);
            return defaultValue;
        }
    }

    public boolean getBool(String key, boolean defaultValue) {
        return Boolean.parseBoolean(get(key, String.valueOf(defaultValue)));
    }

    /**
     * 获取 LLM API Key（自动清洗）。
     * <p>曾出现配置值混入中文/空白字符导致 OkHttp 抛出
     * "Unexpected char 0xe4 in Authorization value" 的问题，
     * 此处统一去除所有非可见 ASCII 字符。
     */
    public String llmApiKey() {
        String raw = get("llm.api-key", "");
        String cleaned = raw.replaceAll("[^\\x21-\\x7E]", "");
        if (!cleaned.equals(raw)) {
            log.warn("llm.api-key 含非法字符（中文/空白等，共 {} 个），已自动清洗；"
                    + "请检查 application.properties 中是否仍为占位符「你的API_KEY」或粘贴时混入中文", raw.length() - cleaned.length());
        }
        return cleaned;
    }

    /**
     * API Key 是否为合法的已配置状态（非空、非占位符）。
     */
    public boolean isLlmApiKeyValid() {
        String key = llmApiKey();
        return !key.isBlank() && !key.contains("API_KEY");
    }

    public String llmBaseUrl() {
        return get("llm.base-url", "https://open.bigmodel.cn/api/paas/v4");
    }

    public String llmChatModel() {
        return get("llm.chat-model", "glm-4-flash");
    }

    public String llmVisionModel() {
        return get("llm.vision-model", "glm-4v-flash");
    }

    public String llmImageModel() {
        return get("llm.image-model", "cogview-3-flash");
    }

    public String llmTtsModel() {
        return get("llm.tts-model", "cogtts");
    }

    public int llmTimeoutMs() {
        return getInt("llm.timeout-ms", 60000);
    }

    public boolean voiceReplyEnabled() {
        return getBool("voice.reply-enabled", false);
    }

    public String geocodingUrl() {
        return get("weather.geocoding-url", "https://geocoding-api.open-meteo.com/v1/search");
    }

    public String forecastUrl() {
        return get("weather.forecast-url", "https://api.open-meteo.com/v1/forecast");
    }

    public int maxToolRounds() {
        return getInt("bot.max-tool-rounds", 5);
    }

    public long typingMillis() {
        return getInt("bot.typing-millis", 1500);
    }

    /** RAG 总开关（关闭后消息路由跳过 RAG 层，用于对比测试） */
    public boolean ragEnabled() {
        return getBool("rag.enabled", true);
    }

    /** RAG 知识库文件（classpath 路径） */
    public String ragKnowledgeBase() {
        return get("rag.knowledge-base", "knowledge-base.json");
    }

    /** RAG 检索返回的最大文档数 */
    public int ragTopK() {
        return getInt("rag.top-k", 2);
    }
}
