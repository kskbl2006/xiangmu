package com.github.wechat.ilink.bot.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.wechat.ilink.sdk.core.context.ContextKey;
import com.github.wechat.ilink.sdk.core.context.ConversationContext;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 在版本控制外保存 iLink 登录信息、更新游标和会话令牌。 */
public final class WeChatSessionStore {
  private static final Logger log = LoggerFactory.getLogger(WeChatSessionStore.class);
  private static final EnumSet<PosixFilePermission> OWNER_ONLY =
      EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

  private final Path path;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public WeChatSessionStore(Path path) {
    this.path = path;
  }

  public synchronized ResumeContext load() {
    if (!Files.isRegularFile(path)) {
      return null;
    }
    try {
      JsonNode root = objectMapper.readTree(path.toFile());
      LoginContext login =
          new LoginContext(
              requiredText(root, "botToken"),
              requiredText(root, "userId"),
              requiredText(root, "botId"),
              requiredText(root, "baseUrl"));
      ResumeContext.Builder builder = ResumeContext.builder(login);
      JsonNode cursor = root.get("updatesCursor");
      if (cursor != null && cursor.isTextual() && !cursor.asText().isBlank()) {
        builder.updatesCursor(cursor.asText());
      }
      java.util.Map<String, ConversationContext> contexts = new java.util.LinkedHashMap<>();
      for (JsonNode node : root.path("conversationContexts")) {
        String botId = requiredText(node, "botId");
        String contextUserId = requiredText(node, "userId");
        ConversationContext context =
            new ConversationContext(new ContextKey(botId, contextUserId));
        String token = optionalText(node, "latestContextToken");
        Long sourceMessageId = optionalLong(node, "sourceMessageId");
        Long sourceMessageTime = optionalLong(node, "sourceMessageTime");
        context.updateContextToken(token, sourceMessageId, sourceMessageTime);
        context.setTypingTicket(optionalText(node, "typingTicket"));
        contexts.put(contextUserId, context);
      }
      builder.conversationContexts(contexts);
      log.info("Restored persisted WeChat session: botId={}, conversations={}", login.getBotId(), contexts.size());
      return builder.build();
    } catch (Exception e) {
      log.warn("Ignoring unreadable WeChat session file {}: {}", path, e.getMessage());
      return null;
    }
  }

  public synchronized void save(ResumeContext resumeContext) throws IOException {
    if (resumeContext == null || resumeContext.getLoginContext() == null) {
      return;
    }
    LoginContext login = resumeContext.getLoginContext();
    ObjectNode root = objectMapper.createObjectNode();
    root.put("botToken", login.getBotToken());
    root.put("userId", login.getUserId());
    root.put("botId", login.getBotId());
    root.put("baseUrl", login.getBaseUrl());
    if (resumeContext.getUpdatesCursor() != null) {
      root.put("updatesCursor", resumeContext.getUpdatesCursor());
    }
    ArrayNode contexts = root.putArray("conversationContexts");
    for (ConversationContext context : resumeContext.getConversationContexts()) {
      ObjectNode node = contexts.addObject();
      node.put("botId", context.getKey().getBotId());
      node.put("userId", context.getKey().getUserId());
      putOptional(node, "latestContextToken", context.getLatestContextToken());
      putOptional(node, "typingTicket", context.getTypingTicket());
      putOptional(node, "sourceMessageId", context.getSourceMessageId());
      putOptional(node, "sourceMessageTime", context.getSourceMessageTime());
    }

    Path parent = path.toAbsolutePath().getParent();
    Files.createDirectories(parent);
    Path temporary = Files.createTempFile(parent, "wechat-session-", ".tmp");
    try {
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), root);
      setOwnerOnlyPermissions(temporary);
      Files.move(
          temporary,
          path.toAbsolutePath(),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
      setOwnerOnlyPermissions(path.toAbsolutePath());
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  public synchronized void clear() throws IOException {
    Files.deleteIfExists(path);
  }

  private static String requiredText(JsonNode node, String field) throws IOException {
    String value = optionalText(node, field);
    if (value == null || value.isBlank()) {
      throw new IOException("missing session field: " + field);
    }
    return value;
  }

  private static String optionalText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static Long optionalLong(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asLong();
  }

  private static void putOptional(ObjectNode node, String field, String value) {
    if (value != null) {
      node.put(field, value);
    }
  }

  private static void putOptional(ObjectNode node, String field, Long value) {
    if (value != null) {
      node.put(field, value);
    }
  }

  private static void setOwnerOnlyPermissions(Path file) {
    try {
      Files.setPosixFilePermissions(file, OWNER_ONLY);
    } catch (UnsupportedOperationException | IOException e) {
      log.debug("Unable to apply POSIX owner-only permissions to {}: {}", file, e.getMessage());
    }
  }
}
