package com.github.wechat.ilink.bot.travelrag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.bot.config.AppConfig;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Real-embedding retrieval evaluation: lexical baseline vs dense vs dense plus constraints. */
public final class TravelRagEvaluation {
  private static final int TOP_K = 4;

  private TravelRagEvaluation() {}

  public static void main(String[] args) throws Exception {
    AppConfig config = AppConfig.fromEnvironment();
    ObjectMapper mapper = new ObjectMapper();
    TravelVectorIndex index;
    try (InputStream input =
        TravelRagEvaluation.class.getResourceAsStream("/travel-rag/travel-rag-index.json")) {
      if (input == null) throw new IllegalStateException("travel index resource is missing");
      index = mapper.readValue(input, TravelVectorIndex.class);
    }
    List<EvalCase> cases = evaluationCases();
    long startedAt = System.nanoTime();
    List<List<Double>> queryVectors =
        new QwenEmbeddingClient(config).embedAll(cases.stream().map(EvalCase::query).toList());
    long embeddingMillis = (System.nanoTime() - startedAt) / 1_000_000L;
    InMemoryTravelVectorStore store = new InMemoryTravelVectorStore(index);

    Metrics lexical = new Metrics("关键词");
    Metrics dense = new Metrics("纯向量");
    Metrics hybrid = new Metrics("向量+约束");
    for (int caseIndex = 0; caseIndex < cases.size(); caseIndex++) {
      EvalCase eval = cases.get(caseIndex);
      List<InMemoryTravelVectorStore.Hit> lexicalHits = lexicalSearch(index, eval);
      List<InMemoryTravelVectorStore.Hit> denseCandidates =
          store.search(
              queryVectors.get(caseIndex),
              eval.city(),
              store.size(),
              config.getTravelRagMinScore());
      List<InMemoryTravelVectorStore.Hit> denseHits = denseCandidates.stream().limit(TOP_K).toList();
      List<InMemoryTravelVectorStore.Hit> hybridHits =
          denseCandidates.stream()
              .map(
                  hit ->
                      new InMemoryTravelVectorStore.Hit(
                          hit.chunk(),
                          hit.score()
                              + TravelRagService.constraintBonus(eval.query(), hit.chunk())))
              .sorted(Comparator.comparingDouble(InMemoryTravelVectorStore.Hit::score).reversed())
              .limit(TOP_K)
              .toList();
      lexical.record(eval, lexicalHits);
      dense.record(eval, denseHits);
      hybrid.record(eval, hybridHits);
      System.out.printf(
          Locale.ROOT,
          "%s\t%s\t%s\t%s%n",
          firstRelevantRank(hybridHits, eval.expectedIds()) > 0 ? "PASS" : "MISS",
          eval.id(),
          eval.query(),
          hybridHits.stream().map(hit -> hit.chunk().id()).toList());
    }

    System.out.println();
    lexical.print(cases.size());
    dense.print(cases.size());
    hybrid.print(cases.size());
    System.out.printf(
        Locale.ROOT,
        "Embedding batch latency: %d ms, %.1f ms/query%n",
        embeddingMillis,
        embeddingMillis / (double) cases.size());
    if (hybrid.hitAt4 < Math.ceil(cases.size() * 0.85) || hybrid.hitAt4 < dense.hitAt4) {
      throw new IllegalStateException("travel retrieval quality gate failed");
    }
  }

  private static List<InMemoryTravelVectorStore.Hit> lexicalSearch(
      TravelVectorIndex index, EvalCase eval) {
    Set<String> queryBigrams = bigrams(eval.query());
    return index.chunks().stream()
        .filter(chunk -> eval.city() == null || eval.city().equals(chunk.city()))
        .map(
            chunk -> {
              Set<String> documentBigrams = bigrams(chunk.title() + chunk.text());
              long overlap = queryBigrams.stream().filter(documentBigrams::contains).count();
              double score = queryBigrams.isEmpty() ? 0 : overlap / (double) queryBigrams.size();
              return new InMemoryTravelVectorStore.Hit(chunk, score);
            })
        .sorted(Comparator.comparingDouble(InMemoryTravelVectorStore.Hit::score).reversed())
        .limit(TOP_K)
        .toList();
  }

