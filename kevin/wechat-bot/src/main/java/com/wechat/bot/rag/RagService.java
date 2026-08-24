package com.wechat.bot.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * RAG 服务：检索增强生成的编排层。
 * <p>完整流程（检索 → 增强 → 生成 由上层 handler 配合 LlmClient 完成）：
 * <ol>
 *   <li>检索：KeywordKnowledgeBase 按关键词相关度召回 Top-K 文档</li>
 *   <li>增强：把文档正文拼进系统 Prompt，约束 LLM 按知识库内容回答</li>
 *   <li>生成：LLM 基于增强 Prompt 输出最终回复</li>
 * </ol>
 * <p>「是否命中 RAG」的判断：检索结果非空即视为命中（用于消息路由第二级）。
 */
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final KeywordKnowledgeBase knowledgeBase;
    private final boolean enabled;
    private final int topK;

    public RagService(KeywordKnowledgeBase knowledgeBase, boolean enabled, int topK) {
        this.knowledgeBase = knowledgeBase;
        this.enabled = enabled;
        this.topK = topK;
        log.info("RAG 服务初始化：enabled={}，topK={}，知识库 {} 条", enabled, topK, knowledgeBase.size());
    }

    /** RAG 总开关状态 */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 检索并判断是否命中：开关开启且检索到相关文档时返回 true。
     */
    public boolean hit(String question) {
        return enabled && !search(question).isEmpty();
    }

    /**
     * 关键词检索（受总开关控制：关闭时直接返回空）。
     */
    public List<KnowledgeDoc> search(String question) {
        if (!enabled) {
            return List.of();
        }
        return knowledgeBase.search(question, topK);
    }

    /**
     * 组装增强 Prompt（Augmentation）：把检索到的文档正文注入系统指令。
     * <p>未命中时原样返回默认系统 Prompt（与无 RAG 行为一致）。
     */
    public String augmentSystemPrompt(String defaultSystemPrompt, String question) {
        List<KnowledgeDoc> hits = search(question);
        if (hits.isEmpty()) {
            return defaultSystemPrompt;
        }
        StringBuilder sb = new StringBuilder(defaultSystemPrompt == null ? "" : defaultSystemPrompt);
        sb.append("\n\n以下是知识库中检索到的相关资料（请优先依据资料回答，")
          .append("资料未涉及的内容如实话说明，不要编造）：\n");
        for (int i = 1; i <= hits.size(); i++) {
            KnowledgeDoc doc = hits.get(i - 1);
            sb.append("【资料").append(i).append("】").append(doc.answer()).append('\n');
        }
        log.info("RAG 增强 Prompt：注入 {} 条资料，增加 {} 字符", hits.size(),
                sb.length() - (defaultSystemPrompt == null ? 0 : defaultSystemPrompt.length()));
        return sb.toString();
    }
}
