package com.wechat.bot.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 极简关键词检索知识库：RAG 的「检索（Retrieval）」环节。
 * <p>实现逻辑：
 * <ol>
 *   <li>启动时从 classpath 加载 knowledge-base.json 知识文档</li>
 *   <li>检索时对每个文档统计「用户问题包含其关键词的个数」作为相关度得分</li>
 *   <li>得分 &gt; 0 的文档按得分降序取 Top-K</li>
 * </ol>
 * <p>生产级 RAG 会用 Embedding 向量相似度替代关键词计数（本实现用于理解检索原理）。
 */
public class KeywordKnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(KeywordKnowledgeBase.class);

    private final List<KnowledgeDoc> docs = new ArrayList<>();

    /**
     * 从 classpath 加载知识库 JSON。
     * <p>格式：[{"id":"...","keywords":["..."],"question":"...","answer":"..."}]
     */
    public KeywordKnowledgeBase load(String resourcePath) {
        try (InputStream in = KeywordKnowledgeBase.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (in == null) {
                log.warn("知识库文件不存在，RAG 检索将始终为空：{}", resourcePath);
                return this;
            }
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(in);
            for (JsonNode node : root) {
                List<String> keywords = new ArrayList<>();
                node.path("keywords").forEach(k -> keywords.add(k.asText()));
                docs.add(new KnowledgeDoc(
                        node.path("id").asText(),
                        keywords.toArray(String[]::new),
                        node.path("question").asText(),
                        node.path("answer").asText()));
            }
            log.info("知识库加载完成：{} 个文档（来源 {}）", docs.size(), resourcePath);
        } catch (IOException e) {
            log.error("知识库加载失败：{}", resourcePath, e);
        }
        return this;
    }

    /** 知识文档总数 */
    public int size() {
        return docs.size();
    }

    /**
     * 关键词检索：返回相关文档（相关度降序）。
     *
     * @param question 用户问题
     * @param topK     最多返回条数
     */
    public List<KnowledgeDoc> search(String question, int topK) {
        record Scored(KnowledgeDoc doc, int score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (KnowledgeDoc doc : docs) {
            int score = 0;
            for (String keyword : doc.keywords()) {
                if (question.contains(keyword)) {
                    score++;
                }
            }
            if (score > 0) {
                scored.add(new Scored(doc, score));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        List<KnowledgeDoc> result = scored.stream().limit(topK).map(Scored::doc).toList();
        log.info("RAG 检索：「{}」命中 {} / {} 个文档，Top-{} 返回 {} 条",
                mask(question), scored.size(), docs.size(), topK, result.size());
        return result;
    }

    private String mask(String text) {
        return text.length() <= 20 ? text : text.substring(0, 20) + "...";
    }
}
