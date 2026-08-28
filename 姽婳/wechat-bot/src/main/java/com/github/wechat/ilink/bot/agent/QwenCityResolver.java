package com.github.wechat.ilink.bot.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.bot.llm.QwenClient;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 使用千问结构化输出，从自由文本中提取中国旅行目的地。 */
public final class QwenCityResolver implements TravelBriefSkill.CityResolver {
  private static final Logger log = LoggerFactory.getLogger(QwenCityResolver.class);
  private static final String SYSTEM_INSTRUCTION =
      "你是旅行目的地解析器。提取用户明确想去的一个中国城市或地级行政区。"
          + "不要把出发地、景点名或省份误当目的地；无法确定时返回空字符串。"
          + "城市名使用常用简称并去掉‘市’，例如北京市返回北京。"
          + "只返回JSON对象：{\"destination\":\"北京\"}。";

  private final QwenClient qwenClient;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public QwenCityResolver(QwenClient qwenClient) {
    this.qwenClient = qwenClient;
  }

  @Override
  public String resolve(String message) {
    try {
      String json = qwenClient.chatJson(SYSTEM_INSTRUCTION, message);
      JsonNode root = objectMapper.readTree(json);
      String destination = root.path("destination").asText("").trim();
      log.info("Qwen resolved travel destination: {}", destination.isBlank() ? "<unknown>" : destination);
      return destination;
    } catch (IOException | RuntimeException e) {
      log.warn("Qwen city resolution failed: {}", e.getMessage());
      return "";
    }
  }
}
