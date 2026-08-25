package com.github.wechat.ilink.bot.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.wechat.ilink.sdk.core.context.ContextKey;
import com.github.wechat.ilink.sdk.core.context.ConversationContext;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WeChatSessionStoreTest {
  @TempDir Path temporaryDirectory;

  @Test
  void roundTripsLoginCursorAndConversationContext() throws Exception {
    LoginContext login =
        new LoginContext("secret-token", "owner@im.wechat", "bot@im.bot", "https://example.test");
    ConversationContext conversation =
        new ConversationContext(new ContextKey(login.getBotId(), "user@im.wechat"));
    conversation.updateContextToken("context-token", 42L, 1234L);
    conversation.setTypingTicket("typing-ticket");
    ResumeContext original =
        ResumeContext.builder(login)
            .updatesCursor("cursor-value")
            .conversationContexts(Map.of("user@im.wechat", conversation))
            .build();
    WeChatSessionStore store =
        new WeChatSessionStore(temporaryDirectory.resolve("wechat-session.json"));

    store.save(original);
    ResumeContext restored = store.load();

    assertNotNull(restored);
    assertEquals("secret-token", restored.getLoginContext().getBotToken());
    assertEquals("cursor-value", restored.getUpdatesCursor());
    ConversationContext restoredConversation =
        restored.getConversationContextMap().get("user@im.wechat");
    assertNotNull(restoredConversation);
    assertEquals("context-token", restoredConversation.getLatestContextToken());
    assertEquals(42L, restoredConversation.getSourceMessageId());
    assertEquals("typing-ticket", restoredConversation.getTypingTicket());
  }
}
