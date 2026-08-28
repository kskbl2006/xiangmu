package com.github.wechat.ilink.bot.travelrag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.bot.config.AppConfig;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 识别旅行请求、执行语义检索并构建有依据的模型提示词。 */
public final class TravelRagService {
  private static final Logger log = LoggerFactory.getLogger(TravelRagService.class);
  private static final Set<String> TRAVEL_KEYWORDS =
      Set.of(
          "旅行", "旅游", "行程", "景点", "游玩", "攻略", "路线", "门票", "预约", "亲子",
          "夜景", "博物馆", "园林", "预算", "几日游", "一日游", "两日游", "三日游");
  private static final Set<String> TRAVEL_CONTEXT_KEYWORDS =
      Set.of(
          "哪里", "去哪", "怎么玩", "怎么去", "往返", "多久", "高铁", "酒店", "住宿", "餐厅",
          "出行", "逛", "夜景", "门票", "预约");
  private static final List<String> CITIES = List.of("上海", "杭州", "苏州");
  private static final Set<String> UNSUPPORTED_CITIES =
      Set.of("北京", "广州", "深圳", "南京", "成都", "重庆", "西安", "武汉", "长沙", "青岛", "厦门", "三亚");

  private final AppConfig config;
  private final QwenEmbeddingClient embeddingClient;
  private final InMemoryTravelVectorStore vectorStore;

  public TravelRagService(
      AppConfig config,
      QwenEmbeddingClient embeddingClient,
      InMemoryTravelVectorStore vectorStore) {
    this.config = config;
    this.embeddingClient = embeddingClient;
    this.vectorStore = vectorStore;
  }

  public static TravelRagService fromBundledIndex(AppConfig config, ObjectMapper objectMapper) {
    InMemoryTravelVectorStore store =
        InMemoryTravelVectorStore.fromResource(
            objectMapper, "/travel-rag/travel-rag-index.json");
    if (!store.model().equals(config.getQwenEmbeddingModel())
        || store.dimension() != config.getQwenEmbeddingDimension()) {
      throw new IllegalStateException(
          "travel vector index model/dimension does not match runtime embedding configuration");
    }
    return new TravelRagService(config, new QwenEmbeddingClient(config), store);
  }

  public List<InMemoryTravelVectorStore.Hit> retrieve(String userMessage) throws IOException {
    return retrieve(userMessage, config.getTravelRagTopK());
  }

  /** 为长行程检索更多候选，同时保留范围限制。 */
  public List<InMemoryTravelVectorStore.Hit> retrieve(String userMessage, int limit)
      throws IOException {
    if (!config.isTravelRagEnabled() || !isTravelRequest(userMessage)) {
      return List.of();
    }
    if (detectCity(userMessage) == null && mentionsUnsupportedCity(userMessage)) {
      log.info("Travel RAG skipped: the requested city is outside the bundled Shanghai/Hangzhou/Suzhou index");
      return List.of();
    }
    List<Double> queryVector = embeddingClient.embed(userMessage);
    List<InMemoryTravelVectorStore.Hit> semanticHits =
        vectorStore.search(
            queryVector,
            detectCity(userMessage),
            vectorStore.size(),
            config.getTravelRagMinScore());
    List<InMemoryTravelVectorStore.Hit> hits =
        semanticHits.stream()
            .map(
                hit ->
                    new InMemoryTravelVectorStore.Hit(
                        hit.chunk(), hit.score() + constraintBonus(userMessage, hit.chunk())))
            .sorted(Comparator.comparingDouble(InMemoryTravelVectorStore.Hit::score).reversed())
            .limit(Math.max(1, Math.min(20, limit)))
            .toList();
    log.info(
        "Travel RAG retrieved {} chunk(s): {}",
        hits.size(),
        hits.stream()
            .map(hit -> hit.chunk().id() + "@" + String.format(Locale.ROOT, "%.3f", hit.score()))
            .toList());
    return hits;
  }

