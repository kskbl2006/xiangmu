package com.wechat.bot.rag;

/**
 * 知识文档：RAG 知识库的最小检索单元。
 *
 * @param id       文档唯一标识
 * @param keywords 触发关键词：与用户问题匹配（包含）即认为文档相关
 * @param question 文档对应的典型问题（便于人工核对检索结果）
 * @param answer   文档正文：拼进增强 Prompt，作为 LLM 回答的事实依据
 */
public record KnowledgeDoc(String id, String[] keywords, String question, String answer) {
}
