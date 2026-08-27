package com.travel.agent.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量级 RAG 检索（BM25-lite，纯 JDK 实现）。
 *
 * - 中文按字符二元组（bigram）分词，无需外部分词依赖；
 * - relevance = 查询与文档的 bigram 重合度（0~1）；
 * - 供 poi 工具对本地旅行知识库做相关性排序。
 */
public final class Kb {

    private Kb() {
    }

    /** 中文按字符 bigram 计数。 */
    public static Map<String, Integer> bigrams(String text) {
        String t = text == null ? "" : text.replaceAll("\\s+", "");
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (int i = 0; i + 2 <= t.length(); i++) {
            counter.merge(t.substring(i, i + 2), 1, Integer::sum);
        }
        return counter;
    }

    /** 查询与文档的 bigram 重合度（0~1）。 */
    public static double relevance(String query, String doc) {
        Map<String, Integer> q = bigrams(query);
        Map<String, Integer> d = bigrams(doc);
        int qTotal = q.values().stream().mapToInt(Integer::intValue).sum();
        int dTotal = d.values().stream().mapToInt(Integer::intValue).sum();
        if (qTotal == 0 || dTotal == 0) {
            return 0.0;
        }
        int hit = 0;
        for (Map.Entry<String, Integer> e : q.entrySet()) {
            hit += Math.min(e.getValue(), d.getOrDefault(e.getKey(), 0));
        }
        return (double) hit / qTotal;
    }

    /** 对文档列表按相关性检索 TopK（score &gt; 0）。 */
    public static List<Map<String, Object>> search(String query, List<Map<String, Object>> docs,
                                                    List<String> textKeys, int k) {
        List<String> keys = (textKeys == null || textKeys.isEmpty())
                ? List.of("title", "tags", "desc") : textKeys;
        record Scored(double score, Map<String, Object> doc) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Map<String, Object> doc : docs) {
            StringBuilder sb = new StringBuilder();
            for (String key : keys) {
                sb.append(doc.get(key) == null ? "" : doc.get(key).toString()).append(' ');
            }
            scored.add(new Scored(relevance(query, sb.toString().trim()), doc));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Scored s : scored) {
            if (out.size() >= k) {
                break;
            }
            if (s.score() > 0) {
                out.add(s.doc());
            }
        }
        return out;
    }
}