  /** 仅返回景点候选，避免规则和聚类占用行程位置。 */
  public List<InMemoryTravelVectorStore.Hit> retrieveAttractionsForPlanning(
      String userMessage, int limit) throws IOException {
    if (!config.isTravelRagEnabled() || !isTravelRequest(userMessage)) return List.of();
    if (detectCity(userMessage) == null && mentionsUnsupportedCity(userMessage)) return List.of();
    List<Double> queryVector = embeddingClient.embed(userMessage);
    List<InMemoryTravelVectorStore.Hit> hits =
        vectorStore
            .search(
                queryVector,
                detectCity(userMessage),
                vectorStore.size(),
                config.getTravelRagMinScore())
            .stream()
            .filter(hit -> "attraction".equals(hit.chunk().type()))
            .map(
                hit ->
                    new InMemoryTravelVectorStore.Hit(
                        hit.chunk(), hit.score() + constraintBonus(userMessage, hit.chunk())))
            .sorted(Comparator.comparingDouble(InMemoryTravelVectorStore.Hit::score).reversed())
            .limit(Math.max(1, Math.min(20, limit)))
            .toList();
    log.info(
        "Travel planning RAG retrieved {} attraction(s): {}",
        hits.size(),
        hits.stream().map(hit -> hit.chunk().id()).toList());
    return hits;
  }

  public int size() {
    return vectorStore.size();
  }

  public String model() {
    return vectorStore.model();
  }

  static boolean isTravelRequest(String message) {
    if (message == null || message.isBlank()) return false;
    boolean hasTravelKeyword = TRAVEL_KEYWORDS.stream().anyMatch(message::contains);
    boolean hasSupportedCity = CITIES.stream().anyMatch(message::contains);
    boolean hasTravelContext = TRAVEL_CONTEXT_KEYWORDS.stream().anyMatch(message::contains);
    return hasTravelKeyword || (hasSupportedCity && hasTravelContext);
  }

  static String detectCity(String message) {
    if (message == null) return null;
    return CITIES.stream().filter(message::contains).findFirst().orElse(null);
  }

  static boolean mentionsUnsupportedCity(String message) {
    return message != null && UNSUPPORTED_CITIES.stream().anyMatch(message::contains);
  }

  static double constraintBonus(String message, TravelKnowledgeChunk chunk) {
    String query = message == null ? "" : message;
    String content = chunk.title() + " " + chunk.text();
    double bonus = 0;
    if (containsAny(query, "下雨", "雨天", "暴雨")) {
      if (containsAny(content, "博物馆", "纪念馆", "室内", "展览")) bonus += 0.25;
      if (containsAny(content, "野生动物园", "外滩", "湿地", "园林", "湖", "古街")) {
        bonus -= 0.12;
      }
    }
    if (containsAny(query, "孩子", "儿童", "亲子")) {
      if (containsAny(content, "亲子", "儿童", "博物馆", "动物园", "迪士尼", "主题乐园")) {
        bonus += 0.08;
      }
    }
    if (containsAny(query, "免费", "省钱", "预算低", "预算比较低")) {
      if (content.contains("免费")) bonus += 0.14;
    }
    if (containsAny(query, "路线", "行程", "不绕", "几日游", "一日游", "两日游", "三日游")) {
      if ("cluster".equals(chunk.type()) || "rule".equals(chunk.type())) bonus += 0.08;
    }
    if (containsAny(query, "老人", "低体力", "少走路")) {
      if (containsAny(content, "步行", "徒步", "爬", "登山")) bonus -= 0.08;
    }
    return bonus;
  }

  private static boolean containsAny(String value, String... candidates) {
    for (String candidate : candidates) {
      if (value.contains(candidate)) return true;
    }
    return false;
  }

  public static String enhancePrompt(
      String userMessage, List<InMemoryTravelVectorStore.Hit> hits) {
    if (hits == null || hits.isEmpty()) return userMessage;
    StringBuilder prompt =
        new StringBuilder(
            "你正在生成旅行建议。以下资料来自演示知识库，部分票价、开放时间和预约规则未经实时核验。"
                + "请优先使用资料中的稳定信息；涉及动态政策时明确提醒用户出发前通过官方渠道确认。"
                + "不要编造资料中没有的实时余票、酒店价格或营业状态。\n\n参考资料：\n");
    for (int index = 0; index < hits.size(); index++) {
      TravelKnowledgeChunk chunk = hits.get(index).chunk();
      prompt
          .append(index + 1)
          .append(". [")
          .append(chunk.city())
          .append("/")
          .append(chunk.type())
          .append("] ")
          .append(chunk.title())
          .append("：")
          .append(chunk.text())
          .append("\n");
    }
    return prompt.append("\n用户需求：").append(userMessage).toString();
  }
}