  private static Set<String> bigrams(String value) {
    String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsHan}a-z0-9]", "");
    Set<String> result = new HashSet<>();
    if (normalized.length() == 1) result.add(normalized);
    for (int index = 0; index + 1 < normalized.length(); index++) {
      result.add(normalized.substring(index, index + 2));
    }
    return result;
  }

  private static int firstRelevantRank(
      List<InMemoryTravelVectorStore.Hit> hits, Set<String> expectedIds) {
    for (int index = 0; index < hits.size(); index++) {
      if (expectedIds.contains(hits.get(index).chunk().id())) return index + 1;
    }
    return 0;
  }

  private static List<EvalCase> evaluationCases() {
    List<EvalCase> cases = new ArrayList<>();
    cases.add(eval("E01", "上海", "魔都哪里能看浦江两岸的老建筑和夜景", "attraction:shanghai-bund", "rule:rule-007"));
    cases.add(eval("E02", "上海", "想登高看看上海的摩天楼天际线", "attraction:shanghai-dongfangmingzhu", "attraction:shanghai-lujiazui"));
    cases.add(eval("E03", "上海", "上海下雨天带孩子适合去哪里", "attraction:shanghai-museum", "attraction:shanghai-history-museum"));
    cases.add(eval("E04", "上海", "带娃看动物还能坐车进入猛兽区", "attraction:shanghai-wildlife"));
    cases.add(eval("E05", "上海", "想逛保留石库门肌理的创意小巷", "attraction:shanghai-tianzifang"));
    cases.add(eval("E06", "杭州", "杭州预算很低，想找免费景点", "rule:rule-004", "attraction:hangzhou-xihu"));
    cases.add(eval("E07", "杭州", "杭州想去寺庙祈福顺便看山景", "attraction:hangzhou-lingyin", "attraction:hangzhou-faxi-temple", "cluster:hangzhou-lingyin-tianti"));
    cases.add(eval("E08", "杭州", "想去杭州水乡湿地坐摇橹船", "attraction:hangzhou-xixi", "rule:rule-027", "cluster:hangzhou-water-eco"));
    cases.add(eval("E09", "杭州", "杭州哪里可以登塔俯瞰西湖和夕阳", "attraction:hangzhou-leifeng-pagoda"));
    cases.add(eval("E10", "杭州", "想看宋代主题的大型演出", "attraction:hangzhou-songcity"));
    cases.add(eval("E11", "苏州", "苏州两日游想看园林和博物馆，路线别太绕", "rule:rule-012", "cluster:suzhou-garden-ancient-city"));
    cases.add(eval("E12", "苏州", "苏州那座贝聿铭设计的建筑怎么预约", "attraction:suzhou-suzhou-museum", "rule:rule-001"));
    cases.add(eval("E13", "苏州", "哪个苏州园林有像迷宫一样的假山", "attraction:suzhou-lion-grove"));
    cases.add(eval("E14", "苏州", "苏州晚上想去湖边看喷泉和现代夜景", "attraction:suzhou-jinji-lake", "rule:rule-024", "cluster:suzhou-jiulong-jinji"));
    cases.add(eval("E15", "苏州", "苏州想逛有摇橹船的千年古街夜景", "attraction:suzhou-shantang-street", "attraction:suzhou-pingjiang-road", "cluster:suzhou-shanwan-history"));
    cases.add(eval("E16", "苏州", "从苏州去上海当天往返大概要多久", "connection:suzhou-shanghai", "rule:rule-009"));
    cases.add(eval("E17", "杭州", "杭州到上海坐高铁大概多长时间", "connection:hangzhou-shanghai", "rule:rule-009"));
    return List.copyOf(cases);
  }

  private static EvalCase eval(String id, String city, String query, String... expectedIds) {
    return new EvalCase(id, city, query, Set.of(expectedIds));
  }

  private record EvalCase(String id, String city, String query, Set<String> expectedIds) {}

  private static final class Metrics {
    private final String name;
    private int hitAt1;
    private int hitAt4;
    private double reciprocalRank;

    private Metrics(String name) {
      this.name = name;
    }

    private void record(EvalCase eval, List<InMemoryTravelVectorStore.Hit> hits) {
      int rank = firstRelevantRank(hits, eval.expectedIds());
      if (rank == 1) hitAt1++;
      if (rank > 0) {
        hitAt4++;
        reciprocalRank += 1.0 / rank;
      }
    }

    private void print(int total) {
      System.out.printf(
          Locale.ROOT,
          "%s: Hit@1=%d/%d (%.1f%%), Hit@4=%d/%d (%.1f%%), MRR@4=%.3f%n",
          name,
          hitAt1,
          total,
          hitAt1 * 100.0 / total,
          hitAt4,
          total,
          hitAt4 * 100.0 / total,
          reciprocalRank / total);
    }
  }
}
