package com.github.wechat.ilink.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.bot.config.AppConfig;
import com.github.wechat.ilink.bot.llm.QwenClient;
import com.github.wechat.ilink.bot.rag.KeywordRagRetriever;
import com.github.wechat.ilink.bot.rag.RagPromptBuilder;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Real-model RAG on/off comparison; it does not connect to WeChat. */
public final class RagAblationSmokeTest {
  private static final Logger log = LoggerFactory.getLogger(RagAblationSmokeTest.class);

  private RagAblationSmokeTest() {}

  public static void main(String[] args) throws Exception {
    AppConfig config = AppConfig.fromEnvironment();
    QwenClient client = new QwenClient(config);
    KeywordRagRetriever retriever =
        KeywordRagRetriever.fromResource(new ObjectMapper(), "/rag/bot-knowledge.json");
    String question = "这个项目把微信登录会话保存在哪个文件？重启后什么时候需要重新扫码？";
    List<KeywordRagRetriever.Hit> hits = retriever.search(question, 2);
    if (hits.isEmpty()) throw new IllegalStateException("RAG did not retrieve session knowledge");

    String withoutRag = client.chat(question);
    String withRag = client.chat(RagPromptBuilder.enhance(question, hits));
    if (!withRag.contains("runtime/wechat-session.json")) {
      throw new IllegalStateException("RAG answer missed the verified session path: " + withRag);
    }
    log.info("RAG ablation [OFF] answer={}", withoutRag);
    log.info(
        "RAG ablation [ON] documents={}; answer={}",
        hits.stream().map(hit -> hit.document().id()).toList(),
        withRag);
  }
}
