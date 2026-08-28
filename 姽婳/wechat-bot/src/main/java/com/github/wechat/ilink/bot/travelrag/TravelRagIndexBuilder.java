package com.github.wechat.ilink.bot.travelrag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.bot.config.AppConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 离线整理演示数据并保存稠密向量的工具。 */
public final class TravelRagIndexBuilder {
  private static final Logger log = LoggerFactory.getLogger(TravelRagIndexBuilder.class);
  private static final int MAX_CHUNK_CHARS = 700;

  private TravelRagIndexBuilder() {}

  public static void main(String[] args) throws Exception {
    Path sourceDirectory =
        args.length >= 1 ? Path.of(args[0]) : Path.of("..", "travel-rag-data");
    Path output =
        args.length >= 2
            ? Path.of(args[1])
            : Path.of("src", "main", "resources", "travel-rag", "travel-rag-index.json");
    AppConfig config = AppConfig.fromEnvironment();
    ObjectMapper objectMapper = new ObjectMapper();
    List<DraftChunk> drafts =
        Files.isRegularFile(sourceDirectory.resolve("attractions.json"))
            ? loadDrafts(objectMapper, sourceDirectory)
            : loadDraftsFromExistingIndex(objectMapper, output);
    log.info(
        "Generating {} travel vectors with model={} dimension={}",
        drafts.size(),
        config.getQwenEmbeddingModel(),
        config.getQwenEmbeddingDimension());
    List<List<Double>> vectors =
        new QwenEmbeddingClient(config).embedAll(drafts.stream().map(DraftChunk::text).toList());
    List<TravelKnowledgeChunk> chunks = new ArrayList<>();
    for (int index = 0; index < drafts.size(); index++) {
      DraftChunk draft = drafts.get(index);
      chunks.add(
          new TravelKnowledgeChunk(
              draft.id(),
              draft.city(),
              draft.type(),
              draft.title(),
              draft.text(),
              draft.sourceStatus(),
              vectors.get(index)));
    }
    TravelVectorIndex vectorIndex =
        new TravelVectorIndex(
            config.getQwenEmbeddingModel(),
            config.getQwenEmbeddingDimension(),
            OffsetDateTime.now().toString(),
            "Demo dataset: dynamic prices, schedules and reservation rules may be stale; verify before travel.",
            chunks);
    Path absoluteOutput = output.toAbsolutePath();
    Files.createDirectories(absoluteOutput.getParent());
    objectMapper.writerWithDefaultPrettyPrinter().writeValue(absoluteOutput.toFile(), vectorIndex);
    log.info("Wrote travel vector index: path={}, chunks={}", absoluteOutput, chunks.size());
  }

  static List<DraftChunk> loadDrafts(ObjectMapper mapper, Path sourceDirectory) throws Exception {
    List<DraftChunk> drafts = new ArrayList<>();
    loadAttractions(mapper, sourceDirectory.resolve("attractions.json"), drafts);
    loadRules(mapper, sourceDirectory.resolve("travel-rules.json"), drafts);
    loadClusters(mapper, sourceDirectory.resolve("area-clusters.json"), drafts);
    Set<String> ids = new LinkedHashSet<>();
    for (DraftChunk draft : drafts) {
      if (!ids.add(draft.id())) {
        throw new IllegalArgumentException("duplicate travel chunk id: " + draft.id());
      }
    }
    return List.copyOf(drafts);
  }

  private static List<DraftChunk> loadDraftsFromExistingIndex(ObjectMapper mapper, Path output)
      throws Exception {
    if (!Files.isRegularFile(output)) {
      throw new IllegalArgumentException(
          "travel source data and existing vector index are both missing");
    }
    TravelVectorIndex existing = mapper.readValue(output.toFile(), TravelVectorIndex.class);
    log.warn("Raw demo data is absent; rebuilding vectors from the existing normalized chunks");
    return existing.chunks().stream()
        .map(
            chunk ->
                new DraftChunk(
                    chunk.id(),
                    chunk.city(),
                    chunk.type(),
                    chunk.title(),
                    chunk.text(),
                    chunk.sourceStatus()))
        .toList();
  }

