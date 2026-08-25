package com.github.wechat.ilink.bot.rag;

import java.util.List;

/** One curated, single-topic knowledge chunk used by the keyword RAG baseline. */
public record KnowledgeDocument(
    String id, String title, List<String> keywords, String content, String source) {}
