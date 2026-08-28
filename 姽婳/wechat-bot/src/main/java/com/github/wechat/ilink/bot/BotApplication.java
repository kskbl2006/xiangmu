package com.github.wechat.ilink.bot;

import com.github.wechat.ilink.bot.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 应用入口；SDK 登录验证后接入微信事件路由。 */
public final class BotApplication {
  private static final Logger log = LoggerFactory.getLogger(BotApplication.class);

  private BotApplication() {}

  public static void main(String[] args) {
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, throwable) -> log.error("Uncaught error on thread {}", thread.getName(), throwable));

    AppConfig config = AppConfig.fromEnvironment();
    log.debug("Loaded runtime settings for model={}, baseUrl={}", config.getQwenModel(), config.getDashscopeBaseUrl());
    log.info("iLink Bot application initialized; Java runtime={}", System.getProperty("java.version"));
    if (!config.hasDashscopeApiKey()) {
      log.warn("DASHSCOPE_API_KEY is absent; LLM calls remain disabled until it is configured");
    }
  }
}