  private static void loadAttractions(ObjectMapper mapper, Path path, List<DraftChunk> drafts)
      throws Exception {
    JsonNode root = mapper.readTree(path.toFile());
    for (JsonNode attraction : root.path("attractions")) {
      String id = required(attraction, "id");
      String city = required(attraction, "city");
      String name = required(attraction, "name");
      StringBuilder text = new StringBuilder(name).append("位于").append(city);
      append(text, "行政区", attraction.path("district"));
      append(text, "类别", attraction.path("category"));
      append(text, "标签", attraction.path("tags"));
      append(text, "亮点", attraction.path("highlights"));
      append(text, "地址", attraction.path("address"));
      append(text, "开放信息", attraction.path("open_hours"));
      append(text, "门票参考", attraction.path("ticket"));
      append(text, "预约提示", attraction.path("reservation"));
      append(text, "交通", attraction.path("transport"));
      append(text, "补充说明", attraction.path("notes"));
      String sourceStatus =
          attraction.hasNonNull("official_url") || attraction.hasNonNull("source")
              ? "partially_verified"
              : "demo_unverified";
      drafts.add(
          new DraftChunk(
              "attraction:" + id,
              city,
              "attraction",
              name,
              limit(text.toString()),
              sourceStatus));
    }
  }

  private static void loadRules(ObjectMapper mapper, Path path, List<DraftChunk> drafts)
      throws Exception {
    JsonNode root = mapper.readTree(path.toFile());
    for (JsonNode rule : root.path("rules")) {
      String id = required(rule, "id");
      String city = normalizeCity(required(rule, "city"));
      String title = required(rule, "title");
      StringBuilder text = new StringBuilder(title);
      append(text, "规则", rule.path("content"));
      append(text, "适用景点", rule.path("applies_to"));
      append(text, "类别", rule.path("category"));
      drafts.add(
          new DraftChunk(
              "rule:" + id,
              city,
              "rule",
              title,
              limit(text.toString()),
              "demo_unverified"));
    }
  }

  private static void loadClusters(ObjectMapper mapper, Path path, List<DraftChunk> drafts)
      throws Exception {
    JsonNode root = mapper.readTree(path.toFile());
    for (JsonNode cityGroup : root.path("city_clusters")) {
      String city = required(cityGroup, "city");
      for (JsonNode cluster : cityGroup.path("clusters")) {
        String id = required(cluster, "cluster_id");
        String name = required(cluster, "name");
        StringBuilder text = new StringBuilder(name);
        append(text, "主题", cluster.path("theme"));
        append(text, "简介", cluster.path("summary"));
        append(text, "包含景点", cluster.path("attractions"));
        append(text, "建议时长", cluster.path("recommended_duration"));
        append(text, "参考顺序", cluster.path("recommended_order"));
        append(text, "说明", cluster.path("notes"));
        drafts.add(
            new DraftChunk(
                "cluster:" + id,
                city,
                "cluster",
                name,
                limit(text.toString()),
                "demo_unverified"));
      }
    }
    for (JsonNode connection : root.path("inter_city_connections")) {
      String from = required(connection, "from");
      String to = required(connection, "to");
      String title = from + "至" + to + "城际交通";
      StringBuilder text = new StringBuilder(title);
      append(text, "方式", connection.path("connection_type"));
      append(text, "参考用时", connection.path("duration"));
      append(text, "班次说明", connection.path("frequency"));
      append(text, "参考价格", connection.path("price"));
      append(text, "说明", connection.path("notes"));
      drafts.add(
          new DraftChunk(
              "connection:" + romanizeCity(from) + "-" + romanizeCity(to),
              from,
              "connection",
              title,
              limit(text.toString()),
              "demo_unverified"));
    }
  }

  private static void append(StringBuilder text, String label, JsonNode value) {
    if (value == null || value.isMissingNode() || value.isNull() || value.isEmpty()) return;
    String normalized;
    if (value.isArray()) {
      normalized =
          java.util.stream.StreamSupport.stream(value.spliterator(), false)
              .map(JsonNode::asText)
              .filter(item -> !item.isBlank())
              .reduce((left, right) -> left + "、" + right)
              .orElse("");
    } else {
      normalized = value.asText();
    }
    if (!normalized.isBlank()) {
      text.append("；").append(label).append("：").append(normalized);
    }
  }

  private static String required(JsonNode node, String field) {
    String value = node.path(field).asText();
    if (value.isBlank()) throw new IllegalArgumentException("missing field: " + field);
    return value;
  }

  private static String normalizeCity(String value) {
    if (value.contains("上海")) return "上海";
    if (value.contains("杭州")) return "杭州";
    if (value.contains("苏州")) return "苏州";
    return value;
  }

  private static String romanizeCity(String city) {
    return switch (city) {
      case "上海" -> "shanghai";
      case "杭州" -> "hangzhou";
      case "苏州" -> "suzhou";
      default -> Integer.toHexString(city.hashCode());
    };
  }

  private static String limit(String text) {
    String normalized = text.replaceAll("\\s+", " ").trim();
    return normalized.length() <= MAX_CHUNK_CHARS
        ? normalized
        : normalized.substring(0, MAX_CHUNK_CHARS);
  }

  record DraftChunk(
      String id,
      String city,
      String type,
      String title,
      String text,
      String sourceStatus) {}
}
