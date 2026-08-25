package com.github.wechat.ilink.bot;

import com.github.wechat.ilink.bot.config.AppConfig;
import com.github.wechat.ilink.bot.memory.ConversationMemoryStore;
import com.github.wechat.ilink.bot.message.WeChatMessageRouter;
import com.github.wechat.ilink.bot.session.WeChatSessionStore;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.ILinkClientBuilder;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manual iLink login and message-loop entry point.
 *
 * <p>It receives messages by default. Set WECHAT_AUTO_REPLY_ENABLED=true only after confirming
 * that your account can receive messages through iLink.
 */
public final class WeChatBotApplication {
  private static final Logger log = LoggerFactory.getLogger(WeChatBotApplication.class);

  private WeChatBotApplication() {}

  public static void main(String[] args) throws Exception {
    AppConfig appConfig = AppConfig.fromEnvironment();
    log.info(
        "WeChat reply configuration: autoReplyEnabled={}, llmReplyEnabled={}, dashscopeConfigured={}",
        appConfig.isWeChatAutoReplyEnabled(),
        appConfig.isWeChatLlmReplyEnabled(),
        appConfig.hasDashscopeApiKey());
    if (!appConfig.hasDashscopeApiKey()) {
      log.warn("DASHSCOPE_API_KEY is absent; incoming messages will receive a configuration notice.");
    }
    AtomicReference<ILinkClient> clientRef = new AtomicReference<ILinkClient>();
    ILinkConfig iLinkConfig = ILinkConfig.builder().heartbeatEnabled(false).build();
    WeChatSessionStore sessionStore =
        new WeChatSessionStore(Path.of("runtime", "wechat-session.json"));
    ResumeContext restoredSession = sessionStore.load();
    ConversationMemoryStore memory =
        new ConversationMemoryStore(Path.of("runtime", "conversation-memory.json"), 8);
    WeChatMessageRouter messageRouter =
        new WeChatMessageRouter(clientRef, appConfig, memory);
    ILinkClientBuilder clientBuilder =
        ILinkClient.builder()
            .config(iLinkConfig)
            .resumeContext(restoredSession)
            .onLogin(
                new OnLoginListener() {
                  @Override
                  public void onLoginSuccess(LoginContext context) {
                    log.info("WeChat iLink login succeeded; botId={}", context.getBotId());
                  }

                  @Override
                  public void onLoginFailure(Throwable throwable) {
                    log.error("WeChat iLink login failed: {}", throwable.getMessage());
                  }
                })
            .onMessage(messageRouter);

    try (messageRouter; ILinkClient client = clientBuilder.build()) {
      clientRef.set(client);
      if (restoredSession == null) {
        loginUntilAuthorized(client);
        sessionStore.save(client.exportResumeContext());
      } else {
        log.info("Using persisted WeChat login; QR authorization is not required.");
      }
      log.info("Listening for WeChat messages. Stop this process with Ctrl+C when finished.");

      int consecutiveFailures = 0;
      while (!Thread.currentThread().isInterrupted()) {
        try {
          client.getUpdates();
          sessionStore.save(client.exportResumeContext());
          consecutiveFailures = 0;
        } catch (Exception e) {
          if (isSessionExpired(e)) {
            sessionStore.clear();
            log.error("WeChat session expired; persisted credentials were cleared. Restart to scan a new QR code.");
            return;
          }
          consecutiveFailures++;
          long delayMillis = Math.min(30_000L, 1_000L << Math.min(consecutiveFailures, 5));
          log.warn(
              "WeChat polling failed (attempt {}); retrying in {} ms: {}",
              consecutiveFailures,
              delayMillis,
              e.getMessage());
          Thread.sleep(delayMillis);
        }
      }
    } finally {
      clientRef.set(null);
    }
  }

  private static boolean isSessionExpired(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      String message = current.getMessage();
      if (message == null) continue;
      String normalized = message.toLowerCase(java.util.Locale.ROOT);
      if (normalized.contains("-14")
          || normalized.contains("session expired")
          || normalized.contains("session timeout")) {
        return true;
      }
    }
    return false;
  }

  private static void loginUntilAuthorized(ILinkClient client) throws Exception {
    while (true) {
      String qrCodeContent = client.executeLogin();
      QrCodePresenter.present(qrCodeContent);
      log.info("Use WeChat to scan and confirm the login QR code; waiting for authorization.");
      try {
        client.getLoginFuture().get();
        return;
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        log.warn(
            "Login QR code was not confirmed ({}); requesting a new QR code.",
            cause == null ? "unknown failure" : cause.getMessage());
      }
    }
  }

}
